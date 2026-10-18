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
import java.util.List;
import org.apache.fineract.organisation.monetary.domain.MonetaryCurrency;
import org.apache.fineract.portfolio.savings.domain.SavingsAccountTransaction;
import org.apache.fineract.portfolio.savings.domain.SavingsAccountTransactionComparator;

/**
 * The amount invested in a Dynamic Deposit: the principal still in the account.
 *
 * It is deposits less the PRINCIPAL part of withdrawals. A withdrawal takes interest first - the interest posted, less
 * the tax withheld on it and any interest forfeited, that has not already been withdrawn - and only what is left of it
 * is principal. This is the same interest-first rule the interest-withdrawal ledger uses to label a withdrawal, but
 * with the interest net of tax, so a payout of interest less tax leaves nothing behind to be mislabelled later.
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
            if (transaction.isReversed() || transaction.isReversalTransaction()) {
                continue;
            }
            if (COMPARATOR.compare(transaction, uptoAndIncluding) > 0) {
                continue;
            }
            chronological.add(transaction);
        }
        chronological.sort(COMPARATOR);

        BigDecimal principal = BigDecimal.ZERO;
        BigDecimal interestAvailable = BigDecimal.ZERO;
        for (final SavingsAccountTransaction transaction : chronological) {
            final BigDecimal amount = transaction.getAmount(currency).getAmount();
            if (transaction.isDeposit()) {
                principal = principal.add(amount);
            } else if (transaction.isInterestPostingAndNotReversed()) {
                interestAvailable = interestAvailable.add(amount);
            } else if (transaction.isWithHoldTaxAndNotReversed() || transaction.isInterestChargeAndNotReversed()) {
                interestAvailable = interestAvailable.subtract(amount);
            } else if (transaction.isWithdrawal()) {
                final BigDecimal fromInterest = amount.min(interestAvailable.max(BigDecimal.ZERO));
                interestAvailable = interestAvailable.subtract(fromInterest);
                principal = principal.subtract(amount.subtract(fromInterest));
            }
        }
        return principal;
    }
}
