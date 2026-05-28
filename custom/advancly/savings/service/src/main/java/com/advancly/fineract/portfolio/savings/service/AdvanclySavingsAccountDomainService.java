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

import com.advancly.fineract.portfolio.savings.helper.SavingsAccountTransactionHelper;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.apache.fineract.accounting.journalentry.service.JournalEntryWritePlatformService;
import org.apache.fineract.infrastructure.event.business.domain.savings.transaction.SavingsDepositBusinessEvent;
import org.apache.fineract.infrastructure.event.business.domain.savings.transaction.SavingsWithdrawalBusinessEvent;
import org.apache.fineract.infrastructure.event.business.service.BusinessEventNotifierService;
import org.apache.fineract.organisation.monetary.domain.MonetaryCurrency;
import org.apache.fineract.organisation.monetary.domain.Money;
import org.apache.fineract.portfolio.paymentdetail.domain.PaymentDetail;
import org.apache.fineract.portfolio.savings.SavingsTransactionBooleanValues;
import org.apache.fineract.portfolio.savings.domain.SavingsAccount;
import org.apache.fineract.portfolio.savings.domain.SavingsAccountRepositoryWrapper;
import org.apache.fineract.portfolio.savings.domain.SavingsAccountTransaction;
import org.apache.fineract.portfolio.savings.domain.SavingsAccountTransactionRepository;
import org.apache.fineract.portfolio.savings.service.SavingsAccountDomainService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Optimized domain service for savings deposits and withdrawals. Uses O(1) append path for current-date transactions.
 * Backdated transactions are delegated to the core domain service.
 */
@SuppressWarnings("SpringJavaInjectionPointsAutowiringInspection")
@Slf4j
@Service
@Primary
public class AdvanclySavingsAccountDomainService implements SavingsAccountDomainService {

    private final SavingsAccountRepositoryWrapper savingsAccountRepository;
    private final SavingsAccountTransactionRepository savingsAccountTransactionRepository;
    private final BusinessEventNotifierService businessEventNotifierService;
    private final SavingsAccountTransactionHelper transactionHelper;
    private final SavingsAccountDomainService coreDomainService;
    private final JournalEntryWritePlatformService journalEntryWritePlatformService;

    @Autowired
    public AdvanclySavingsAccountDomainService(final SavingsAccountRepositoryWrapper savingsAccountRepository,
            final SavingsAccountTransactionRepository savingsAccountTransactionRepository,
            final BusinessEventNotifierService businessEventNotifierService, final SavingsAccountTransactionHelper transactionHelper,
            @Qualifier("coreSavingsAccountDomainService") final SavingsAccountDomainService coreDomainService,
            JournalEntryWritePlatformService journalEntryWritePlatformService) {
        this.savingsAccountRepository = savingsAccountRepository;
        this.savingsAccountTransactionRepository = savingsAccountTransactionRepository;
        this.businessEventNotifierService = businessEventNotifierService;
        this.transactionHelper = transactionHelper;
        this.coreDomainService = coreDomainService;
        this.journalEntryWritePlatformService = journalEntryWritePlatformService;
    }

    /**
     * O(1) append-only deposit handler. The caller (WritePlatformService) must ensure the transaction is not backdated.
     */
    public SavingsAccountTransaction handleDepositOptimized(final SavingsAccount account, final LocalDate transactionDate,
            final BigDecimal transactionAmount, final PaymentDetail paymentDetail, final Money lastRunningBalance,
            final MonetaryCurrency currency, final SavingsAccountTransaction lastNonReversedTransaction, boolean isAccountTransfer) {
        account.validateForAccountBlock();
        account.validateForCreditBlock();

        UUID refNo = UUID.randomUUID();
        final SavingsAccountTransaction deposit = SavingsAccountTransaction.deposit(account, account.office(), paymentDetail,
                transactionDate, Money.of(currency, transactionAmount), refNo.toString());

        account.addTransactionToExisting(deposit);

        transactionHelper.updatePreviousTransactionBalanceEndDate(lastNonReversedTransaction, transactionDate, currency);
        transactionHelper.setRunningBalanceForAppendPath(deposit, lastRunningBalance, currency);
        transactionHelper.updateSummaryIncremental(account, deposit, currency);

        final Set<Long> existingTransactionIds = new HashSet<>(account.findCurrentTransactionIdsWithPivotDateConfig());
        final Set<Long> existingReversedTransactionIds = new HashSet<>(account.findCurrentReversedTransactionIdsWithPivotDateConfig());

        saveTransactionToGenerateTransactionId(deposit);
        saveUpdatedTransactionsOfSavingsAccount(account.getSavingsAccountTransactionsWithPivotConfig());
        savingsAccountRepository.saveAndFlush(account);

        postJournalEntries(account, existingTransactionIds, existingReversedTransactionIds, isAccountTransfer, true);
        businessEventNotifierService.notifyPostBusinessEvent(new SavingsDepositBusinessEvent(deposit));
        return deposit;
    }

