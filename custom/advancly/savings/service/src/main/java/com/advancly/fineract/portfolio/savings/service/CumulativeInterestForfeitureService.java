/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package com.advancly.fineract.portfolio.savings.service;

import com.advancly.fineract.portfolio.savings.domain.AdvanclyChargeInterestRule;
import com.advancly.fineract.portfolio.savings.domain.AdvanclyChargeInterestRuleRepository;
import com.advancly.fineract.portfolio.savings.domain.DepositInterestChargeApplication;
import com.advancly.fineract.portfolio.savings.domain.DepositInterestChargeApplicationRepository;
import com.advancly.fineract.portfolio.savings.domain.InterestBasisMode;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.apache.fineract.accounting.journalentry.service.JournalEntryWritePlatformService;
import org.apache.fineract.organisation.monetary.domain.Money;
import org.apache.fineract.portfolio.charge.domain.Charge;
import org.apache.fineract.portfolio.charge.domain.ChargeCalculationType;
import org.apache.fineract.portfolio.note.domain.Note;
import org.apache.fineract.portfolio.note.domain.NoteRepository;
import org.apache.fineract.portfolio.savings.domain.SavingsAccount;
import org.apache.fineract.portfolio.savings.domain.SavingsAccountCharge;
import org.apache.fineract.portfolio.savings.domain.SavingsAccountChargePaidBy;
import org.apache.fineract.portfolio.savings.domain.SavingsAccountRepositoryWrapper;
import org.apache.fineract.portfolio.savings.domain.SavingsAccountTransaction;
import org.apache.fineract.portfolio.savings.service.SavingsAccountWritePlatformService;
import org.springframework.context.annotation.Lazy;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Cumulative early-withdrawal interest forfeiture: an early withdrawal takes back the configured percentage of net
 * interest still available in the account, computed and charged immediately rather than deferred to the next interest
 * posting the way per-period mode is.
 *
 * Runs at the write-platform layer, not from an entity hook, because it force-posts interest - and that posting does
 * saveAndFlush and writes journal entries, which would be re-entrant if invoked from inside a withdrawal already in
 * progress. Premature closure establishes the same shape at the same layer.
 *
 * Forced interest posting runs with the account's real withholding-tax configuration intact. If interest has already
 * posted on the forced posting date, this service reuses that posting instead of posting again.
 *
 * This service owns the summary refresh and the GL journal entries for everything it writes, because neither its
 * withdrawal caller nor its closure caller can do either for it:
 * <ul>
 * <li><b>Summary</b> - the forced posting call refreshes the summary as its own last step, so the forfeiture and
 * deferred-tax transactions this service adds AFTERWARDS are invisible to it. Premature closure reads
 * {@code account.getSummary().getAccountBalance()} the moment this method returns, to decide the settlement payout, so
 * the refresh has to happen here (see {@code SavingsAccount#refreshSummary(boolean)}).</li>
 * <li><b>Journal entries</b> - every caller journals only the transactions that are NOT in a "transaction ids that
 * already existed" snapshot it takes for itself, and every one of those snapshots is taken AFTER this method returns.
 * The rows this service writes are flushed (and so have database ids) by then, so they would be silently classified as
 * pre-existing and never journaled. Taking the snapshot here, before writing anything, and posting the entries here is
 * what keeps them out of the caller's "new" set and in this one - journaled exactly once.</li>
 * </ul>
 */
@Component
public class CumulativeInterestForfeitureService {

    private static final DateTimeFormatter NOTE_DATE = DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.ENGLISH);

    private final AdvanclyChargeInterestRuleRepository chargeInterestRuleRepository;
    private final DepositInterestChargeApplicationRepository interestChargeApplicationRepository;
    private final SavingsAccountWritePlatformService savingsAccountWritePlatformService;
    private final NoteRepository noteRepository;
    private final SavingsAccountRepositoryWrapper savingsAccountRepository;
    private final JournalEntryWritePlatformService journalEntryWritePlatformService;
    private final JdbcTemplate jdbcTemplate;

    // @Lazy breaks a genuine bean cycle: AdvanclySavingsAccountWritePlatformService (@Primary) injects this service to
    // trigger forfeiture, and this service injects it back to force-post interest. Without @Lazy the context fails to
    // start with a circular-reference error. The proxy resolves on first use, long after startup.
    public CumulativeInterestForfeitureService(final AdvanclyChargeInterestRuleRepository chargeInterestRuleRepository,
            final DepositInterestChargeApplicationRepository interestChargeApplicationRepository,
            @Lazy final SavingsAccountWritePlatformService savingsAccountWritePlatformService, final NoteRepository noteRepository,
            final SavingsAccountRepositoryWrapper savingsAccountRepository,
            final JournalEntryWritePlatformService journalEntryWritePlatformService, final JdbcTemplate jdbcTemplate) {
        this.chargeInterestRuleRepository = chargeInterestRuleRepository;
        this.interestChargeApplicationRepository = interestChargeApplicationRepository;
        this.savingsAccountWritePlatformService = savingsAccountWritePlatformService;
        this.noteRepository = noteRepository;
        this.savingsAccountRepository = savingsAccountRepository;
        this.journalEntryWritePlatformService = journalEntryWritePlatformService;
        this.jdbcTemplate = jdbcTemplate;
    }

    public boolean appliesTo(final SavingsAccount account, final BigDecimal chargePercentageOverride) {
        return resolveQualifyingChargeWithPercentage(account, chargePercentageOverride) != null;
    }

    /**
     * @param exitDate
     *            the date of the early exit this forfeiture belongs to - the withdrawal date, or, for a premature
     *            closure, the closure date.
     * @param isPrematureClosure
     *            whether this exit is a premature closure rather than an ordinary withdrawal. It decides only which
     *            date the forced interest posting transaction is dated - see
     *            {@link #forcedPostingDate(SavingsAccount, LocalDate, boolean)}.
     * @return the forfeiture transaction, or {@code null} when there was nothing to forfeit (no qualifying charge, or
     *         no interest posted yet - the latter being how a withdrawal taken before the first posting correctly costs
     *         the customer nothing rather than eating into principal).
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public SavingsAccountTransaction forfeitIfApplicable(final SavingsAccount account, final LocalDate exitDate,
            final boolean backdatedTxnsAllowedTill, final boolean isPrematureClosure) {
        return forfeitIfApplicable(account, null, exitDate, backdatedTxnsAllowedTill, isPrematureClosure, null);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public SavingsAccountTransaction forfeitIfApplicable(final SavingsAccount account, final LocalDate exitDate,
            final boolean backdatedTxnsAllowedTill, final boolean isPrematureClosure, final BigDecimal chargePercentageOverride) {
        return forfeitIfApplicable(account, null, exitDate, backdatedTxnsAllowedTill, isPrematureClosure, chargePercentageOverride);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public SavingsAccountTransaction forfeitIfApplicable(final SavingsAccount account,
            final SavingsAccountTransaction withdrawalTransaction, final LocalDate exitDate, final boolean backdatedTxnsAllowedTill,
            final boolean isPrematureClosure, final BigDecimal chargePercentageOverride) {
        final QualifyingCharge qualifying = resolveQualifyingChargeWithPercentage(account, chargePercentageOverride);
        if (qualifying == null) {
            return null;
        }

        // Force-post first so the accrual up to this exit becomes real, credited interest before any of it is taken
        // back. postInterestAs=true is load-bearing: it persists is_manual=true, which later recalculations re-derive
        // as a genuine posting-period boundary, so the next scheduled run posts only the increment instead of
        // re-posting the whole period on top of this one.
        //
        final LocalDate forcedPostingDate = forcedPostingDate(account, exitDate, isPrematureClosure);
        SavingsAccountTransaction interestPostingOnForcedDate = latestInterestPostingOn(account, forcedPostingDate);
        if (interestPostingOnForcedDate == null) {
            this.savingsAccountWritePlatformService.postInterest(account, true, forcedPostingDate, backdatedTxnsAllowedTill);
            interestPostingOnForcedDate = latestInterestPostingOn(account, forcedPostingDate);
            noteOn(account, interestPostingOnForcedDate, "Interest accrued on pro rata basis from period start to day before withdrawal");
        }

        // Snapshotted HERE - after the forced posting (which journals and flushes everything it wrote itself) and
        // before this method writes anything of its own - so that the forfeiture transaction below
        // are the only ones this method's own journal-posting step at the end treats as new. See the class javadoc for
        // why the caller's later snapshot cannot do this job.
        final Set<Long> existingTransactionIds = new HashSet<>(
                backdatedTxnsAllowedTill ? account.findCurrentTransactionIdsWithPivotDateConfig() : account.findExistingTransactionIds());
        final Set<Long> existingReversedTransactionIds = new HashSet<>(
                backdatedTxnsAllowedTill ? account.findCurrentReversedTransactionIdsWithPivotDateConfig()
                        : account.findExistingReversedTransactionIds());

        final BigDecimal alreadyForfeited = sumActiveAppliedAmount(account.getId());
        final BigDecimal amount = CumulativeForfeitureCalculator.forfeitureAmount(account.getSummary().getTotalInterestPosted(),
                account.getSummary().getTotalWithholdTax(), alreadyForfeited, qualifying.percentage());

        boolean wroteAnything = false;

        SavingsAccountTransaction forfeiture = null;
        if (amount.compareTo(BigDecimal.ZERO) > 0) {
            forfeiture = writeForfeiture(account, withdrawalTransaction, qualifying, exitDate, amount, alreadyForfeited,
                    backdatedTxnsAllowedTill);
            wroteAnything = true;
        }

        if (wroteAnything) {
            // Order matters: the summary must be current before the account is flushed (its derived columns are
            // persisted by that flush), the flush must happen before the journal entries (they carry the transaction
            // ids the flush assigns), and the flush is also what puts those ids into the caller's later snapshot so
            // the caller does not journal them a second time.
            account.refreshSummary(backdatedTxnsAllowedTill);
            this.savingsAccountRepository.saveAndFlush(account);
            refreshPostedDerivedChargeColumn(account.getId());
            postJournalEntries(account, existingTransactionIds, existingReversedTransactionIds, backdatedTxnsAllowedTill);
        }

        return forfeiture;
    }

    /**
     * Where the forced interest posting transaction lands - which is what makes the shared period-splitting logic cap
     * the ACCRUAL at the day before it (see {@code SavingsHelper#determineInterestPostingPeriods}).
     *
     * A premature closure uses exactly the date core's own per-period closure path uses,
     * {@code SavingsAccount#interestPostingTransactionDateForClosure(closedDate)}, so both modes post the closing
     * interest for the identical span. The two are NOT interchangeable: for the early closure this service handles that
     * method returns {@code closedDate} itself, so dating the posting {@code closedDate - 1} instead would silently
     * drop the closure day's predecessor from the accrual - invisible at 100% forfeiture (all of it is taken back
     * anyway) but a real shortfall in what the customer keeps at any partial percentage.
     *
     * An ordinary withdrawal has no such core helper and deliberately keeps {@code withdrawalDate - 1}: the forced
     * posting must sort strictly BEFORE the withdrawal in the balance walk, which a same-dated transaction does not
     * guarantee. A closure is unaffected by that concern because its own settlement withdrawal is issued afterwards by
     * core, from the already-refreshed balance, exactly as the per-period closure path does.
     */
    private LocalDate forcedPostingDate(final SavingsAccount account, final LocalDate exitDate, final boolean isPrematureClosure) {
        return isPrematureClosure ? account.interestPostingTransactionDateForClosure(exitDate) : exitDate.minusDays(1);
    }

    private SavingsAccountTransaction writeForfeiture(final SavingsAccount account, final SavingsAccountTransaction withdrawalTransaction,
            final QualifyingCharge qualifying, final LocalDate exitDate, final BigDecimal amount, final BigDecimal alreadyForfeited,
            final boolean backdatedTxnsAllowedTill) {
        final SavingsAccountCharge accountCharge = qualifying.accountCharge();
        final SavingsAccountTransaction forfeiture = SavingsAccountTransaction.interestCharge(account, account.office(), exitDate,
                Money.of(account.getCurrency(), amount));
        final BigDecimal appliedAmount = forfeiture.getAmount();

        // Mirrors SavingsAccount.payCharge(...): pay(...) before building the SavingsAccountChargePaidBy link, so the
        // charge's own paid/outstanding bookkeeping stays symmetric with undoTransaction's undoPayment(...).
        accountCharge.pay(account.getCurrency(), Money.of(account.getCurrency(), appliedAmount));
        forfeiture.getSavingsAccountChargesPaid().add(SavingsAccountChargePaidBy.instance(forfeiture, accountCharge, appliedAmount));
        if (backdatedTxnsAllowedTill) {
            account.addTransactionToExisting(forfeiture);
        } else {
            account.addTransaction(forfeiture);
        }
        noteOn(account, forfeiture, "Interest forfeiture due to early withdrawal on " + exitDate.format(NOTE_DATE));

        final SavingsAccountTransaction effectiveWithdrawalTransaction = withdrawalTransaction == null ? forfeiture : withdrawalTransaction;
        final BigDecimal originalBasisAmount = forfeitableBasis(account);
        this.interestChargeApplicationRepository.saveAndFlush(DepositInterestChargeApplication.createNew(account, accountCharge.getCharge(),
                effectiveWithdrawalTransaction, forfeiture, exitDate, selectedFromDate(account, exitDate), exitDate,
                InterestBasisMode.CUMULATIVE, null, qualifying.percentage(), originalBasisAmount, alreadyForfeited, appliedAmount));

        return forfeiture;
    }

    /** Same two lines both {@code SavingsAccountDomainService#postJournalEntries} implementations run. */
    private void postJournalEntries(final SavingsAccount account, final Set<Long> existingTransactionIds,
            final Set<Long> existingReversedTransactionIds, final boolean backdatedTxnsAllowedTill) {
        final boolean isAccountTransfer = false;
        final Map<String, Object> accountingBridgeData = account.deriveAccountingBridgeData(account.getCurrency().getCode(),
                existingTransactionIds, existingReversedTransactionIds, isAccountTransfer, backdatedTxnsAllowedTill);
        this.journalEntryWritePlatformService.createJournalEntriesForSavings(accountingBridgeData);
    }

    /**
     * Keeps the posted reporting column in sync with the charge-application ledger after cumulative forfeiture writes
     * its already-applied row.
     */
    private void refreshPostedDerivedChargeColumn(final Long accountId) {
        final BigDecimal posted = sumActiveAppliedAmount(accountId);
        this.jdbcTemplate.update("update m_savings_account set total_interest_charge_derived = ? where id = ?", posted, accountId);
    }

    private BigDecimal sumActiveAppliedAmount(final Long accountId) {
        final BigDecimal amount = this.interestChargeApplicationRepository.sumActiveAppliedAmountForAccount(accountId);
        return amount == null ? BigDecimal.ZERO : amount;
    }

    private BigDecimal forfeitableBasis(final SavingsAccount account) {
        final BigDecimal postedInterest = account.getSummary().getTotalInterestPosted() == null ? BigDecimal.ZERO
                : account.getSummary().getTotalInterestPosted();
        final BigDecimal withholdingTax = account.getSummary().getTotalWithholdTax() == null ? BigDecimal.ZERO
                : account.getSummary().getTotalWithholdTax();
        return postedInterest.subtract(withholdingTax).max(BigDecimal.ZERO);
    }

    private LocalDate selectedFromDate(final SavingsAccount account, final LocalDate exitDate) {
        final LocalDate startInterestCalculationDate = account.getStartInterestCalculationDate();
        return startInterestCalculationDate != null ? startInterestCalculationDate : exitDate;
    }

    private SavingsAccountTransaction latestInterestPostingOn(final SavingsAccount account, final LocalDate date) {
        SavingsAccountTransaction found = null;
        for (final SavingsAccountTransaction transaction : account.getTransactions()) {
            if (transaction.isInterestPostingAndNotReversed() && date.equals(transaction.getTransactionDate())) {
                found = transaction;
            }
        }
        return found;
    }

    private void noteOn(final SavingsAccount account, final SavingsAccountTransaction transaction, final String text) {
        if (transaction == null) {
            return;
        }
        this.noteRepository.save(Note.savingsTransactionNote(account, transaction, text));
    }

    private QualifyingCharge resolveQualifyingChargeWithPercentage(final SavingsAccount account,
            final BigDecimal chargePercentageOverride) {
        final List<AdvanclyChargeInterestRule> rules = this.chargeInterestRuleRepository.findBySavingsProductId(account.productId());
        if (rules.size() != 1 || !rules.get(0).isCumulative()) {
            return null;
        }
        final SavingsAccountCharge accountCharge = resolveAccountCharge(account, rules.get(0).chargeId());
        if (accountCharge == null) {
            return null;
        }
        final BigDecimal percentage = resolvePercentage(account, accountCharge, chargePercentageOverride);
        if (percentage == null || percentage.compareTo(BigDecimal.ZERO) <= 0) {
            return null;
        }
        return new QualifyingCharge(accountCharge, percentage);
    }

    private SavingsAccountCharge resolveAccountCharge(final SavingsAccount account, final Long chargeId) {
        for (final SavingsAccountCharge accountCharge : account.charges()) {
            final Charge definition = accountCharge.getCharge();
            if (definition == null || !chargeId.equals(definition.getId())) {
                continue;
            }
            if (!accountCharge.isActive()) {
                continue;
            }
            if (!accountCharge.isPenaltyCharge() && !definition.isPenalty()) {
                continue;
            }
            if (definition.getChargeCalculation() == null
                    || !ChargeCalculationType.fromInt(definition.getChargeCalculation()).isPercentageOfInterest()) {
                continue;
            }
            return accountCharge;
        }
        return null;
    }

    private BigDecimal resolvePercentage(final SavingsAccount account, final SavingsAccountCharge accountCharge,
            final BigDecimal chargePercentageOverride) {
        final BigDecimal transactionOverride = chargePercentageOverride != null ? chargePercentageOverride
                : account.earlyWithdrawalChargePercentageOverride();
        if (transactionOverride != null) {
            return transactionOverride;
        }
        final BigDecimal accountPercentage = accountCharge.getPercentage();
        if (accountPercentage != null && accountPercentage.compareTo(BigDecimal.ZERO) > 0) {
            return accountPercentage;
        }
        return accountCharge.getCharge().getAmount();
    }

    private record QualifyingCharge(SavingsAccountCharge accountCharge, BigDecimal percentage) {
    }
}
