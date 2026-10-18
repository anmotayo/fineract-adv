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

import com.advancly.fineract.portfolio.savings.domain.DepositAccountDynamicRateHistory;
import com.advancly.fineract.portfolio.savings.domain.DepositAccountDynamicRateHistoryRepository;
import com.advancly.fineract.portfolio.savings.domain.DynamicDepositAccount;
import com.advancly.fineract.portfolio.savings.domain.DynamicDepositRateHistoryEventType;
import com.advancly.fineract.portfolio.savings.domain.DynamicDepositRateSource;
import com.advancly.fineract.portfolio.savings.service.DynamicDepositRateResolutionService.ResolvedRate;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.apache.fineract.portfolio.savings.domain.DepositAccountTermAndPreClosure;
import org.apache.fineract.portfolio.savings.domain.SavingsAccountTransaction;
import org.apache.fineract.portfolio.savings.domain.SavingsAccountTransactionComparator;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Single entry point for recording a Dynamic Deposit account's principal-changing events (implementation plan, Section
 * 6): recomputes the invested amount from the account's own transactions, updates the
 * {@code m_deposit_account_term_and_preclosure.deposit_amount} quick-read cache, resolves (or carries forward) the
 * applicable interest rate off the account's own chart snapshot, writes the
 * {@code m_deposit_account_dynamic_rate_history} row, and - since backdating is supported - retroactively corrects
 * every already-written row whose invested amount/rate is now stale as a result.
 *
 * Called from every hook that can create a principal-changing transaction for a Dynamic Deposit account: the entity
 * level {@code DynamicDepositAccount#deposit(...)}/{@code #withdraw(...)} overrides (covers backdated top-up,
 * withdrawal, transfer-in/out, and an adjustment's replacement transaction - all of which route through those entity
 * methods) and {@code AdvanclySavingsAccountDomainService}'s optimized append-path and reversal methods (which build
 * transactions directly, bypassing the entity methods).
 *
 * The invested amount is always recomputed by summing the account's own transactions (never an incrementally maintained
 * cache), because backdating means a transaction can be inserted out of chronological order: naively adding/subtracting
 * this transaction's amount to the last-cached total would get the running TOTAL right (addition commutes) but would
 * resolve this event's own rate against the wrong invested-amount-as-of-its-date, and would leave every already-written
 * row for a later date silently stale (both its invested amount and, potentially, which rate slab it should have
 * matched). {@link #reconcileFollowingRows} walks forward and corrects those.
 */
@Component
public class DynamicDepositRateHistoryService {

    private final DepositAccountDynamicRateHistoryRepository rateHistoryRepository;
    private final DynamicDepositRateResolutionService rateResolutionService;
    private final SavingsAccountTransactionComparator transactionComparator = new SavingsAccountTransactionComparator();

    public DynamicDepositRateHistoryService(final DepositAccountDynamicRateHistoryRepository rateHistoryRepository,
            final DynamicDepositRateResolutionService rateResolutionService) {
        this.rateHistoryRepository = rateHistoryRepository;
        this.rateResolutionService = rateResolutionService;
    }

    /**
     * Records one principal-changing event for {@code account}. Safe to call unconditionally from a shared hook -
     * callers are expected to gate on {@code account instanceof DynamicDepositAccount} before calling.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void recordPrincipalChangeEvent(final DynamicDepositAccount account, final SavingsAccountTransaction transaction,
            final DynamicDepositRateHistoryEventType eventType) {
        final DepositAccountTermAndPreClosure accountTermAndPreClosure = account.accountTermAndPreClosure();
        if (accountTermAndPreClosure == null) {
            return;
        }

        final long priorRowCount = this.rateHistoryRepository.countByAccountId(account.getId());
        final DynamicDepositRateHistoryEventType resolvedEventType = priorRowCount == 0
                ? DynamicDepositRateHistoryEventType.ACCOUNT_ACTIVATION
                : eventType;

        final BigDecimal investedAmountAfterTransaction = computeInvestedAmountAsOf(account, transaction);
        final ResolvedRate resolvedRate = resolveRate(account, accountTermAndPreClosure, investedAmountAfterTransaction,
                transaction.getTransactionDate(), priorRowCount);

        final DepositAccountDynamicRateHistory historyRow = DepositAccountDynamicRateHistory.createNew(account, transaction,
                transaction.getTransactionDate(), resolvedEventType, investedAmountAfterTransaction,
                accountTermAndPreClosure.depositPeriod(),
                accountTermAndPreClosure.depositPeriodFrequencyType() == null ? null
                        : accountTermAndPreClosure.depositPeriodFrequencyType().getValue(),
                resolvedRate.interestRateChartId(), resolvedRate.interestRateSlabId(), resolvedRate.baseAnnualInterestRate(),
                resolvedRate.annualInterestRate(), resolvedRate.source());
        this.rateHistoryRepository.save(historyRow);

        reconcileFollowingRows(account, accountTermAndPreClosure, transaction);
    }

    /**
     * Re-derives the invested amount and rate history following a transaction being undone (the rare "hard undo" admin
     * action, or just before an adjustment's replacement transaction is recorded separately via
     * {@link #recordPrincipalChangeEvent}). By the time this is called, {@code undoneTransaction} is already marked
     * reversed on the account (core {@code SavingsAccount#undoTransaction} calls {@code transaction.reverse()} before
     * this hook runs), so it is automatically excluded from {@link #computeInvestedAmountAsOf} - no inverse-delta math
     * is needed. Per plan Section 8 step 5, the undone transaction's own rate-history row is left untouched (a reversed
     * transaction's row is simply excluded later by the interest engine joining back to
     * {@code m_savings_account_transaction.is_reversed}); only rows dated after it can now be stale.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void reverseInvestedAmountForUndo(final DynamicDepositAccount account, final SavingsAccountTransaction undoneTransaction) {
        final DepositAccountTermAndPreClosure accountTermAndPreClosure = account.accountTermAndPreClosure();
        if (accountTermAndPreClosure == null) {
            return;
        }
        reconcileFollowingRows(account, accountTermAndPreClosure, undoneTransaction);
    }

    /**
     * The amount invested as of {@code uptoAndIncluding}'s position in chronological order (reusing core's own
     * {@link SavingsAccountTransactionComparator} - transaction date, then created date, then id): deposits less the
     * principal part of withdrawals - see {@link DynamicDepositInvestedAmountCalculator} for why that is not simply
     * deposits less withdrawals.
     */
    public BigDecimal computeInvestedAmountAsOf(final DynamicDepositAccount account, final SavingsAccountTransaction uptoAndIncluding) {
        return DynamicDepositInvestedAmountCalculator.investedAmountAsOf(account.getTransactions(), account.getCurrency(),
                uptoAndIncluding);
    }

    /**
     * Walks every existing rate-history row dated after {@code referenceTransaction} (a backdated insert, or the undo
     * of a transaction that had rows recorded after it) and corrects its invested amount and resolved rate, since a
     * change earlier in the timeline changes what was true for everything after it. Then refreshes the
     * {@code deposit_amount} cache and the account's live nominal rate from whichever non-reversed row is now
     * chronologically last.
     *
     * dynamic_rate_enabled = false accounts never re-resolve past activation (business rule 7): corrected rows simply
     * carry the activation row's rate forward unchanged, only their invested amount changes.
     */
    private void reconcileFollowingRows(final DynamicDepositAccount account, final DepositAccountTermAndPreClosure accountTermAndPreClosure,
            final SavingsAccountTransaction referenceTransaction) {
        final List<DepositAccountDynamicRateHistory> rows = this.rateHistoryRepository
                .findByAccountIdOrderByTransactionDateAscIdAsc(account.getId());
        if (rows.isEmpty()) {
            return;
        }

        final DepositAccountDynamicRateHistory activationRow = rows.get(0);
        for (final DepositAccountDynamicRateHistory row : rows) {
            if (row.transaction().isReversed() || this.transactionComparator.compare(row.transaction(), referenceTransaction) <= 0) {
                continue;
            }
            final BigDecimal correctedInvestedAmount = computeInvestedAmountAsOf(account, row.transaction());
            if (account.isDynamicRateEnabled()) {
                final ResolvedRate rate = this.rateResolutionService.resolve(account.chart(), nominalAnnualInterestRate(account),
                        correctedInvestedAmount, row.transactionDate(), accountTermAndPreClosure.depositPeriod(),
                        accountTermAndPreClosure.depositPeriodFrequencyType());
                row.correct(correctedInvestedAmount, rate.interestRateChartId(), rate.interestRateSlabId(), rate.baseAnnualInterestRate(),
                        rate.annualInterestRate(), rate.source());
            } else {
                row.correct(correctedInvestedAmount, activationRow.interestRateChartId(), activationRow.interestRateSlabId(),
                        activationRow.baseAnnualInterestRate(), activationRow.resolvedAnnualInterestRate(), activationRow.rateSource());
            }
        }

        DepositAccountDynamicRateHistory latestActiveRow = null;
        for (final DepositAccountDynamicRateHistory row : rows) {
            if (row.transaction().isReversed()) {
                continue;
            }
            if (latestActiveRow == null || this.transactionComparator.compare(row.transaction(), latestActiveRow.transaction()) > 0) {
                latestActiveRow = row;
            }
        }
        if (latestActiveRow != null) {
            accountTermAndPreClosure.updateDepositAmount(latestActiveRow.investedAmountAfterTransaction());
            // Business rule 7 / plan Section 8: the interest engine (Phase 3) will replay resolved_annual_interest_rate
            // per interval from this history table. Until that lands, also keep the account's own
            // nominalAnnualInterestRate current so the platform's existing (pre-Phase-3) interest calculation
            // continues to use the latest rate.
            account.updateNominalAnnualInterestRate(latestActiveRow.resolvedAnnualInterestRate());
        }
    }

    /** One rate-history row's stored figures and what they should be. */
    public record RowRecompute(Long rowId, LocalDate transactionDate, String eventType, BigDecimal storedInvested,
            BigDecimal correctedInvested, BigDecimal storedRate, BigDecimal correctedRate) {

        public boolean changed() {
            return storedInvested.compareTo(correctedInvested) != 0 || storedRate.compareTo(correctedRate) != 0;
        }
    }

    /** An account's stored invested amount and rate, and what they should be. */
    public record AccountRecompute(Long accountId, String accountNo, String status, BigDecimal storedDepositAmount,
            BigDecimal correctedDepositAmount, BigDecimal storedNominalRate, BigDecimal correctedNominalRate, List<RowRecompute> rows) {

        public boolean changed() {
            return storedDepositAmount.compareTo(correctedDepositAmount) != 0 || storedNominalRate.compareTo(correctedNominalRate) != 0
                    || rows.stream().anyMatch(RowRecompute::changed);
        }
    }

    /**
     * Re-derives every rate-history row of {@code account} - the invested amount after each transaction, and the rate
     * resolved from it - from the account's transactions, as {@link #computeInvestedAmountAsOf} now defines them; then
     * the {@code deposit_amount} cache and the account's live nominal rate from the latest row. Reads everything from
     * the transactions, so it also repairs anything a backdated transaction left stale.
     *
     * With {@code apply} false nothing is changed and the result is a report of what would change.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public AccountRecompute recompute(final DynamicDepositAccount account, final boolean apply) {
        final DepositAccountTermAndPreClosure term = account.accountTermAndPreClosure();
        final List<DepositAccountDynamicRateHistory> rows = this.rateHistoryRepository
                .findByAccountIdOrderByTransactionDateAscIdAsc(account.getId());
        final BigDecimal storedDepositAmount = zeroIfNull(term == null ? null : term.depositAmount());
        final BigDecimal storedNominalRate = zeroIfNull(account.getNominalAnnualInterestRate());
        if (term == null || rows.isEmpty()) {
            return new AccountRecompute(account.getId(), account.getAccountNumber(), statusOf(account), storedDepositAmount,
                    storedDepositAmount, storedNominalRate, storedNominalRate, List.of());
        }

        final DepositAccountDynamicRateHistory activationRow = rows.get(0);
        final List<RowRecompute> report = new ArrayList<>();
        DepositAccountDynamicRateHistory latest = null;
        BigDecimal latestInvested = storedDepositAmount;
        BigDecimal latestRate = storedNominalRate;
        for (final DepositAccountDynamicRateHistory row : rows) {
            if (row.transaction().isReversed()) {
                continue;
            }
            final BigDecimal correctedInvested = computeInvestedAmountAsOf(account, row.transaction());
            final ResolvedRate rate = rateFor(account, term, activationRow, row, correctedInvested);
            report.add(new RowRecompute(row.getId(), row.transactionDate(), row.eventType().name(),
                    zeroIfNull(row.investedAmountAfterTransaction()), correctedInvested, zeroIfNull(row.resolvedAnnualInterestRate()),
                    rate.annualInterestRate()));
            if (apply) {
                row.correct(correctedInvested, rate.interestRateChartId(), rate.interestRateSlabId(), rate.baseAnnualInterestRate(),
                        rate.annualInterestRate(), rate.source());
            }
            if (latest == null || this.transactionComparator.compare(row.transaction(), latest.transaction()) > 0) {
                latest = row;
                latestInvested = correctedInvested;
                latestRate = rate.annualInterestRate();
            }
        }
        if (apply && latest != null) {
            term.updateDepositAmount(latestInvested);
            account.updateNominalAnnualInterestRate(latestRate);
        }
        return new AccountRecompute(account.getId(), account.getAccountNumber(), statusOf(account), storedDepositAmount, latestInvested,
                storedNominalRate, latestRate, report);
    }

    /**
     * The rate a row should carry: the activation row keeps its own; later rows are resolved from the corrected
     * invested amount.
     */
    private ResolvedRate rateFor(final DynamicDepositAccount account, final DepositAccountTermAndPreClosure term,
            final DepositAccountDynamicRateHistory activationRow, final DepositAccountDynamicRateHistory row,
            final BigDecimal correctedInvested) {
        if (row == activationRow || !account.isDynamicRateEnabled()) {
            return new ResolvedRate(activationRow.resolvedAnnualInterestRate(), activationRow.baseAnnualInterestRate(),
                    activationRow.rateSource(), activationRow.interestRateChartId(), activationRow.interestRateSlabId());
        }
        return this.rateResolutionService.resolve(account.chart(), nominalAnnualInterestRate(account), correctedInvested,
                row.transactionDate(), term.depositPeriod(), term.depositPeriodFrequencyType());
    }

    private static String statusOf(final DynamicDepositAccount account) {
        return account.getStatus() == null ? null : account.getStatus().name();
    }

    private static BigDecimal zeroIfNull(final BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    public ResolvedRate resolveRate(final DynamicDepositAccount account, final DepositAccountTermAndPreClosure accountTermAndPreClosure,
            final BigDecimal investedAmount, final LocalDate asOfDate, final long priorRowCount) {
        final BigDecimal nominalAnnualInterestRate = account.savingsProduct().nominalAnnualInterestRate();
        if (priorRowCount == 0) {
            if (account.isDynamicRateEnabled() || account.getNominalAnnualInterestRate() == null) {
                return this.rateResolutionService.resolve(account.chart(), nominalAnnualInterestRate(account), investedAmount, asOfDate,
                        accountTermAndPreClosure.depositPeriod(), accountTermAndPreClosure.depositPeriodFrequencyType());
            }

            return new ResolvedRate(account.getNominalAnnualInterestRate(), account.getNominalAnnualInterestRate(),
                    DynamicDepositRateSource.ACCOUNT_NOMINAL, null, null);
        }

        if (account.isDynamicRateEnabled()) {
            return this.rateResolutionService.resolve(account.chart(), nominalAnnualInterestRate(account), investedAmount, asOfDate,
                    accountTermAndPreClosure.depositPeriod(), accountTermAndPreClosure.depositPeriodFrequencyType());
        }

        final DepositAccountDynamicRateHistory lastRow = this.rateHistoryRepository
                .findFirstByAccountIdOrderByTransactionDateDescIdDesc(account.getId());
        if (lastRow == null) {
            return this.rateResolutionService.resolve(account.chart(), nominalAnnualInterestRate, investedAmount, asOfDate,
                    accountTermAndPreClosure.depositPeriod(), accountTermAndPreClosure.depositPeriodFrequencyType());
        }
        // dynamic_rate_enabled = false: carry the last resolved rate forward unchanged (business rule 7).
        return new ResolvedRate(lastRow.resolvedAnnualInterestRate(), lastRow.resolvedAnnualInterestRate(), lastRow.rateSource(),
                lastRow.interestRateChartId(), lastRow.interestRateSlabId());
    }

    private BigDecimal nominalAnnualInterestRate(final DynamicDepositAccount account) {
        return account.getNominalAnnualInterestRate() == null ? account.savingsProduct().nominalAnnualInterestRate()
                : account.getNominalAnnualInterestRate();
    }
}
