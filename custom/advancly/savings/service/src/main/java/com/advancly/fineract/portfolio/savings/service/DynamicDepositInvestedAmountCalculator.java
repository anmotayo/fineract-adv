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

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.fineract.organisation.monetary.domain.MonetaryCurrency;
import org.apache.fineract.organisation.monetary.domain.Money;
import org.apache.fineract.portfolio.savings.domain.SavingsAccountTransaction;
import org.apache.fineract.portfolio.savings.domain.SavingsAccountTransactionComparator;

/**
 * The amount invested in a Dynamic Deposit: the principal still in the account.
 *
 * It is deposits less the PRINCIPAL part of withdrawals. Which part of a withdrawal is principal is not guessed from
 * the amounts; it is read off what the withdrawal was:
 * <ul>
 * <li>an interest transfer (the withdrawal that sent interest to the linked account, as listed in
 * {@code interestTransferTransactionIds}) takes from the interest available - the interest posted, less the tax
 * withheld on it and any interest forfeited, that has not already been withdrawn - and never from principal;</li>
 * <li>a closing payout - the withdrawal that leaves nothing in the account, once the early-withdrawal charge posted
 * straight after it is counted - sweeps whatever interest is left first, and only the rest is principal;</li>
 * <li>any other withdrawal is principal withdrawn, in full, however much interest the account is holding.</li>
 * </ul>
 *
 * Subtracting every withdrawal in full drove a fully closed account to a negative amount, and treating every withdrawal
 * as interest-first understated the principal withdrawn whenever the account held interest at the time.
 *
 * Pure: it reads only the transactions it is given (and their running balances), so it is the same on a managed
 * account, a detached one carrying a simulated transaction, and one mid-closure that has not been flushed. A
 * transaction without a running balance yet - the closing payout is read before core has set it - has its balance
 * worked out from the transactions instead, so it is still recognised as the payout that empties the account.
 */
public final class DynamicDepositInvestedAmountCalculator {

    private static final SavingsAccountTransactionComparator COMPARATOR = new SavingsAccountTransactionComparator();

    private DynamicDepositInvestedAmountCalculator() {}

    /**
     * @param interestTransferTransactionIds
     *            the withdrawals that paid interest out to the linked account
     * @param uptoAndIncluding
     *            the last transaction, in chronological order, that counts; later ones are ignored
     */
    public static BigDecimal investedAmountAsOf(final Collection<SavingsAccountTransaction> transactions, final MonetaryCurrency currency,
            final Collection<Long> interestTransferTransactionIds, final SavingsAccountTransaction uptoAndIncluding) {
        final List<SavingsAccountTransaction> chronological = new ArrayList<>();
        for (final SavingsAccountTransaction transaction : transactions) {
            if (isCounted(transaction)) {
                chronological.add(transaction);
            }
        }
        chronological.sort(COMPARATOR);
        final Position position = new Position(currency, interestTransferTransactionIds);
        // Applied over every transaction and stopped after the one asked about, not over a list cut at it: a closing
        // payout has to see the charge posted right after it to be recognised.
        for (int i = 0; i < chronological.size() && COMPARATOR.compare(chronological.get(i), uptoAndIncluding) <= 0; i++) {
            position.apply(chronological, i);
        }
        return position.principal;
    }

    /**
     * The amount invested after each transaction, in one pass over the transactions - for re-deriving every
     * rate-history row of an account without recalculating from the start for each of them. Keyed by transaction
     * instance.
     */
    public static Map<SavingsAccountTransaction, BigDecimal> investedAmountAfterEach(
            final Collection<SavingsAccountTransaction> transactions, final MonetaryCurrency currency,
            final Collection<Long> interestTransferTransactionIds) {
        final List<SavingsAccountTransaction> chronological = new ArrayList<>();
        for (final SavingsAccountTransaction transaction : transactions) {
            if (isCounted(transaction)) {
                chronological.add(transaction);
            }
        }
        chronological.sort(COMPARATOR);
        final Map<SavingsAccountTransaction, BigDecimal> investedAfter = new IdentityHashMap<>();
        final Position position = new Position(currency, interestTransferTransactionIds);
        for (int i = 0; i < chronological.size(); i++) {
            position.apply(chronological, i);
            investedAfter.put(chronological.get(i), position.principal);
        }
        return investedAfter;
    }

