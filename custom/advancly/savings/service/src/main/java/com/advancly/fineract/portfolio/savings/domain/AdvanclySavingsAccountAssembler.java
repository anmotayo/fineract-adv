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
import java.time.LocalDate;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.fineract.infrastructure.configuration.domain.ConfigurationDomainService;
import org.apache.fineract.portfolio.savings.domain.SavingsAccount;
import org.apache.fineract.portfolio.savings.domain.SavingsAccountRepositoryWrapper;
import org.apache.fineract.portfolio.savings.domain.SavingsAccountTransaction;
import org.apache.fineract.portfolio.savings.domain.SavingsAccountTransactionSummaryWrapper;
import org.apache.fineract.portfolio.savings.domain.SavingsHelper;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

/**
 * Optimized assembler that loads only the transactions needed for the current operation instead of the full history.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AdvanclySavingsAccountAssembler {

    private final SavingsAccountRepositoryWrapper savingsAccountRepository;
    private final AdvanclySavingsAccountTransactionRepository advanclyTransactionRepository;
    private final ConfigurationDomainService configurationDomainService;
    private final SavingsAccountTransactionSummaryWrapper summaryWrapper;
    private final SavingsHelper savingsHelper;

    private static final Pageable LAST_ONE = PageRequest.of(0, 1);

    /**
     * O(1) append path — loads account with NO transactions from history. Only fetches the last running balance and
     * interest/overdraft transactions for posting period checks.
     */
    public AssembledSavingsAccount assembleForAppendPath(final Long savingsId) {
        SavingsAccount account = savingsAccountRepository.findSavingsWithNotFoundDetection(savingsId, false);

        List<SavingsAccountTransaction> lastTxnList = advanclyTransactionRepository.findLastNonReversedTransaction(savingsId, LAST_ONE);

        if (!lastTxnList.isEmpty()) {
            BigDecimal lastBalance = lastTxnList.get(0).getRunningBalance(account.getCurrency()).getAmount();
            account.getSummary().setRunningBalanceOnPivotDate(lastBalance);
        } else {
            account.getSummary().setRunningBalanceOnPivotDate(BigDecimal.ZERO);
        }

        List<SavingsAccountTransaction> interestTxns = advanclyTransactionRepository
                .findNonReversedInterestAndOverdraftTransactions(savingsId);

        account.setHelpers(summaryWrapper, savingsHelper);
        return AssembledSavingsAccount.of(account, interestTxns);
    }

    /**
     * O(k) insert path — loads transactions from transactionDate onward. Sets opening balance from the last transaction
     * before that date.
     */
    public AssembledSavingsAccount assembleForInsertPath(final Long savingsId, final LocalDate transactionDate, boolean hasInterestRate) {
        SavingsAccount account = savingsAccountRepository.findSavingsWithNotFoundDetection(savingsId, false);

        List<SavingsAccountTransaction> transactions = advanclyTransactionRepository.findTransactionsOnOrAfterDate(account,
                transactionDate);

        if (!transactions.isEmpty()) {
            account.setSavingsAccountTransactions(transactions);
        }

        List<SavingsAccountTransaction> beforeDateTxns;
        if (hasInterestRate) {
            beforeDateTxns = advanclyTransactionRepository.findNonAccrualTransactionBeforeDate(savingsId, transactionDate, LAST_ONE);
        } else {
            beforeDateTxns = advanclyTransactionRepository.findNonInterestTransactionBeforeDate(savingsId, transactionDate, LAST_ONE);
        }

        if (!beforeDateTxns.isEmpty()) {
            account.getSummary().setRunningBalanceOnPivotDate(beforeDateTxns.get(0).getRunningBalance(account.getCurrency()).getAmount());
        } else {
            account.getSummary().setRunningBalanceOnPivotDate(BigDecimal.ZERO);
        }

        List<SavingsAccountTransaction> interestTxns = advanclyTransactionRepository
                .findNonReversedInterestAndOverdraftTransactions(savingsId);

        account.setHelpers(summaryWrapper, savingsHelper);
        return AssembledSavingsAccount.of(account, interestTxns);
    }

    /**
     * Full assembly with pivot support — used by non-optimized paths (interest posting, etc.)
     */
    public AssembledSavingsAccount assembleFrom(final Long savingsId, final boolean backdatedTxnsAllowedTill) {
        SavingsAccount account = savingsAccountRepository.findSavingsWithNotFoundDetection(savingsId, backdatedTxnsAllowedTill);

        if (backdatedTxnsAllowedTill) {
            loadTransactionsWithPivot(account);
        }

        List<SavingsAccountTransaction> interestTxns = advanclyTransactionRepository
                .findNonReversedInterestAndOverdraftTransactions(account.getId());

        account.setHelpers(summaryWrapper, savingsHelper);
        return AssembledSavingsAccount.of(account, interestTxns);
    }

    public boolean getPivotConfigStatus() {
        return configurationDomainService.retrievePivotDateConfig();
    }

    public boolean isRelaxingDaysConfigEnabled() {
        return configurationDomainService.isRelaxingDaysConfigForPivotDateEnabled();
    }

    public Long getRelaxingDays() {
        return configurationDomainService.retrieveRelaxingDaysConfigForPivotDate();
    }

    private void loadTransactionsWithPivot(SavingsAccount account) {
        boolean hasInterestRate = account.hasInterestCalculation() || account.hasOverdraftInterestCalculation();
        LocalDate pivotDate = hasInterestRate ? account.getSummary().getInterestPostedTillDate()
                : account.getSummary().getLastInterestCalculationDate();

        if (pivotDate != null) {
            LocalDate loadFromDate = pivotDate;
            if (isRelaxingDaysConfigEnabled()) {
                loadFromDate = pivotDate.minusDays(getRelaxingDays());
            }

            List<SavingsAccountTransaction> txns = advanclyTransactionRepository.findTransactionsOnOrAfterDate(account, loadFromDate);
            if (!txns.isEmpty()) {
                account.setSavingsAccountTransactions(txns);
            }

            List<SavingsAccountTransaction> beforePivot;
            if (hasInterestRate) {
                beforePivot = advanclyTransactionRepository.findNonAccrualTransactionBeforeDate(account.getId(), loadFromDate, LAST_ONE);
            } else {
                beforePivot = advanclyTransactionRepository.findNonInterestTransactionBeforeDate(account.getId(), loadFromDate, LAST_ONE);
            }
            if (!beforePivot.isEmpty()) {
                account.getSummary().setRunningBalanceOnPivotDate(beforePivot.get(0).getRunningBalance(account.getCurrency()).getAmount());
            }
        } else {
            List<SavingsAccountTransaction> allTxns = advanclyTransactionRepository.findBySavingsAccount(account);
            account.setSavingsAccountTransactions(allTxns);
        }
    }
}
