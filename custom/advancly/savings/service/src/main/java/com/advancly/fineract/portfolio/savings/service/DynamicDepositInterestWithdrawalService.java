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
    private final SavingsAccountTransactionComparator transactionComparator = new SavingsAccountTransactionComparator();

    public DynamicDepositInterestWithdrawalService(final DepositAccountInterestWithdrawalRepository repository) {
        this.repository = repository;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void recordIfApplicable(final DynamicDepositAccount account, final SavingsAccountTransaction withdrawalTransaction) {
        final MonetaryCurrency currency = account.getCurrency();
        BigDecimal remainingToAllocate = withdrawalTransaction.getAmount(currency).getAmount();
        if (remainingToAllocate.compareTo(BigDecimal.ZERO) <= 0) {
            return;
        }

        final List<SavingsAccountTransaction> orderedPostings = account.getTransactions().stream()
                .filter(SavingsAccountTransaction::isInterestPostingAndNotReversed).sorted(this.transactionComparator).toList();

        for (final SavingsAccountTransaction posting : orderedPostings) {
            if (remainingToAllocate.compareTo(BigDecimal.ZERO) <= 0) {
                break;
            }
            final BigDecimal postedAmount = posting.getAmount(currency).getAmount();
            final BigDecimal alreadyWithdrawn = this.repository.sumWithdrawnInterestForPostingTransaction(posting.getId());
            final BigDecimal availableFromThisPosting = postedAmount.subtract(alreadyWithdrawn);
            if (availableFromThisPosting.compareTo(BigDecimal.ZERO) <= 0) {
                continue;
            }

            final BigDecimal amountToAllocate = availableFromThisPosting.min(remainingToAllocate);
            this.repository.save(DepositAccountInterestWithdrawal.createNew(account, posting, withdrawalTransaction, amountToAllocate,
                    withdrawalTransaction.getTransactionDate()));
            remainingToAllocate = remainingToAllocate.subtract(amountToAllocate);
        }
    }
}
