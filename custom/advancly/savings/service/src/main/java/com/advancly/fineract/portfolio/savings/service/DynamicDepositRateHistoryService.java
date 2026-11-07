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
 * Records Dynamic Deposit principal changes in the rate history. Invested amounts are always recomputed from the
 * transactions, never incrementally, because a backdated transaction can make later rows' amount and slab stale.
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

    /** The undone transaction is already reversed, so a recompute excludes it; its own history row is left as is. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void reverseInvestedAmountForUndo(final DynamicDepositAccount account, final SavingsAccountTransaction undoneTransaction) {
        final DepositAccountTermAndPreClosure accountTermAndPreClosure = account.accountTermAndPreClosure();
        if (accountTermAndPreClosure == null) {
            return;
        }
        recompute(account);
    }

    public BigDecimal computeInvestedAmountAsOf(final DynamicDepositAccount account, final SavingsAccountTransaction uptoAndIncluding) {
        return DynamicDepositInvestedAmountCalculator.investedAmountAsOf(account.getTransactions(), account.getCurrency(),
                account.interestTransferTransactionIds(), uptoAndIncluding);
    }

    /** Re-derives history rows, the {@code deposit_amount} cache and nominal rate; returns whether anything changed. */
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
            // Keep the last positive invested amount once the closing payout empties the account.
            final BigDecimal depositAmount = latestInvested != null && latestInvested.signum() <= 0 && lastPositiveInvested != null
                    ? lastPositiveInvested
                    : latestInvested;
            if (compare(term.depositAmount(), depositAmount) != 0) {
                term.updateDepositAmount(depositAmount);
                changed = true;
            }
            if (compare(account.getNominalAnnualInterestRate(), latestRate) != 0) {
                account.updateNominalAnnualInterestRate(latestRate);
                changed = true;
            }
        }
        return changed;
    }

    /** The activation row keeps its own rate; later rows are resolved from the invested amount. */
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
        // Dynamic rate disabled: carry the last resolved rate forward.
        return new ResolvedRate(lastRow.resolvedAnnualInterestRate(), lastRow.resolvedAnnualInterestRate(), lastRow.rateSource(),
                lastRow.interestRateChartId(), lastRow.interestRateSlabId());
    }

    private BigDecimal nominalAnnualInterestRate(final DynamicDepositAccount account) {
        return account.getNominalAnnualInterestRate() == null ? account.savingsProduct().nominalAnnualInterestRate()
                : account.getNominalAnnualInterestRate();
    }
}