    private static boolean isCounted(final SavingsAccountTransaction transaction) {
        return !transaction.isReversed() && !transaction.isReversalTransaction();
    }

    /**
     * The principal still invested and the interest still available to be withdrawn, as of the transactions applied so
     * far.
     */
    private static final class Position {

        private final MonetaryCurrency currency;
        private final Set<Long> interestTransferTransactionIds;
        private BigDecimal principal = BigDecimal.ZERO;
        private BigDecimal interestAvailable = BigDecimal.ZERO;
        /**
         * The account balance worked out from the transactions applied so far, for a transaction with no running
         * balance.
         */
        private BigDecimal balance = BigDecimal.ZERO;

        Position(final MonetaryCurrency currency, final Collection<Long> interestTransferTransactionIds) {
            this.currency = currency;
            this.interestTransferTransactionIds = interestTransferTransactionIds == null ? Set.of()
                    : new HashSet<>(interestTransferTransactionIds);
        }

        void apply(final List<SavingsAccountTransaction> chronological, final int index) {
            final SavingsAccountTransaction transaction = chronological.get(index);
            final BigDecimal amount = transaction.getAmount(this.currency).getAmount();
            if (transaction.isDeposit() || transaction.isInterestPostingAndNotReversed()) {
                this.balance = this.balance.add(amount);
            } else if (transaction.isWithHoldTaxAndNotReversed() || transaction.isInterestChargeAndNotReversed()
                    || transaction.isWithdrawal()) {
                this.balance = this.balance.subtract(amount);
            }
            if (transaction.isDeposit()) {
                this.principal = this.principal.add(amount);
            } else if (transaction.isInterestPostingAndNotReversed()) {
                this.interestAvailable = this.interestAvailable.add(amount);
            } else if (transaction.isWithHoldTaxAndNotReversed() || transaction.isInterestChargeAndNotReversed()) {
                this.interestAvailable = this.interestAvailable.subtract(amount);
            } else if (transaction.isWithdrawal()) {
                if (transaction.getId() != null && this.interestTransferTransactionIds.contains(transaction.getId())) {
                    this.interestAvailable = this.interestAvailable.subtract(amount);
                } else {
                    final BigDecimal chargeAfter = chargesPostedRightAfter(chronological, index);
                    if (leavesNothing(transaction, chargeAfter)) {
                        // The charge is taken out of interest, so it is not there for the payout to sweep.
                        final BigDecimal interestToSweep = this.interestAvailable.subtract(chargeAfter).max(BigDecimal.ZERO);
                        final BigDecimal fromInterest = amount.min(interestToSweep);
                        this.interestAvailable = this.interestAvailable.subtract(fromInterest);
                        this.principal = this.principal.subtract(amount.subtract(fromInterest));
                    } else {
                        this.principal = this.principal.subtract(amount);
                    }
                }
            }
        }

        /**
         * The early-withdrawal charge is posted after the payout it belongs to, so the payout's own running balance is
         * the charge and it is the charge that brings the account to zero.
         */
        private BigDecimal chargesPostedRightAfter(final List<SavingsAccountTransaction> chronological, final int withdrawalIndex) {
            BigDecimal charges = BigDecimal.ZERO;
            for (int i = withdrawalIndex + 1; i < chronological.size() && chronological.get(i).isInterestChargeAndNotReversed(); i++) {
                charges = charges.add(chronological.get(i).getAmount(this.currency).getAmount());
            }
            return charges;
        }

        private boolean leavesNothing(final SavingsAccountTransaction withdrawal, final BigDecimal chargesRightAfter) {
            final BigDecimal balanceAfter = withdrawal.getRunningBalance() == null ? this.balance
                    : withdrawal.getRunningBalance(this.currency).getAmount();
            final BigDecimal left = balanceAfter.subtract(chargesRightAfter);
            return Money.of(this.currency, left).getAmount().signum() <= 0;
        }
    }
}
