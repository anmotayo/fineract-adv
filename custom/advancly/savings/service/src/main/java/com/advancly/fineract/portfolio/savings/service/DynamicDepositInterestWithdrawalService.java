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

import com.advancly.fineract.portfolio.savings.domain.DepositAccountInterestWithdrawal;
import com.advancly.fineract.portfolio.savings.domain.DepositAccountInterestWithdrawalRepository;
import com.advancly.fineract.portfolio.savings.domain.DynamicDepositAccount;
import com.advancly.fineract.portfolio.savings.domain.SavingsAccountInterestChargeRepository;
import com.advancly.fineract.portfolio.savings.domain.SavingsProductEarlyWithdrawalCharge;
import com.advancly.fineract.portfolio.savings.domain.SavingsProductEarlyWithdrawalChargeRepository;
import java.math.BigDecimal;
import java.util.List;
import org.apache.fineract.organisation.monetary.domain.MonetaryCurrency;
import org.apache.fineract.portfolio.savings.domain.SavingsAccountTransaction;
import org.apache.fineract.portfolio.savings.domain.SavingsAccountTransactionComparator;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Records how much of a withdrawal from a Dynamic Deposit account represents previously-posted, not-yet-withdrawn
 * interest (implementation plan Section 10 step 11) - purely for the interest-summary API's {@code interestWithdrawn}
 * figure (Section 9). The withdrawal transaction itself already correctly reduces the account balance through the
 * normal transaction ledger and {@code SavingsAccount}'s own daily-balance recalculation - nothing here changes money
 * movement, only which existing interest-posting transactions this withdrawal is attributed against, oldest first.
 */
@Component
public class DynamicDepositInterestWithdrawalService {

    private final DepositAccountInterestWithdrawalRepository repository;
    private final SavingsAccountInterestChargeRepository interestChargeRepository;
    private final SavingsProductEarlyWithdrawalChargeRepository productEarlyWithdrawalChargeRepository;
    private final SavingsAccountTransactionComparator transactionComparator = new SavingsAccountTransactionComparator();

    public DynamicDepositInterestWithdrawalService(final DepositAccountInterestWithdrawalRepository repository,
            final SavingsAccountInterestChargeRepository interestChargeRepository,
            final SavingsProductEarlyWithdrawalChargeRepository productEarlyWithdrawalChargeRepository) {
        this.repository = repository;
        this.interestChargeRepository = interestChargeRepository;
        this.productEarlyWithdrawalChargeRepository = productEarlyWithdrawalChargeRepository;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void recordIfApplicable(final DynamicDepositAccount account, final SavingsAccountTransaction withdrawalTransaction) {
        final MonetaryCurrency currency = account.getCurrency();
        BigDecimal remainingToAllocate = withdrawalTransaction.getAmount(currency).getAmount();
        if (remainingToAllocate.compareTo(BigDecimal.ZERO) <= 0) {
            return;
        }

        // Interest this account has already had taken back by cumulative early-withdrawal forfeiture is no longer in
        // the balance, so it cannot be part of what any withdrawal pays out - yet the interest-posting transactions it
        // was taken from still stand at their full gross amounts, which is all the loop below would otherwise see.
        // Without this, the withdrawal that TRIGGERS a 100% forfeiture (the forfeiture runs first, at the
        // write-platform layer, then the withdrawal reaches this entity-level hook) would be attributed to interest
        // the customer never receives - reporting it as both withdrawn AND forfeited in the same interest summary.
        // Consumed oldest-first below, matching this method's own oldest-first allocation, which nets out exactly
        // right in aggregate at any forfeiture percentage: what stays attributable is the interest that survived.
        BigDecimal remainingForfeited = forfeitedInterest(account);

        final List<SavingsAccountTransaction> orderedPostings = account.getTransactions().stream()
                .filter(SavingsAccountTransaction::isInterestPostingAndNotReversed).sorted(this.transactionComparator).toList();

        for (final SavingsAccountTransaction posting : orderedPostings) {
            if (remainingToAllocate.compareTo(BigDecimal.ZERO) <= 0) {
                break;
            }
            final BigDecimal postedAmount = posting.getAmount(currency).getAmount();
            final BigDecimal alreadyWithdrawn = this.repository.sumWithdrawnInterestForPostingTransaction(posting.getId());
            BigDecimal availableFromThisPosting = postedAmount.subtract(alreadyWithdrawn);
            if (remainingForfeited.compareTo(BigDecimal.ZERO) > 0 && availableFromThisPosting.compareTo(BigDecimal.ZERO) > 0) {
                final BigDecimal consumedByForfeiture = availableFromThisPosting.min(remainingForfeited);
                availableFromThisPosting = availableFromThisPosting.subtract(consumedByForfeiture);
                remainingForfeited = remainingForfeited.subtract(consumedByForfeiture);
            }
            if (availableFromThisPosting.compareTo(BigDecimal.ZERO) <= 0) {
                continue;
            }

            final BigDecimal amountToAllocate = availableFromThisPosting.min(remainingToAllocate);
            this.repository.save(DepositAccountInterestWithdrawal.createNew(account, posting, withdrawalTransaction, amountToAllocate,
                    withdrawalTransaction.getTransactionDate()));
            remainingToAllocate = remainingToAllocate.subtract(amountToAllocate);
        }
    }

    /**
     * Interest already taken back from this account, or zero on anything but a CUMULATIVE-mode product.
     *
     * The mode gate is what keeps PER_PERIOD behaviour byte-for-byte unchanged: {@code sumPostedChargeAmount} covers
     * every applied row in {@code m_savings_account_interest_charge}, which for a per-period product means its deferred
     * INTEREST_BASED_CHARGE rows - those reduce the balance too, so arguably the same reasoning applies to them, but
     * that is existing, separately-shipped behaviour and changing it is out of scope here. On a CUMULATIVE product the
     * per-period hook writes nothing at all (see
     * {@code DynamicDepositEarlyWithdrawalChargeService#recordIfApplicable}), so every row the sum returns there is a
     * forfeiture.
     */
    private BigDecimal forfeitedInterest(final DynamicDepositAccount account) {
        final List<SavingsProductEarlyWithdrawalCharge> selections = this.productEarlyWithdrawalChargeRepository
                .findBySavingsProductId(account.productId());
        if (selections.size() != 1 || !selections.get(0).mode().isCumulative()) {
            return BigDecimal.ZERO;
        }
        final BigDecimal forfeited = this.interestChargeRepository.sumPostedChargeAmount(account.getId());
        return forfeited == null ? BigDecimal.ZERO : forfeited;
    }
}
