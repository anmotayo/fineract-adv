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
import com.advancly.fineract.portfolio.savings.domain.DepositInterestChargeApplicationRepository;
import com.advancly.fineract.portfolio.savings.domain.DynamicDepositAccount;
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
    private final DepositInterestChargeApplicationRepository interestChargeApplicationRepository;
    private final SavingsAccountTransactionComparator transactionComparator = new SavingsAccountTransactionComparator();

    public DynamicDepositInterestWithdrawalService(final DepositAccountInterestWithdrawalRepository repository,
            final DepositInterestChargeApplicationRepository interestChargeApplicationRepository) {
        this.repository = repository;
        this.interestChargeApplicationRepository = interestChargeApplicationRepository;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void recordIfApplicable(final DynamicDepositAccount account, final SavingsAccountTransaction withdrawalTransaction) {
        final MonetaryCurrency currency = account.getCurrency();
        BigDecimal remainingToAllocate = withdrawalTransaction.getAmount(currency).getAmount();
        if (remainingToAllocate.compareTo(BigDecimal.ZERO) <= 0) {
            return;
        }

        // Interest this account has already had taken back by early-withdrawal interest charges is no longer in the
        // balance, so it cannot be part of what any withdrawal pays out - yet the interest-posting transactions it was
        // taken from still stand at their full gross amounts, which is all the loop below would otherwise see. Without
        // this, a later withdrawal would be attributed to interest the customer never receives - reporting it as both
        // withdrawn AND charged in the same interest summary.
        // Consumed oldest-first below, matching this method's own oldest-first allocation, which nets out exactly
        // right in aggregate at any charge percentage: what stays attributable is the interest that survived.
        BigDecimal remainingChargedInterest = chargedInterest(account);

        final List<SavingsAccountTransaction> orderedPostings = account.getTransactions().stream()
                .filter(SavingsAccountTransaction::isInterestPostingAndNotReversed).sorted(this.transactionComparator).toList();

        for (final SavingsAccountTransaction posting : orderedPostings) {
            if (remainingToAllocate.compareTo(BigDecimal.ZERO) <= 0) {
                break;
            }
            final BigDecimal postedAmount = posting.getAmount(currency).getAmount();
            final BigDecimal alreadyWithdrawn = this.repository.sumWithdrawnInterestForPostingTransaction(posting.getId());
            BigDecimal availableFromThisPosting = postedAmount.subtract(alreadyWithdrawn);
            if (remainingChargedInterest.compareTo(BigDecimal.ZERO) > 0 && availableFromThisPosting.compareTo(BigDecimal.ZERO) > 0) {
                final BigDecimal consumedByInterestCharge = availableFromThisPosting.min(remainingChargedInterest);
                availableFromThisPosting = availableFromThisPosting.subtract(consumedByInterestCharge);
                remainingChargedInterest = remainingChargedInterest.subtract(consumedByInterestCharge);
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

    private BigDecimal chargedInterest(final DynamicDepositAccount account) {
        final BigDecimal charged = this.interestChargeApplicationRepository.sumActiveAppliedAmountForAccount(account.getId());
        return charged == null ? BigDecimal.ZERO : charged;
    }
}