    /**
     * O(1) append-only withdrawal handler. The caller (WritePlatformService) must ensure the transaction is not
     * backdated.
     */
    public SavingsAccountTransaction handleWithdrawalOptimized(final SavingsAccount account, final LocalDate transactionDate,
            final BigDecimal transactionAmount, final PaymentDetail paymentDetail, final boolean applyWithdrawFee,
            final Money lastRunningBalance, final MonetaryCurrency currency, final SavingsAccountTransaction lastNonReversedTransaction,
            boolean isAccountTransfer) {
        account.validateForAccountBlock();
        account.validateForDebitBlock();

        UUID refNo = UUID.randomUUID();
        final SavingsAccountTransaction withdrawal = SavingsAccountTransaction.withdrawal(account, account.office(), paymentDetail,
                transactionDate, Money.of(currency, transactionAmount), refNo.toString());

        transactionHelper.validateBalanceForAppendPath(account, transactionAmount, currency);
        account.addTransactionToExisting(withdrawal);
        transactionHelper.updatePreviousTransactionBalanceEndDate(lastNonReversedTransaction, transactionDate, currency);
        transactionHelper.setRunningBalanceForAppendPath(withdrawal, lastRunningBalance, currency);
        transactionHelper.updateSummaryIncremental(account, withdrawal, currency);

        final Set<Long> existingTransactionIds = new HashSet<>(account.findCurrentTransactionIdsWithPivotDateConfig());
        final Set<Long> existingReversedTransactionIds = new HashSet<>(account.findCurrentReversedTransactionIdsWithPivotDateConfig());

        saveTransactionToGenerateTransactionId(withdrawal);
        saveUpdatedTransactionsOfSavingsAccount(account.getSavingsAccountTransactionsWithPivotConfig());
        savingsAccountRepository.save(account);

        postJournalEntries(account, existingTransactionIds, existingReversedTransactionIds, isAccountTransfer, true);
        businessEventNotifierService.notifyPostBusinessEvent(new SavingsWithdrawalBusinessEvent(withdrawal));
        return withdrawal;
    }

    // === Interface method implementations — delegate to core ===

    @Transactional
    @Override
    public SavingsAccountTransaction handleDeposit(final SavingsAccount account, final DateTimeFormatter fmt,
            final LocalDate transactionDate, final BigDecimal transactionAmount, final PaymentDetail paymentDetail,
            final boolean isAccountTransfer, final boolean isRegularTransaction, final boolean backdatedTxnsAllowedTill) {
        return coreDomainService.handleDeposit(account, fmt, transactionDate, transactionAmount, paymentDetail, isAccountTransfer,
                isRegularTransaction, backdatedTxnsAllowedTill);
    }

    @Transactional
    @Override
    public SavingsAccountTransaction handleWithdrawal(final SavingsAccount account, final DateTimeFormatter fmt,
            final LocalDate transactionDate, final BigDecimal transactionAmount, final PaymentDetail paymentDetail,
            final SavingsTransactionBooleanValues transactionBooleanValues, final boolean backdatedTxnsAllowedTill) {
        return coreDomainService.handleWithdrawal(account, fmt, transactionDate, transactionAmount, paymentDetail, transactionBooleanValues,
                backdatedTxnsAllowedTill);
    }

    @Transactional
    @Override
    public void postJournalEntries(final SavingsAccount savingsAccount, final Set<Long> existingTransactionIds,
            final Set<Long> existingReversedTransactionIds, final boolean backdatedTxnsAllowedTill) {
        final boolean isAccountTransfer = false;
        postJournalEntries(savingsAccount, existingTransactionIds, existingReversedTransactionIds, isAccountTransfer,
                backdatedTxnsAllowedTill);
    }

    private void postJournalEntries(final SavingsAccount savingsAccount, final Set<Long> existingTransactionIds,
            final Set<Long> existingReversedTransactionIds, final boolean isAccountTransfer, final boolean backdatedTxnsAllowedTill) {
        final Map<String, Object> accountingBridgeData = savingsAccount.deriveAccountingBridgeData(savingsAccount.getCurrency().getCode(),
                existingTransactionIds, existingReversedTransactionIds, isAccountTransfer, backdatedTxnsAllowedTill);
        this.journalEntryWritePlatformService.createJournalEntriesForSavings(accountingBridgeData);
    }

    @Override
    public SavingsAccountTransaction handleDividendPayout(final SavingsAccount account, final LocalDate transactionDate,
            final BigDecimal transactionAmount, final boolean backdatedTxnsAllowedTill) {
        return coreDomainService.handleDividendPayout(account, transactionDate, transactionAmount, backdatedTxnsAllowedTill);
    }

    @Override
    public SavingsAccountTransaction handleReversal(SavingsAccount account, List<SavingsAccountTransaction> savingsAccountTransactions,
            boolean backdatedTxnsAllowedTill) {
        return coreDomainService.handleReversal(account, savingsAccountTransactions, backdatedTxnsAllowedTill);
    }

    @Override
    public SavingsAccountTransaction handleHold(SavingsAccount account, BigDecimal amount, LocalDate transactionDate, Boolean lienAllowed) {
        return coreDomainService.handleHold(account, amount, transactionDate, lienAllowed);
    }

    private void saveTransactionToGenerateTransactionId(SavingsAccountTransaction transaction) {
        savingsAccountTransactionRepository.saveAndFlush(transaction);
    }

    private void saveUpdatedTransactionsOfSavingsAccount(List<SavingsAccountTransaction> savingsAccountTransactions) {
        savingsAccountTransactionRepository.saveAll(savingsAccountTransactions);
    }
}
