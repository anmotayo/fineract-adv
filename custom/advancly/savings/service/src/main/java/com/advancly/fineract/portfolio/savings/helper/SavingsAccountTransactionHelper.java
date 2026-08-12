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
package com.advancly.fineract.portfolio.savings.helper;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.apache.fineract.organisation.monetary.domain.MonetaryCurrency;
import org.apache.fineract.organisation.monetary.domain.Money;
import org.apache.fineract.portfolio.savings.domain.SavingsAccount;
import org.apache.fineract.portfolio.savings.domain.SavingsAccountSummary;
import org.apache.fineract.portfolio.savings.domain.SavingsAccountTransaction;
import org.apache.fineract.portfolio.savings.domain.SavingsAccountTransactionSummaryWrapper;
import org.apache.fineract.portfolio.savings.exception.InsufficientAccountBalanceException;
import org.springframework.stereotype.Component;

@SuppressWarnings("SpringJavaInjectionPointsAutowiringInspection")
@Component
@RequiredArgsConstructor
public class SavingsAccountTransactionHelper {

    private final SavingsAccountTransactionSummaryWrapper summaryWrapper;

    /**
     * O(1) — Set running balance for a transaction appended at the end of the timeline.
     */
    public void setRunningBalanceForAppendPath(SavingsAccountTransaction transaction, Money lastRunningBalance, MonetaryCurrency currency) {
        Money transactionAmount = transaction.getAmount(currency);
        Money newBalance;
        if (transaction.isCredit()) {
            newBalance = lastRunningBalance.plus(transactionAmount);
        } else {
            newBalance = lastRunningBalance.minus(transactionAmount);
        }
        transaction.setRunningBalance(newBalance);
    }

    /**
     * O(1) — Update the previous last transaction's balance end date and cumulative balance fields when appending a new
     * transaction. The previous transaction's balance period is closed at (newTransactionDate - 1).
     */
    public void updatePreviousTransactionBalanceEndDate(SavingsAccountTransaction previousTransaction, LocalDate newTransactionDate,
            MonetaryCurrency currency) {
        if (previousTransaction != null) {
            LocalDate endDate = newTransactionDate.minusDays(1);
            previousTransaction.updateCumulativeBalanceAndDates(currency, endDate);
        }
    }

    /**
     * O(1) — Incremental summary update for a single new transaction. Delegates to the existing
     * SavingsAccountSummary.updateSummaryWithPivotConfig switch logic.
     */
    public void updateSummaryIncremental(SavingsAccount account, SavingsAccountTransaction transaction, MonetaryCurrency currency) {
        account.getSummary().updateSummaryWithPivotConfig(currency, summaryWrapper, transaction,
                account.getSavingsAccountTransactionsWithPivotConfig());
    }

    /**
     * O(1) — Validate withdrawal doesn't exceed available balance for append path.
     */
    public void validateBalanceForAppendPath(SavingsAccount account, BigDecimal withdrawalAmount, MonetaryCurrency currency) {
        Money withdrawal = Money.of(currency, withdrawalAmount);
        Money availableBalance = Money.of(currency, account.getWithdrawableBalance());

        if (availableBalance.minus(withdrawal).isLessThanZero()) {
            throw new InsufficientAccountBalanceException("transactionAmount", account.getSummary().getAccountBalance(), null,
                    withdrawalAmount);
        }
    }

    /**
     * O(k) — Recalculate running balances for a list of transactions from a given opening balance. Transactions must be
     * pre-sorted by date.
     */
    public void recalculateDailyBalancesFromDate(List<SavingsAccountTransaction> sortedTransactions, Money openingBalance,
            MonetaryCurrency currency) {
        Money runningBalance = openingBalance;
        for (SavingsAccountTransaction transaction : sortedTransactions) {
            if (transaction.isReversed() || transaction.isReversalTransaction()) {
                transaction.zeroBalanceFields();
                continue;
            }
            if (transaction.isCredit() || transaction.isAmountRelease()) {
                runningBalance = runningBalance.plus(transaction.getAmount(currency));
            } else if (transaction.isDebit() || transaction.isAmountOnHold()) {
                runningBalance = runningBalance.minus(transaction.getAmount(currency));
            }
            transaction.setRunningBalance(runningBalance);
        }
    }

    /**
     * O(k) — Validate balance never goes negative from a set of pre-recalculated transactions.
     */
    public void validateBalanceDoesNotBecomeNegative(SavingsAccount account, List<SavingsAccountTransaction> sortedTransactions,
            Money openingBalance, MonetaryCurrency currency) {
        Money runningBalance = openingBalance;
        Money minRequired = account.minRequiredBalanceDerived(currency);

        for (SavingsAccountTransaction transaction : sortedTransactions) {
            if (transaction.isReversed() || transaction.isReversalTransaction()) {
                continue;
            }
            if (transaction.isCredit()) {
                runningBalance = runningBalance.plus(transaction.getAmount(currency));
            } else if (transaction.isDebit()) {
                runningBalance = runningBalance.minus(transaction.getAmount(currency));
            } else {
                continue;
            }

            if (!account.isOverdraft() && transaction.canProcessBalanceCheck()) {
                if (runningBalance.minus(minRequired).isLessThanZero()) {
                    throw new InsufficientAccountBalanceException("transactionAmount", account.getSummary().getAccountBalance(), null,
                            transaction.getAmount());
                }
            }
        }
    }

