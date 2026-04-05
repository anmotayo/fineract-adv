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

import com.advancly.fineract.portfolio.savings.domain.AdvanclySavingsAccountTransactionRepository;
import com.advancly.fineract.portfolio.savings.helper.SavingsAccountTransactionHelper;
import java.math.BigDecimal;
import java.math.MathContext;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.fineract.accounting.journalentry.service.JournalEntryWritePlatformService;
import org.apache.fineract.infrastructure.configuration.domain.ConfigurationDomainService;
import org.apache.fineract.infrastructure.core.domain.ExternalId;
import org.apache.fineract.infrastructure.core.service.DateUtils;
import org.apache.fineract.infrastructure.event.business.domain.savings.transaction.SavingsDepositBusinessEvent;
import org.apache.fineract.infrastructure.event.business.domain.savings.transaction.SavingsWithdrawalBusinessEvent;
import org.apache.fineract.infrastructure.event.business.service.BusinessEventNotifierService;
import org.apache.fineract.infrastructure.security.service.PlatformSecurityContext;
import org.apache.fineract.organisation.monetary.domain.MonetaryCurrency;
import org.apache.fineract.organisation.monetary.domain.Money;
import org.apache.fineract.portfolio.paymentdetail.domain.PaymentDetail;
import org.apache.fineract.portfolio.savings.SavingsTransactionBooleanValues;
import org.apache.fineract.portfolio.savings.domain.DepositAccountOnHoldTransactionRepository;
import org.apache.fineract.portfolio.savings.domain.SavingsAccount;
import org.apache.fineract.portfolio.savings.domain.SavingsAccountRepositoryWrapper;
import org.apache.fineract.portfolio.savings.domain.SavingsAccountTransaction;
import org.apache.fineract.portfolio.savings.domain.SavingsAccountTransactionRepository;
import org.apache.fineract.portfolio.savings.domain.SavingsAccountTransactionSummaryWrapper;
import org.apache.fineract.portfolio.savings.domain.SavingsHelper;
import org.apache.fineract.portfolio.savings.service.SavingsAccountDomainService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Optimized domain service for savings deposits and withdrawals. Uses O(1) append path for current-date transactions
 * and O(k) insert path for backdated ones.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AdvanclySavingsAccountDomainService implements SavingsAccountDomainService {

    private final PlatformSecurityContext context;
    private final SavingsAccountRepositoryWrapper savingsAccountRepository;
    private final SavingsAccountTransactionRepository savingsAccountTransactionRepository;
    private final JournalEntryWritePlatformService journalEntryWritePlatformService;
    private final ConfigurationDomainService configurationDomainService;
    private final DepositAccountOnHoldTransactionRepository depositAccountOnHoldTransactionRepository;
    private final BusinessEventNotifierService businessEventNotifierService;
    private final SavingsAccountTransactionSummaryWrapper savingsAccountTransactionSummaryWrapper;
    private final SavingsHelper savingsHelper;
    private final SavingsAccountTransactionHelper transactionHelper;
    private final AdvanclySavingsAccountTransactionRepository advanclyTransactionRepository;

    /**
     * Optimized deposit handler with O(1) append or O(k) insert path selection.
     */
    public SavingsAccountTransaction handleDepositOptimized(final SavingsAccount account, final LocalDate transactionDate,
            final BigDecimal transactionAmount, final PaymentDetail paymentDetail,
            final List<SavingsAccountTransaction> interestAndOverdraftTransactions, final Money lastRunningBalance,
            final MonetaryCurrency currency) {

        final String refNo = ExternalId.generate().getValue();
        final SavingsAccountTransaction deposit = SavingsAccountTransaction.deposit(account, account.office(), paymentDetail,
                transactionDate, Money.of(currency, transactionAmount), refNo);

        account.addTransaction(deposit);

        Optional<LocalDate> lastTxnDate = advanclyTransactionRepository.findLastTransactionDate(account.getId());
        boolean isAppendPath = lastTxnDate.isEmpty() || !transactionDate.isBefore(lastTxnDate.get());

        if (isAppendPath) {
            // O(1) path
            transactionHelper.setRunningBalanceForAppendPath(deposit, lastRunningBalance, currency);
            transactionHelper.updateSummaryIncremental(account, deposit, currency);
        } else {
            // Insert path
            handleInsertPathForDeposit(account, transactionDate, deposit, interestAndOverdraftTransactions, currency);
        }

        final Set<Long> existingTransactionIds = new HashSet<>(account.findCurrentTransactionIdsWithPivotDateConfig());
        final Set<Long> existingReversedTransactionIds = new HashSet<>(account.findCurrentReversedTransactionIdsWithPivotDateConfig());

        saveTransactionToGenerateTransactionId(deposit);
        saveUpdatedTransactionsOfSavingsAccount(account.getSavingsAccountTransactionsWithPivotConfig());
        savingsAccountRepository.saveAndFlush(account);

        postJournalEntries(account, existingTransactionIds, existingReversedTransactionIds, false);
        businessEventNotifierService.notifyPostBusinessEvent(new SavingsDepositBusinessEvent(deposit));
        return deposit;
    }

    /**
     * Optimized withdrawal handler with O(1) append or O(k) insert path selection.
     */
    public SavingsAccountTransaction handleWithdrawalOptimized(final SavingsAccount account, final LocalDate transactionDate,
            final BigDecimal transactionAmount, final PaymentDetail paymentDetail, final boolean applyWithdrawFee,
            final List<SavingsAccountTransaction> interestAndOverdraftTransactions, final Money lastRunningBalance,
            final MonetaryCurrency currency) {

        final String refNo = ExternalId.generate().getValue();
        final SavingsAccountTransaction withdrawal = SavingsAccountTransaction.withdrawal(account, account.office(), paymentDetail,
                transactionDate, Money.of(currency, transactionAmount), refNo);

        Optional<LocalDate> lastTxnDate = advanclyTransactionRepository.findLastTransactionDate(account.getId());
        boolean isAppendPath = lastTxnDate.isEmpty() || !transactionDate.isBefore(lastTxnDate.get());

        if (isAppendPath) {
            // O(1) balance validation + append
            transactionHelper.validateBalanceForAppendPath(account, transactionAmount, currency);
            account.addTransaction(withdrawal);
            transactionHelper.setRunningBalanceForAppendPath(withdrawal, lastRunningBalance, currency);
            transactionHelper.updateSummaryIncremental(account, withdrawal, currency);
        } else {
            account.addTransaction(withdrawal);
            handleInsertPathForWithdrawal(account, transactionDate, transactionAmount, interestAndOverdraftTransactions, currency);
        }

        final Set<Long> existingTransactionIds = new HashSet<>(account.findCurrentTransactionIdsWithPivotDateConfig());
        final Set<Long> existingReversedTransactionIds = new HashSet<>(account.findCurrentReversedTransactionIdsWithPivotDateConfig());

        saveTransactionToGenerateTransactionId(withdrawal);
        saveUpdatedTransactionsOfSavingsAccount(account.getSavingsAccountTransactionsWithPivotConfig());
        savingsAccountRepository.save(account);

        postJournalEntries(account, existingTransactionIds, existingReversedTransactionIds, false);
        businessEventNotifierService.notifyPostBusinessEvent(new SavingsWithdrawalBusinessEvent(withdrawal));
        return withdrawal;
    }

    private void handleInsertPathForDeposit(SavingsAccount account, LocalDate transactionDate, SavingsAccountTransaction deposit,
            List<SavingsAccountTransaction> interestAndOverdraftTransactions, MonetaryCurrency currency) {
        boolean hasInterest = account.hasInterestCalculation() || account.hasOverdraftInterestCalculation();
        boolean beforeLastPosting = transactionHelper.isBeforeLastPostingPeriod(transactionDate, interestAndOverdraftTransactions);

        if (beforeLastPosting && hasInterest) {
            // Case B/C: needs interest re-posting — fall back to full path
            fullRecalculation(account);
        } else {
            // Case A: after interest posting — just recalculate running balances
            Money openingBalance = Money.of(currency, account.getSummary().getRunningBalanceOnPivotDate());
            List<SavingsAccountTransaction> sortedTxns = account.getSavingsAccountTransactionsWithPivotConfig();
            transactionHelper.recalculateDailyBalancesFromDate(sortedTxns, openingBalance, currency);
            transactionHelper.updateSummaryIncremental(account, deposit, currency);
        }
    }

    private void handleInsertPathForWithdrawal(SavingsAccount account, LocalDate transactionDate, BigDecimal transactionAmount,
            List<SavingsAccountTransaction> interestAndOverdraftTransactions, MonetaryCurrency currency) {
        boolean hasInterest = account.hasInterestCalculation() || account.hasOverdraftInterestCalculation();
        boolean beforeLastPosting = transactionHelper.isBeforeLastPostingPeriod(transactionDate, interestAndOverdraftTransactions);

        if (beforeLastPosting && hasInterest) {
            fullRecalculation(account);
        } else {
            Money openingBalance = Money.of(currency, account.getSummary().getRunningBalanceOnPivotDate());
            List<SavingsAccountTransaction> sortedTxns = account.getSavingsAccountTransactionsWithPivotConfig();
            transactionHelper.recalculateDailyBalancesFromDate(sortedTxns, openingBalance, currency);
            transactionHelper.validateBalanceDoesNotBecomeNegative(account, sortedTxns, openingBalance, currency);
            // For insert path, use single-pass summary calculation instead of 12 separate iterations
            transactionHelper.calculateAndUpdateSummaryInSinglePass(account, sortedTxns, currency);
        }
    }

    private void fullRecalculation(SavingsAccount account) {
        MathContext mc = MathContext.DECIMAL64;
        LocalDate today = DateUtils.getBusinessLocalDate();
        boolean isSavingsInterestPostingAtCurrentPeriodEnd = configurationDomainService.isSavingsInterestPostingAtCurrentPeriodEnd();
        Integer financialYearBeginningMonth = configurationDomainService.retrieveFinancialYearBeginningMonth();
        boolean postReversals = configurationDomainService.isReversalTransactionAllowed();

        account.calculateInterestUsing(mc, today, false, isSavingsInterestPostingAtCurrentPeriodEnd, financialYearBeginningMonth, null,
                true, postReversals);
    }

    // === Interface method implementations — these are called by the WritePlatformService ===

    @Transactional
    @Override
    public SavingsAccountTransaction handleDeposit(SavingsAccount account, java.time.format.DateTimeFormatter fmt,
            LocalDate transactionDate, BigDecimal transactionAmount, PaymentDetail paymentDetail, boolean isAccountTransfer,
            boolean isRegularTransaction, boolean backdatedTxnsAllowedTill) {
        // The optimized path is called directly via handleDepositOptimized
        // This method is kept for interface compliance and non-optimized callers
        throw new UnsupportedOperationException("Use handleDepositOptimized() via AdvanclySavingsAccountWritePlatformService");
    }

    @Transactional
    @Override
    public SavingsAccountTransaction handleWithdrawal(SavingsAccount account, java.time.format.DateTimeFormatter fmt,
            LocalDate transactionDate, BigDecimal transactionAmount, PaymentDetail paymentDetail,
            SavingsTransactionBooleanValues transactionBooleanValues, boolean backdatedTxnsAllowedTill, boolean isFromJob) {
        throw new UnsupportedOperationException("Use handleWithdrawalOptimized() via AdvanclySavingsAccountWritePlatformService");
    }

    @Transactional
    @Override
    public void postJournalEntries(SavingsAccount savingsAccount, Set<Long> existingTransactionIds,
            Set<Long> existingReversedTransactionIds, boolean backdatedTxnsAllowedTill) {
        final Map<String, Object> accountingBridgeData = savingsAccount.deriveAccountingBridgeData(savingsAccount.getCurrency().getCode(),
                existingTransactionIds, existingReversedTransactionIds, false, true);
        journalEntryWritePlatformService.createJournalEntriesForSavings(accountingBridgeData);
    }

    @Override
    public SavingsAccountTransaction handleDividendPayout(SavingsAccount account, LocalDate transactionDate, BigDecimal transactionAmount,
            boolean backdatedTxnsAllowedTill) {
        // Dividend payouts are treated as deposits — delegate to full path
        throw new UnsupportedOperationException("Dividend payout not optimized — use core path");
    }

    @Override
    public SavingsAccountTransaction handleReversal(SavingsAccount account, List<SavingsAccountTransaction> savingsAccountTransactions,
            boolean backdatedTxnsAllowedTill) {
        throw new UnsupportedOperationException("Reversal not optimized — use core path");
    }

    @Override
    public SavingsAccountTransaction handleHold(SavingsAccount account, BigDecimal amount, LocalDate transactionDate, Boolean lienAllowed) {
        return SavingsAccountTransaction.holdAmount(account, account.office(), null, transactionDate,
                Money.of(account.getCurrency(), amount), lienAllowed);
    }

    @Override
    public void postInterest(SavingsAccount account, MathContext mc, LocalDate interestPostingUpToDate, boolean isInterestTransfer,
            boolean isSavingsInterestPostingAtCurrentPeriodEnd, Integer financialYearBeginningMonth, LocalDate postInterestOnDate,
            boolean backdatedTxnsAllowedTill, boolean postReversals) {
        // Interest posting is delegated via WritePlatformService to the core path.
        // This method is kept for interface compliance.
        throw new UnsupportedOperationException("Interest posting not optimized — use core path via delegate");
    }

    @Override
    public void reverseTransfer(SavingsAccountTransaction savingsTransaction, boolean backdatedTxnsAllowedTill) {
        savingsTransaction.reverse();
    }

    @Override
    public void undoTransaction(SavingsAccount account, SavingsAccountTransaction savingsAccountTransaction) {
        savingsAccountTransaction.reverse();
    }

    @Override
    public void checkClientOrGroupActive(SavingsAccount account) {
        // Validation delegated to the account entity methods
        account.validateForAccountBlock();
    }

    private void saveTransactionToGenerateTransactionId(SavingsAccountTransaction transaction) {
        savingsAccountTransactionRepository.saveAndFlush(transaction);
    }

    private void saveUpdatedTransactionsOfSavingsAccount(List<SavingsAccountTransaction> savingsAccountTransactions) {
        savingsAccountTransactionRepository.saveAll(savingsAccountTransactions);
    }
}
