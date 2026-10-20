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
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import org.apache.fineract.organisation.monetary.domain.MonetaryCurrency;
import org.apache.fineract.portfolio.savings.domain.SavingsAccountTransaction;
import org.apache.fineract.portfolio.savings.domain.SavingsAccountTransactionComparator;

/**
 * The amount invested in a Dynamic Deposit: the principal still in the account.
 *
 * It is deposits less the PRINCIPAL part of withdrawals. A withdrawal takes interest first - the interest posted, less
 * the tax withheld on it and any interest forfeited, that has not already been withdrawn - and only what is left of it
 * is principal. This is applied with the interest net of tax, so a payout of interest less tax leaves nothing behind to
 * be mislabelled later.
 *
 * Subtracting every withdrawal in full, as this used to, drove a fully closed account to a negative amount, and
 * understated the principal whenever interest had been paid out (for instance each time
 * {@code transferInterestToSavings} sent it to the linked account) - which also pushed the account into a lower rate
 * slab than its principal earns.
 *
 * Pure: it reads only the transactions it is given, so it is the same on a managed account, a detached one carrying a
 * simulated transaction, and one mid-closure that has not been flushed.
 */
public final class DynamicDepositInvestedAmountCalculator {

    private static final SavingsAccountTransactionComparator COMPARATOR = new SavingsAccountTransactionComparator();

    private DynamicDepositInvestedAmountCalculator() {}

    /**
     * @param uptoAndIncluding
     *            the last transaction, in chronological order, that counts; later ones are ignored
     */
    public static BigDecimal investedAmountAsOf(final Collection<SavingsAccountTransaction> transactions, final MonetaryCurrency currency,
            final SavingsAccountTransaction uptoAndIncluding) {
        final List<SavingsAccountTransaction> chronological = new ArrayList<>();
        for (final SavingsAccountTransaction transaction : transactions) {
            if (isCounted(transaction) && COMPARATOR.compare(transaction, uptoAndIncluding) <= 0) {
                chronological.add(transaction);
            }
        }
        chronological.sort(COMPARATOR);
        final Position position = new Position();
        for (final SavingsAccountTransaction transaction : chronological) {
            position.apply(transaction, currency);
        }
        return position.principal;
    }

    /**
     * The amount invested after each transaction, in one pass over the transactions - for re-deriving every
     * rate-history row of an account without recalculating from the start for each of them. Keyed by transaction
     * instance.
     */
    public static Map<SavingsAccountTransaction, BigDecimal> investedAmountAfterEach(
            final Collection<SavingsAccountTransaction> transactions, final MonetaryCurrency currency) {
        final List<SavingsAccountTransaction> chronological = new ArrayList<>();
        for (final SavingsAccountTransaction transaction : transactions) {
            if (isCounted(transaction)) {
                chronological.add(transaction);
            }
        }
        chronological.sort(COMPARATOR);
        final Map<SavingsAccountTransaction, BigDecimal> investedAfter = new IdentityHashMap<>();
        final Position position = new Position();
        for (final SavingsAccountTransaction transaction : chronological) {
            position.apply(transaction, currency);
            investedAfter.put(transaction, position.principal);
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

        private BigDecimal principal = BigDecimal.ZERO;
        private BigDecimal interestAvailable = BigDecimal.ZERO;

        void apply(final SavingsAccountTransaction transaction, final MonetaryCurrency currency) {
            final BigDecimal amount = transaction.getAmount(currency).getAmount();
            if (transaction.isDeposit()) {
                this.principal = this.principal.add(amount);
            } else if (transaction.isInterestPostingAndNotReversed()) {
                this.interestAvailable = this.interestAvailable.add(amount);
            } else if (transaction.isWithHoldTaxAndNotReversed() || transaction.isInterestChargeAndNotReversed()) {
                this.interestAvailable = this.interestAvailable.subtract(amount);
            } else if (transaction.isWithdrawal()) {
                final BigDecimal fromInterest = amount.min(this.interestAvailable.max(BigDecimal.ZERO));
                this.interestAvailable = this.interestAvailable.subtract(fromInterest);
                this.principal = this.principal.subtract(amount.subtract(fromInterest));
            }
        }
    }
}
