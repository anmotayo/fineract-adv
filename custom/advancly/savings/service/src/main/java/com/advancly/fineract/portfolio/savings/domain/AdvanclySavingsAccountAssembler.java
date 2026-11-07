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
package com.advancly.fineract.portfolio.savings.domain;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.fineract.infrastructure.configuration.domain.ConfigurationDomainService;
import org.apache.fineract.portfolio.savings.domain.SavingsAccount;
import org.apache.fineract.portfolio.savings.domain.SavingsAccountSummary;
import org.apache.fineract.portfolio.savings.domain.SavingsAccountTransaction;
import org.apache.fineract.portfolio.savings.domain.SavingsAccountTransactionSummaryWrapper;
import org.apache.fineract.portfolio.savings.domain.SavingsHelper;
import org.apache.fineract.portfolio.savings.exception.SavingsAccountNotFoundException;
import org.springframework.stereotype.Service;

/** Loads only what the O(1) append path needs, not the full transaction history. */
@SuppressWarnings("SpringJavaInjectionPointsAutowiringInspection")
@Slf4j
@Service
@RequiredArgsConstructor
public class AdvanclySavingsAccountAssembler {

    private final AdvanclySavingsAccountLockRepository savingsAccountLockRepository;
    private final AdvanclySavingsAccountTransactionRepository advanclyTransactionRepository;
    private final SavingsAccountTransactionSummaryWrapper summaryWrapper;
    private final SavingsHelper savingsHelper;
    private final ConfigurationDomainService configurationDomainService;

    // Must match the queries' ORDER BY so same-date tie-breaking agrees with the database.
    private static final Comparator<SavingsAccountTransaction> MOST_RECENT_FIRST = Comparator
            .comparing(SavingsAccountTransaction::getTransactionDate)
            .thenComparing(txn -> txn.getCreatedDate().orElse(null), Comparator.nullsFirst(Comparator.naturalOrder()))
            .thenComparing(SavingsAccountTransaction::getId);

    public AssembledSavingsAccount assembleForAppendPath(final Long savingsId) {
        // Locked read without transaction history; see AdvanclySavingsAccountLockRepository.
        SavingsAccount account = savingsAccountLockRepository.findByIdForUpdate(savingsId)
                .orElseThrow(() -> new SavingsAccountNotFoundException(savingsId));

        List<SavingsAccountTransaction> recentTransactions = hasNoTransactions(account) ? List.of()
                : advanclyTransactionRepository.findLastNonReversedAndBalanceBearingTransactions(savingsId);

        // Seed from the true latest row (postings move the balance), not the balance-bearing window target.
        SavingsAccountTransaction lastTransaction = recentTransactions.stream().max(MOST_RECENT_FIRST).orElse(null);
        if (lastTransaction != null) {
            BigDecimal lastBalance = lastTransaction.getRunningBalance(account.getCurrency()).getAmount();
            account.getSummary().setRunningBalanceOnPivotDate(lastBalance);
        } else {
            account.getSummary().setRunningBalanceOnPivotDate(BigDecimal.ZERO);
        }

        SavingsAccountTransaction lastBalanceBearingTransaction = recentTransactions.stream()
                .filter(SavingsAccountTransaction::isBalanceBearing).findFirst().orElse(null);

        account.setHelpers(summaryWrapper, savingsHelper, configurationDomainService);
        return AssembledSavingsAccount.of(account, lastBalanceBearingTransaction,
                lastTransaction == null ? null : lastTransaction.getTransactionDate());
    }

    /**
     * Skips the locking lookups (and their deadlock-prone gap lock) for an account with no transactions. The locked
     * row's totals catch a first transaction committed after our snapshot; the existence check catches types that move
     * no total.
     */
    private boolean hasNoTransactions(final SavingsAccount account) {
        final SavingsAccountSummary summary = account.getSummary();
        return isZero(summary.getAccountBalance()) && isZero(summary.getTotalDeposits()) && isZero(summary.getTotalWithdrawals())
                && isZero(summary.getTotalInterestPosted()) && isZero(summary.getTotalWithdrawalFees())
                && isZero(summary.getTotalFeeCharge()) && isZero(summary.getTotalPenaltyCharge()) && isZero(summary.getTotalAnnualFees())
                && isZero(summary.getTotalOverdraftInterestDerived()) && isZero(summary.getTotalWithholdTax())
                && isZero(account.getSavingsHoldAmount()) && isZero(account.getOnHoldFunds())
                && !advanclyTransactionRepository.existsBySavingsAccountId(account.getId());
    }

    private static boolean isZero(final BigDecimal value) {
        return value == null || value.signum() == 0;
    }
}
