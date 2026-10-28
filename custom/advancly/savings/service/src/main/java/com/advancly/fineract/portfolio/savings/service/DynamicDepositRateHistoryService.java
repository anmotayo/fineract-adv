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
import java.util.List;
import java.util.Map;
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
 * matched). {@link #recompute} re-derives those.
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

        recompute(account);
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
        recompute(account);
    }

    /**
     * The amount invested as of {@code uptoAndIncluding}'s position in chronological order (reusing core's own
     * {@link SavingsAccountTransactionComparator} - transaction date, then created date, then id): deposits less the
     * principal part of withdrawals - see {@link DynamicDepositInvestedAmountCalculator} for why that is not simply
     * deposits less withdrawals.
     */
    public BigDecimal computeInvestedAmountAsOf(final DynamicDepositAccount account, final SavingsAccountTransaction uptoAndIncluding) {
        return DynamicDepositInvestedAmountCalculator.investedAmountAsOf(account.getTransactions(), account.getCurrency(),
                account.interestTransferTransactionIds(), uptoAndIncluding);
    }

    /**
     * Re-derives every rate-history row of {@code account} - the invested amount after each transaction, and the rate
     * resolved from it - from the account's transactions, then the {@code deposit_amount} cache and the account's live
     * nominal rate from the latest row, writing only what differs. An account the closing payout has emptied keeps its
     * last non-zero {@code deposit_amount}. It runs on every transaction that changes the principal (and on an undo),
     * so none of these figures can go stale: a backdated transaction, an interest payout, a closing withdrawal or the
     * correction of an earlier mistake all leave them right.
     *
     * @return whether anything was changed
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean recompute(final DynamicDepositAccount account) {
        final DepositAccountTermAndPreClosure term = account.accountTermAndPreClosure();
        if (term == null) {
            return false;
        }
        final List<DepositAccountDynamicRateHistory> rows = this.rateHistoryRepository
                .findByAccountIdOrderByTransactionDateAscIdAsc(account.getId());
        if (rows.isEmpty()) {
            return false;
        }

        final Map<SavingsAccountTransaction, BigDecimal> investedAfter = DynamicDepositInvestedAmountCalculator
                .investedAmountAfterEach(account.getTransactions(), account.getCurrency(), account.interestTransferTransactionIds());
        final DepositAccountDynamicRateHistory activationRow = rows.get(0);
        boolean changed = false;
        DepositAccountDynamicRateHistory latest = null;
        BigDecimal latestInvested = null;
        BigDecimal latestRate = null;
        DepositAccountDynamicRateHistory lastPositive = null;
        BigDecimal lastPositiveInvested = null;
        for (final DepositAccountDynamicRateHistory row : rows) {
            if (row.transaction().isReversed()) {
                continue;
            }
            final BigDecimal invested = investedAfter.containsKey(row.transaction()) ? investedAfter.get(row.transaction())
                    : computeInvestedAmountAsOf(account, row.transaction());
            if (invested != null && invested.signum() > 0
                    && (lastPositive == null || this.transactionComparator.compare(row.transaction(), lastPositive.transaction()) > 0)) {
                lastPositive = row;
                lastPositiveInvested = invested;
            }
            final ResolvedRate rate = rateFor(account, term, activationRow, row, invested);
            if (differs(row, invested, rate)) {
                row.correct(invested, rate.interestRateChartId(), rate.interestRateSlabId(), rate.baseAnnualInterestRate(),
                        rate.annualInterestRate(), rate.source());
                changed = true;
            }
            if (latest == null || this.transactionComparator.compare(row.transaction(), latest.transaction()) > 0) {
                latest = row;
                latestInvested = invested;
                latestRate = rate.annualInterestRate();
            }
        }
        if (latest != null) {
            // The closing payout empties the account, which would take the deposit amount to zero with it. The deposit
            // amount is what the account was invested with - what a principal reinvestment carries and what a caller
            // reads after the account has closed - so it is the last positive invested amount whenever the latest
            // figure is zero or negative. A negative one is a closing payout counted as principal in full (read before
            // its running balance was set), and a figure left that way by an earlier run is repaired the same way.
            final BigDecimal depositAmount = latestInvested != null && latestInvested.signum() <= 0 && lastPositiveInvested != null
                    ? lastPositiveInvested
                    : latestInvested;
            if (compare(term.depositAmount(), depositAmount) != 0) {
                term.updateDepositAmount(depositAmount);
                changed = true;
            }
            // Business rule 7 / plan Section 8: keep the account's own nominal rate equal to the latest row's resolved
            // rate.
            if (compare(account.getNominalAnnualInterestRate(), latestRate) != 0) {
                account.updateNominalAnnualInterestRate(latestRate);
                changed = true;
            }
        }
        return changed;
    }

    /**
     * The rate a row should carry: the activation row keeps its own; later rows are resolved from the invested amount.
     */
    private ResolvedRate rateFor(final DynamicDepositAccount account, final DepositAccountTermAndPreClosure term,
            final DepositAccountDynamicRateHistory activationRow, final DepositAccountDynamicRateHistory row, final BigDecimal invested) {
        if (row == activationRow || !account.isDynamicRateEnabled()) {
            return new ResolvedRate(activationRow.resolvedAnnualInterestRate(), activationRow.baseAnnualInterestRate(),
                    activationRow.rateSource(), activationRow.interestRateChartId(), activationRow.interestRateSlabId());
        }
        return this.rateResolutionService.resolve(account.chart(), nominalAnnualInterestRate(account), invested, row.transactionDate(),
                term.depositPeriod(), term.depositPeriodFrequencyType());
    }

    private static boolean differs(final DepositAccountDynamicRateHistory row, final BigDecimal invested, final ResolvedRate rate) {
        return compare(row.investedAmountAfterTransaction(), invested) != 0
                || compare(row.resolvedAnnualInterestRate(), rate.annualInterestRate()) != 0
                || compare(row.baseAnnualInterestRate(), rate.baseAnnualInterestRate()) != 0
                || !java.util.Objects.equals(row.interestRateChartId(), rate.interestRateChartId())
                || !java.util.Objects.equals(row.interestRateSlabId(), rate.interestRateSlabId()) || row.rateSource() != rate.source();
    }

    private static int compare(final BigDecimal a, final BigDecimal b) {
        return (a == null ? BigDecimal.ZERO : a).compareTo(b == null ? BigDecimal.ZERO : b);
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
