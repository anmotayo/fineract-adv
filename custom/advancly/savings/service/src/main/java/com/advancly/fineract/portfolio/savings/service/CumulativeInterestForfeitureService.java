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
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Cumulative early-withdrawal interest forfeiture, charged immediately (not deferred to the next posting). Runs at the
 * write-platform layer because the forced interest posting flushes and journals, which would be re-entrant from an
 * entity hook. Refreshes the summary and journals its own rows: callers snapshot existing ids only after this returns.
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

    // @Lazy breaks the bean cycle with AdvanclySavingsAccountWritePlatformService, which injects this service.
    public CumulativeInterestForfeitureService(final AdvanclyChargeInterestRuleRepository chargeInterestRuleRepository,
            final DepositInterestChargeApplicationRepository interestChargeApplicationRepository,
            @Lazy final SavingsAccountWritePlatformService savingsAccountWritePlatformService, final NoteRepository noteRepository,
            final SavingsAccountRepositoryWrapper savingsAccountRepository,
            final JournalEntryWritePlatformService journalEntryWritePlatformService) {
        this.chargeInterestRuleRepository = chargeInterestRuleRepository;
        this.interestChargeApplicationRepository = interestChargeApplicationRepository;
        this.savingsAccountWritePlatformService = savingsAccountWritePlatformService;
        this.noteRepository = noteRepository;
        this.savingsAccountRepository = savingsAccountRepository;
        this.journalEntryWritePlatformService = journalEntryWritePlatformService;
    }

    public boolean appliesTo(final SavingsAccount account, final BigDecimal chargePercentageOverride) {
        return resolveQualifyingChargeWithPercentage(account, chargePercentageOverride) != null;
    }

    /** Returns {@code null} when nothing is forfeited (no qualifying charge or no interest posted yet). */
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

        // postInterestAs=true persists is_manual=true, so the next scheduled run posts only the increment.
        final LocalDate forcedPostingDate = forcedPostingDate(account, exitDate, isPrematureClosure);
        SavingsAccountTransaction interestPostingOnForcedDate = latestInterestPostingOn(account, forcedPostingDate);
        if (interestPostingOnForcedDate == null) {
            this.savingsAccountWritePlatformService.postInterest(account, true, forcedPostingDate, backdatedTxnsAllowedTill);
            interestPostingOnForcedDate = latestInterestPostingOn(account, forcedPostingDate);
            noteOn(account, interestPostingOnForcedDate, "Interest accrued on pro rata basis from period start to day before withdrawal");
        }

        // Snapshot after the forced posting (which journals itself) so only the forfeiture below is journaled here.
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
            // Order matters: refresh before flush (persists summary), flush before journaling (assigns ids).
            account.refreshSummary(backdatedTxnsAllowedTill);
            this.savingsAccountRepository.saveAndFlush(account);
            postJournalEntries(account, existingTransactionIds, existingReversedTransactionIds, backdatedTxnsAllowedTill);
        }

        return forfeiture;
    }

    /**
     * A closure uses core's closure posting date so both modes accrue the same span; a withdrawal uses the day before
     * so the posting sorts strictly before it. Package-private for EarlyWithdrawalChargeReadPlatformServiceImpl.
     */
    LocalDate forcedPostingDate(final SavingsAccount account, final LocalDate exitDate, final boolean isPrematureClosure) {
        return isPrematureClosure ? account.interestPostingTransactionDateForClosure(exitDate) : exitDate.minusDays(1);
    }

    private SavingsAccountTransaction writeForfeiture(final SavingsAccount account, final SavingsAccountTransaction withdrawalTransaction,
            final QualifyingCharge qualifying, final LocalDate exitDate, final BigDecimal amount, final BigDecimal alreadyForfeited,
            final boolean backdatedTxnsAllowedTill) {
        final SavingsAccountCharge accountCharge = qualifying.accountCharge();
        final SavingsAccountTransaction forfeiture = SavingsAccountTransaction.interestCharge(account, account.office(), exitDate,
                Money.of(account.getCurrency(), amount));
        final BigDecimal appliedAmount = forfeiture.getAmount();

        // A plain payment would drive amountOutstanding negative; see SavingsAccountCharge#payExternallyComputedCharge.
        accountCharge.payExternallyComputedCharge(account.getCurrency(), appliedAmount);
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

    private void postJournalEntries(final SavingsAccount account, final Set<Long> existingTransactionIds,
            final Set<Long> existingReversedTransactionIds, final boolean backdatedTxnsAllowedTill) {
        final boolean isAccountTransfer = false;
        final Map<String, Object> accountingBridgeData = account.deriveAccountingBridgeData(account.getCurrency().getCode(),
                existingTransactionIds, existingReversedTransactionIds, isAccountTransfer, backdatedTxnsAllowedTill);
        this.journalEntryWritePlatformService.createJournalEntriesForSavings(accountingBridgeData);
    }

    /** Package-private: also used by EarlyWithdrawalChargeReadPlatformServiceImpl. */
    BigDecimal sumActiveAppliedAmount(final Long accountId) {
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

    /** Package-private: also used by EarlyWithdrawalChargeReadPlatformServiceImpl. */
    SavingsAccountTransaction latestInterestPostingOn(final SavingsAccount account, final LocalDate date) {
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

    /** Package-private: also used by EarlyWithdrawalChargeReadPlatformServiceImpl. */
    QualifyingCharge resolveQualifyingChargeWithPercentage(final SavingsAccount account, final BigDecimal chargePercentageOverride) {
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

    record QualifyingCharge(SavingsAccountCharge accountCharge, BigDecimal percentage) {
    }
}