    /**
     * O(n) single-pass — Calculate all 11 summary totals, running balance, and last interest posting date in one
     * iteration. Replaces the 12-pass updateSummary() + updateRunningBalanceAndPivotDate() for the insert path.
     */
    public void calculateAndUpdateSummaryInSinglePass(SavingsAccount account, List<SavingsAccountTransaction> transactions,
            MonetaryCurrency currency) {
        Money totalDeposits = Money.zero(currency);
        Money totalWithdrawals = Money.zero(currency);
        Money totalInterestPosted = Money.zero(currency);
        Money totalWithdrawalFees = Money.zero(currency);
        Money totalAnnualFees = Money.zero(currency);
        Money totalFeeCharge = Money.zero(currency);
        Money totalFeeChargesWaived = Money.zero(currency);
        Money totalPenaltyCharge = Money.zero(currency);
        Money totalPenaltyChargesWaived = Money.zero(currency);
        Money totalOverdraftInterest = Money.zero(currency);
        Money totalWithholdTax = Money.zero(currency);
        LocalDate lastInterestPostingDate = null;

        for (SavingsAccountTransaction txn : transactions) {
            if (txn.isReversalTransaction()) {
                continue;
            }
            Money amount = txn.getAmount(currency);

            if ((txn.isDepositAndNotReversed() || txn.isDividendPayoutAndNotReversed())) {
                totalDeposits = totalDeposits.plus(amount);
            }
            if (txn.isWithdrawal() && txn.isNotReversed()) {
                totalWithdrawals = totalWithdrawals.plus(amount);
            }
            if (txn.isInterestPostingAndNotReversed() && txn.isNotReversed()) {
                totalInterestPosted = totalInterestPosted.plus(amount);
                lastInterestPostingDate = txn.getTransactionDate();
            }
            if (txn.isWithdrawalFeeAndNotReversed() && txn.isNotReversed()) {
                totalWithdrawalFees = totalWithdrawalFees.plus(amount);
            }
            if (txn.isAnnualFeeAndNotReversed() && txn.isNotReversed()) {
                totalAnnualFees = totalAnnualFees.plus(amount);
            }
            if (txn.isFeeChargeAndNotReversed()) {
                totalFeeCharge = totalFeeCharge.plus(amount);
            }
            if (txn.isWaiveFeeChargeAndNotReversed()) {
                totalFeeChargesWaived = totalFeeChargesWaived.plus(amount);
            }
            if (txn.isPenaltyChargeAndNotReversed()) {
                totalPenaltyCharge = totalPenaltyCharge.plus(amount);
            }
            if (txn.isWaivePenaltyChargeAndNotReversed()) {
                totalPenaltyChargesWaived = totalPenaltyChargesWaived.plus(amount);
            }
            if (txn.isOverdraftInterestAndNotReversed()) {
                totalOverdraftInterest = totalOverdraftInterest.plus(amount);
            }
            if (txn.isWithHoldTaxAndNotReversed()) {
                totalWithholdTax = totalWithholdTax.plus(amount);
            }
        }

        SavingsAccountSummary summary = account.getSummary();
        summary.setTotalDeposits(totalDeposits.getAmountDefaultedToNullIfZero());
        summary.setTotalWithdrawals(totalWithdrawals.getAmountDefaultedToNullIfZero());
        summary.setTotalInterestPosted(totalInterestPosted.getAmountDefaultedToNullIfZero());
        summary.setTotalWithdrawalFees(totalWithdrawalFees.getAmountDefaultedToNullIfZero());
        summary.setTotalAnnualFees(totalAnnualFees.getAmountDefaultedToNullIfZero());
        summary.setTotalFeeCharge(totalFeeCharge.getAmountDefaultedToNullIfZero());
        summary.setTotalPenaltyCharge(totalPenaltyCharge.getAmountDefaultedToNullIfZero());
        summary.setTotalOverdraftInterestDerived(totalOverdraftInterest.getAmountDefaultedToNullIfZero());
        summary.setTotalWithholdTax(totalWithholdTax.getAmountDefaultedToNullIfZero());
        if (lastInterestPostingDate != null) {
            summary.setInterestPostedTillDate(lastInterestPostingDate);
        }

        BigDecimal accountBalance = totalDeposits.plus(totalInterestPosted).minus(totalWithdrawals).minus(totalWithdrawalFees)
                .minus(totalAnnualFees).minus(totalFeeCharge).minus(totalPenaltyCharge).minus(totalOverdraftInterest)
                .minus(totalWithholdTax).getAmount();
        summary.setAccountBalance(accountBalance);
    }

    /**
     * Check if the transaction date falls before the last interest posting period. Uses a pre-loaded list of interest
     * and overdraft transactions.
     */
    public boolean isBeforeLastPostingPeriod(LocalDate transactionDate, List<SavingsAccountTransaction> interestAndOverdraftTransactions) {
        for (SavingsAccountTransaction transaction : interestAndOverdraftTransactions) {
            if ((transaction.isInterestPostingAndNotReversed() || transaction.isOverdraftInterestAndNotReversed())
                    && transaction.isAfter(transactionDate) && !transaction.isReversalTransaction()) {
                return true;
            }
        }
        return false;
    }
}
