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
import org.apache.fineract.portfolio.savings.domain.SavingsAccount;
import org.apache.fineract.portfolio.savings.domain.SavingsAccountRepositoryWrapper;
import org.apache.fineract.portfolio.savings.domain.SavingsAccountTransaction;
import org.apache.fineract.portfolio.savings.domain.SavingsAccountTransactionSummaryWrapper;
import org.apache.fineract.portfolio.savings.domain.SavingsHelper;
import org.springframework.stereotype.Service;

/**
 * Optimized assembler that loads only the data needed for the O(1) append path instead of the full transaction history.
 */
@SuppressWarnings("SpringJavaInjectionPointsAutowiringInspection")
@Slf4j
@Service
@RequiredArgsConstructor
public class AdvanclySavingsAccountAssembler {

    private final SavingsAccountRepositoryWrapper savingsAccountRepository;
    private final AdvanclySavingsAccountTransactionRepository advanclyTransactionRepository;
    private final SavingsAccountTransactionSummaryWrapper summaryWrapper;
    private final SavingsHelper savingsHelper;

    // Mirrors the query's own "order by transaction_date desc, created_date desc, id desc" exactly, so role
    // resolution below agrees with the database's own tie-breaking when two rows share a transaction date.
    private static final Comparator<SavingsAccountTransaction> MOST_RECENT_FIRST = Comparator
            .comparing(SavingsAccountTransaction::getTransactionDate)
            .thenComparing(txn -> txn.getCreatedDate().orElse(null), Comparator.nullsFirst(Comparator.naturalOrder()))
            .thenComparing(SavingsAccountTransaction::getId);

    /**
     * O(1) append path — loads account with NO transactions from history. Only fetches the last running balance.
     */
    public AssembledSavingsAccount assembleForAppendPath(final Long savingsId) {
        SavingsAccount account = savingsAccountRepository.findSavingsWithNotFoundDetection(savingsId, true);

        // Single round trip: findLastNonReversedAndBalanceBearingTransactions(...) returns at most 2 rows - the
        // last non-reversed transaction (excluding accrual) and the last balance-bearing one - deduped by the
        // database to 1 row whenever they're the same transaction (the common case). See its javadoc for why role
        // resolution below (by attribute, not by result order) is always correct regardless of which case applies.
        List<SavingsAccountTransaction> recentTransactions = advanclyTransactionRepository
                .findLastNonReversedAndBalanceBearingTransactions(savingsId);

        // The running-balance seed must come from the true latest row (a posting moves the balance too), so it's
        // simply the most recent of the (at most 2) rows returned - never the window-closing target below, which
        // closing a posting/accrual/overdraft-interest row would wrongly give a window core never intends it to have.
        SavingsAccountTransaction lastTransaction = recentTransactions.stream().max(MOST_RECENT_FIRST).orElse(null);
        if (lastTransaction != null) {
            BigDecimal lastBalance = lastTransaction.getRunningBalance(account.getCurrency()).getAmount();
            account.getSummary().setRunningBalanceOnPivotDate(lastBalance);
        } else {
            account.getSummary().setRunningBalanceOnPivotDate(BigDecimal.ZERO);
        }

        SavingsAccountTransaction lastBalanceBearingTransaction = recentTransactions.stream()
                .filter(SavingsAccountTransaction::isBalanceBearing).findFirst().orElse(null);

        account.setHelpers(summaryWrapper, savingsHelper);
        return AssembledSavingsAccount.of(account, lastBalanceBearingTransaction);
    }
}
