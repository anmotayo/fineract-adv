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
 * The principal still invested in a Dynamic Deposit: deposits less the principal part of withdrawals. Interest
 * transfers come only from interest, a closing payout sweeps remaining interest first, and any other withdrawal is
 * principal in full. Pure: reads only the given transactions, deriving a missing running balance from them.
 */
public final class DynamicDepositInvestedAmountCalculator {

    private static final SavingsAccountTransactionComparator COMPARATOR = new SavingsAccountTransactionComparator();

    private DynamicDepositInvestedAmountCalculator() {}

    /** {@code interestTransferTransactionIds}: the withdrawals that paid interest out to the linked account. */
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
        // Iterate everything and stop after the target: a closing payout must see the charge posted right after it.
        for (int i = 0; i < chronological.size() && COMPARATOR.compare(chronological.get(i), uptoAndIncluding) <= 0; i++) {
            position.apply(chronological, i);
        }
        return position.principal;
    }

    /** Invested amount after each transaction in one pass, keyed by transaction instance. */
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

    private static final class Position {

        private final MonetaryCurrency currency;
        private final Set<Long> interestTransferTransactionIds;
        private BigDecimal principal = BigDecimal.ZERO;
        private BigDecimal interestAvailable = BigDecimal.ZERO;
        /** Derived balance, for transactions with no running balance yet. */
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

        /** The charge posts after its payout, so it is the charge (not the payout) that brings the balance to zero. */
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
