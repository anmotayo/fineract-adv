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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.advancly.fineract.portfolio.savings.domain.AdvanclyChargeInterestRule;
import com.advancly.fineract.portfolio.savings.domain.AdvanclyChargeInterestRuleRepository;
import com.advancly.fineract.portfolio.savings.domain.CustomPeriodReapplyPolicy;
import com.advancly.fineract.portfolio.savings.domain.DepositInterestChargeApplication;
import com.advancly.fineract.portfolio.savings.domain.DepositInterestChargeApplicationRepository;
import com.advancly.fineract.portfolio.savings.domain.InterestBasisMode;
import com.advancly.fineract.portfolio.savings.helper.SavingsAccountTransactionHelper;
import com.advancly.fineract.portfolio.savings.testutil.MoneyHelperInitializer;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.fineract.accounting.journalentry.service.JournalEntryWritePlatformService;
import org.apache.fineract.organisation.monetary.domain.MonetaryCurrency;
import org.apache.fineract.organisation.monetary.domain.Money;
import org.apache.fineract.organisation.office.domain.Office;
import org.apache.fineract.portfolio.charge.domain.Charge;
import org.apache.fineract.portfolio.savings.SavingsPostingInterestPeriodType;
import org.apache.fineract.portfolio.savings.domain.SavingsAccount;
import org.apache.fineract.portfolio.savings.domain.SavingsAccountCharge;
import org.apache.fineract.portfolio.savings.domain.SavingsAccountRepositoryWrapper;
import org.apache.fineract.portfolio.savings.domain.SavingsAccountTransaction;
import org.apache.fineract.portfolio.savings.domain.SavingsAccountTransactionRepository;
import org.apache.fineract.portfolio.savings.domain.SavingsAccountTransactionSummaryWrapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;

class AdvanclyInterestChargeApplicationServiceTest {

    private static final MonetaryCurrency CURRENCY = new MonetaryCurrency("USD", 2, null);

    @BeforeEach
    void setUp() {
        MoneyHelperInitializer.initialize();
    }

    @Test
    void oncePerSelectedPeriodAlreadyAppliedSkipsChargeInsteadOfRejectingWithdrawal() {
        final AdvanclyChargeInterestRuleRepository ruleRepository = mock(AdvanclyChargeInterestRuleRepository.class);
        final DepositInterestChargeApplicationRepository applicationRepository = mock(DepositInterestChargeApplicationRepository.class);
        final SavingsAccountTransactionRepository transactionRepository = mock(SavingsAccountTransactionRepository.class);
        final SavingsAccountRepositoryWrapper savingsAccountRepository = mock(SavingsAccountRepositoryWrapper.class);
        final SavingsAccountTransactionHelper transactionHelper = mock(SavingsAccountTransactionHelper.class);
        final JournalEntryWritePlatformService journalEntryWritePlatformService = mock(JournalEntryWritePlatformService.class);
        final CumulativeInterestForfeitureService cumulativeInterestForfeitureService = mock(CumulativeInterestForfeitureService.class);
        final JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        final AdvanclyInterestChargeApplicationService service = new AdvanclyInterestChargeApplicationService(ruleRepository,
                applicationRepository, transactionRepository, savingsAccountRepository, transactionHelper, journalEntryWritePlatformService,
                cumulativeInterestForfeitureService, jdbcTemplate);

        final Long accountId = 11L;
        final Long savingsProductId = 22L;
        final Long chargeId = 33L;
        final LocalDate transactionDate = LocalDate.of(2031, 4, 18);
        final LocalDate selectedFromDate = LocalDate.of(2031, 4, 1);
        final LocalDate selectedToDate = LocalDate.of(2031, 4, 15);
        final SavingsAccount account = mock(SavingsAccount.class);
        final SavingsAccountTransaction withdrawalTransaction = mock(SavingsAccountTransaction.class);
        final AdvanclyChargeInterestRule rule = mock(AdvanclyChargeInterestRule.class);
        final SavingsAccountCharge accountCharge = mock(SavingsAccountCharge.class);
        final Charge charge = mock(Charge.class);

        when(withdrawalTransaction.isReversed()).thenReturn(false);
        when(withdrawalTransaction.getTransactionDate()).thenReturn(transactionDate);
        when(account.isEarlyWithdrawal(transactionDate)).thenReturn(true);
        when(account.getId()).thenReturn(accountId);
        when(account.productId()).thenReturn(savingsProductId);
        when(account.charges()).thenReturn(Set.of(accountCharge));
        when(account.getInterestPostingPeriodType()).thenReturn(1);
        when(ruleRepository.findBySavingsProductId(savingsProductId)).thenReturn(List.of(rule));
        when(rule.chargeId()).thenReturn(chargeId);
        when(rule.isCumulative()).thenReturn(false);
        when(rule.isCustomPeriod()).thenReturn(true);
        when(rule.customPeriodReapplyPolicy()).thenReturn(CustomPeriodReapplyPolicy.ONCE_PER_SELECTED_PERIOD);
        when(accountCharge.getCharge()).thenReturn(charge);
        when(accountCharge.isActive()).thenReturn(true);
        when(accountCharge.isPenaltyCharge()).thenReturn(true);
        when(charge.getId()).thenReturn(chargeId);
        when(applicationRepository.countActiveForSelectedPeriod(accountId, chargeId, selectedFromDate, selectedToDate)).thenReturn(1L);

        final SavingsAccountTransaction chargeTransaction = service.applyIfApplicable(account, withdrawalTransaction, true, null,
                selectedFromDate, selectedToDate, false, false);

        assertThat(chargeTransaction).isNull();
        verify(applicationRepository).countActiveForSelectedPeriod(accountId, chargeId, selectedFromDate, selectedToDate);
        verifyNoInteractions(transactionRepository, savingsAccountRepository, transactionHelper, journalEntryWritePlatformService,
                cumulativeInterestForfeitureService, jdbcTemplate);
    }

    @Test
    void cumulativeRulesDelegateToTheForfeitureService() {
        final AdvanclyChargeInterestRuleRepository ruleRepository = mock(AdvanclyChargeInterestRuleRepository.class);
        final DepositInterestChargeApplicationRepository applicationRepository = mock(DepositInterestChargeApplicationRepository.class);
        final SavingsAccountTransactionRepository transactionRepository = mock(SavingsAccountTransactionRepository.class);
        final SavingsAccountRepositoryWrapper savingsAccountRepository = mock(SavingsAccountRepositoryWrapper.class);
        final SavingsAccountTransactionHelper transactionHelper = mock(SavingsAccountTransactionHelper.class);
        final JournalEntryWritePlatformService journalEntryWritePlatformService = mock(JournalEntryWritePlatformService.class);
        final CumulativeInterestForfeitureService cumulativeInterestForfeitureService = mock(CumulativeInterestForfeitureService.class);
        final JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        final AdvanclyInterestChargeApplicationService service = new AdvanclyInterestChargeApplicationService(ruleRepository,
                applicationRepository, transactionRepository, savingsAccountRepository, transactionHelper, journalEntryWritePlatformService,
                cumulativeInterestForfeitureService, jdbcTemplate);

        final Long savingsProductId = 22L;
        final Long chargeId = 33L;
        final LocalDate transactionDate = LocalDate.of(2031, 4, 18);
        final BigDecimal percentageOverride = new BigDecimal("75");
        final SavingsAccount account = mock(SavingsAccount.class);
        final SavingsAccountTransaction withdrawalTransaction = mock(SavingsAccountTransaction.class);
        final SavingsAccountTransaction forfeitureTransaction = mock(SavingsAccountTransaction.class);
        final AdvanclyChargeInterestRule rule = mock(AdvanclyChargeInterestRule.class);
        final SavingsAccountCharge accountCharge = mock(SavingsAccountCharge.class);
        final Charge charge = mock(Charge.class);

        when(withdrawalTransaction.isReversed()).thenReturn(false);
        when(withdrawalTransaction.getTransactionDate()).thenReturn(transactionDate);
        when(account.isEarlyWithdrawal(transactionDate)).thenReturn(true);
        when(account.productId()).thenReturn(savingsProductId);
        when(account.charges()).thenReturn(Set.of(accountCharge));
        when(ruleRepository.findBySavingsProductId(savingsProductId)).thenReturn(List.of(rule));
        when(rule.chargeId()).thenReturn(chargeId);
        when(rule.isCumulative()).thenReturn(true);
        when(accountCharge.getCharge()).thenReturn(charge);
        when(accountCharge.isActive()).thenReturn(true);
        when(accountCharge.isPenaltyCharge()).thenReturn(true);
        when(charge.getId()).thenReturn(chargeId);
        when(cumulativeInterestForfeitureService.forfeitIfApplicable(account, withdrawalTransaction, transactionDate, true, false,
                percentageOverride)).thenReturn(forfeitureTransaction);

        final SavingsAccountTransaction chargeTransaction = service.applyIfApplicable(account, withdrawalTransaction, true,
                percentageOverride, null, null, true, false);

        assertThat(chargeTransaction).isSameAs(forfeitureTransaction);
        verify(cumulativeInterestForfeitureService).forfeitIfApplicable(account, withdrawalTransaction, transactionDate, true, false,
                percentageOverride);
        verifyNoInteractions(applicationRepository, transactionRepository, savingsAccountRepository, transactionHelper,
                journalEntryWritePlatformService, jdbcTemplate);
    }

    @Test
    void customPeriodPercentageIsAppliedToRemainingInterestAfterPriorApplications() {
        final AdvanclyChargeInterestRuleRepository ruleRepository = mock(AdvanclyChargeInterestRuleRepository.class);
        final DepositInterestChargeApplicationRepository applicationRepository = mock(DepositInterestChargeApplicationRepository.class);
        final SavingsAccountTransactionRepository transactionRepository = mock(SavingsAccountTransactionRepository.class);
        final SavingsAccountRepositoryWrapper savingsAccountRepository = mock(SavingsAccountRepositoryWrapper.class);
        final SavingsAccountTransactionHelper transactionHelper = mock(SavingsAccountTransactionHelper.class);
        final JournalEntryWritePlatformService journalEntryWritePlatformService = mock(JournalEntryWritePlatformService.class);
        final CumulativeInterestForfeitureService cumulativeInterestForfeitureService = mock(CumulativeInterestForfeitureService.class);
        final JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        final AdvanclyInterestChargeApplicationService service = new AdvanclyInterestChargeApplicationService(ruleRepository,
                applicationRepository, transactionRepository, savingsAccountRepository, transactionHelper, journalEntryWritePlatformService,
                cumulativeInterestForfeitureService, jdbcTemplate);

        final Long accountId = 11L;
        final Long savingsProductId = 22L;
        final Long chargeId = 33L;
        final LocalDate transactionDate = LocalDate.of(2031, 4, 18);
        final LocalDate selectedFromDate = LocalDate.of(2031, 4, 1);
        final LocalDate selectedToDate = LocalDate.of(2031, 4, 15);
        final Office office = mock(Office.class);
        final SavingsAccount account = mock(SavingsAccount.class);
        final SavingsAccountTransaction withdrawalTransaction = SavingsAccountTransaction.withdrawal(account, office, null, transactionDate,
                Money.of(CURRENCY, new BigDecimal("25")), null);
        withdrawalTransaction.setRunningBalance(Money.of(CURRENCY, new BigDecimal("1000")));
        final SavingsAccountTransaction interestPosting = SavingsAccountTransaction.interestPosting(account, office,
                LocalDate.of(2031, 4, 10), Money.of(CURRENCY, new BigDecimal("250")), false);
        final SavingsAccountTransaction withholdingTax = SavingsAccountTransaction.withHoldTax(account, office, LocalDate.of(2031, 4, 10),
                Money.of(CURRENCY, new BigDecimal("50")), Map.of());
        final AdvanclyChargeInterestRule rule = mock(AdvanclyChargeInterestRule.class);
        final SavingsAccountCharge accountCharge = mock(SavingsAccountCharge.class);
        final Charge charge = mock(Charge.class);

        when(account.isEarlyWithdrawal(transactionDate)).thenReturn(true);
        when(account.getId()).thenReturn(accountId);
        when(account.productId()).thenReturn(savingsProductId);
        when(account.charges()).thenReturn(Set.of(accountCharge));
        when(account.getInterestPostingPeriodType()).thenReturn(SavingsPostingInterestPeriodType.DAILY.getValue());
        when(account.getTransactions()).thenReturn(List.of(interestPosting, withholdingTax));
        when(account.getCurrency()).thenReturn(CURRENCY);
        when(account.office()).thenReturn(office);
        when(account.findCurrentTransactionIdsWithPivotDateConfig()).thenReturn(Set.of());
        when(account.findCurrentReversedTransactionIdsWithPivotDateConfig()).thenReturn(Set.of());
        when(ruleRepository.findBySavingsProductId(savingsProductId)).thenReturn(List.of(rule));
        when(rule.chargeId()).thenReturn(chargeId);
        when(rule.isCumulative()).thenReturn(false);
        when(rule.isCustomPeriod()).thenReturn(true);
        when(rule.interestBasisMode()).thenReturn(InterestBasisMode.CUSTOM_PERIOD);
        when(rule.customPeriodReapplyPolicy()).thenReturn(CustomPeriodReapplyPolicy.UNTIL_SELECTED_PERIOD_INTEREST_EXHAUSTED);
        when(accountCharge.getCharge()).thenReturn(charge);
        when(accountCharge.isActive()).thenReturn(true);
        when(accountCharge.isPenaltyCharge()).thenReturn(true);
        when(accountCharge.getPercentage()).thenReturn(new BigDecimal("50"));
        when(charge.getId()).thenReturn(chargeId);
        when(applicationRepository.sumActiveAppliedAmountForSelectedPeriod(accountId, chargeId, selectedFromDate, selectedToDate))
                .thenReturn(new BigDecimal("100"));
        when(applicationRepository.sumActiveAppliedAmountForAccount(accountId)).thenReturn(new BigDecimal("150"));

        final SavingsAccountTransaction chargeTransaction = service.applyIfApplicable(account, withdrawalTransaction, true, null,
                selectedFromDate, selectedToDate, false, false);

        assertThat(chargeTransaction).isNotNull();
        assertThat(chargeTransaction.getAmount()).isEqualByComparingTo("50");
        assertThat(chargeTransaction.isInterestCharge()).isTrue();
        final ArgumentCaptor<DepositInterestChargeApplication> applicationCaptor = ArgumentCaptor
                .forClass(DepositInterestChargeApplication.class);
        verify(applicationRepository).saveAndFlush(applicationCaptor.capture());
        final DepositInterestChargeApplication application = applicationCaptor.getValue();
        assertThat(application.originalBasisAmount()).isEqualByComparingTo("200");
        assertThat(application.previouslyConsumedAmount()).isEqualByComparingTo("100");
        assertThat(application.appliedAmount()).isEqualByComparingTo("50");
        verify(jdbcTemplate).update("update m_savings_account set total_interest_charge_derived = ? where id = ?", new BigDecimal("150"),
                accountId);
        verify(transactionRepository).save(chargeTransaction);
        verify(savingsAccountRepository).saveAndFlush(account);
        verify(journalEntryWritePlatformService).createJournalEntriesForSavings(any());
        // Regression guard: the withdrawal's balance window must be closed when the charge is appended right after
        // it, otherwise it keeps a stale/open-ended window instead of being confined to its own day.
        verify(transactionHelper).updatePreviousTransactionBalanceEndDate(withdrawalTransaction, transactionDate, CURRENCY);
        // Regression guard: neither appendPath nor backdatedTxnsAllowedTill is set, so the charge must go through the
        // JPA-managed collection, matching core's own non-append, non-backdated write path.
        verify(account).addTransaction(chargeTransaction);
        verify(account, never()).addTransactionToExisting(any());
    }

    @Test
    void appendPathAlwaysAddsToExistingRegardlessOfBackdatedTxnsAllowedTill() {
        final SavingsAccount account = mock(SavingsAccount.class);
        final SavingsAccountTransaction chargeTransaction = applyCustomPeriodChargeAndCaptureTransaction(account, true, false);

        // appendPath must win on its own: the withdrawal was added via addTransactionToExisting(...) because it went
        // through the O(1) append path, independent of whatever backdatedTxnsAllowedTill happens to be.
        verify(account).addTransactionToExisting(chargeTransaction);
        verify(account, never()).addTransaction(any());
    }

    @Test
    void nonAppendPathDefersToBackdatedTxnsAllowedTillForWhichCollectionCoreItselfUsed() {
        final SavingsAccount account = mock(SavingsAccount.class);
        final SavingsAccountTransaction chargeTransaction = applyCustomPeriodChargeAndCaptureTransaction(account, false, true);

        // Not on the append path, but core's own handleWithdrawal(...)/withdraw(...) used the pivot-config
        // collection for this withdrawal (backdatedTxnsAllowedTill=true), so the charge must follow suit.
        verify(account).addTransactionToExisting(chargeTransaction);
        verify(account, never()).addTransaction(any());
    }

    /**
     * Shared scaffolding for the appendPath/backdatedTxnsAllowedTill collection-selection tests above: applies a
     * custom-period charge with the given flags and returns the resulting charge transaction so the caller can assert
     * which of {@code account.addTransaction(...)}/{@code addTransactionToExisting(...)} received it.
     */
    private SavingsAccountTransaction applyCustomPeriodChargeAndCaptureTransaction(final SavingsAccount account, final boolean appendPath,
            final boolean backdatedTxnsAllowedTill) {
        final AdvanclyChargeInterestRuleRepository ruleRepository = mock(AdvanclyChargeInterestRuleRepository.class);
        final DepositInterestChargeApplicationRepository applicationRepository = mock(DepositInterestChargeApplicationRepository.class);
        final SavingsAccountTransactionRepository transactionRepository = mock(SavingsAccountTransactionRepository.class);
        final SavingsAccountRepositoryWrapper savingsAccountRepository = mock(SavingsAccountRepositoryWrapper.class);
        final SavingsAccountTransactionHelper transactionHelper = mock(SavingsAccountTransactionHelper.class);
        final JournalEntryWritePlatformService journalEntryWritePlatformService = mock(JournalEntryWritePlatformService.class);
        final CumulativeInterestForfeitureService cumulativeInterestForfeitureService = mock(CumulativeInterestForfeitureService.class);
        final JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        final AdvanclyInterestChargeApplicationService service = new AdvanclyInterestChargeApplicationService(ruleRepository,
                applicationRepository, transactionRepository, savingsAccountRepository, transactionHelper, journalEntryWritePlatformService,
                cumulativeInterestForfeitureService, jdbcTemplate);

        final Long accountId = 11L;
        final Long savingsProductId = 22L;
        final Long chargeId = 33L;
        final LocalDate transactionDate = LocalDate.of(2031, 4, 18);
        final LocalDate selectedFromDate = LocalDate.of(2031, 4, 1);
        final LocalDate selectedToDate = LocalDate.of(2031, 4, 15);
        final Office office = mock(Office.class);
        final SavingsAccountTransaction withdrawalTransaction = SavingsAccountTransaction.withdrawal(account, office, null, transactionDate,
                Money.of(CURRENCY, new BigDecimal("25")), null);
        withdrawalTransaction.setRunningBalance(Money.of(CURRENCY, new BigDecimal("1000")));
        final SavingsAccountTransaction interestPosting = SavingsAccountTransaction.interestPosting(account, office,
                LocalDate.of(2031, 4, 10), Money.of(CURRENCY, new BigDecimal("250")), false);
        final SavingsAccountTransaction withholdingTax = SavingsAccountTransaction.withHoldTax(account, office, LocalDate.of(2031, 4, 10),
                Money.of(CURRENCY, new BigDecimal("50")), Map.of());
        final AdvanclyChargeInterestRule rule = mock(AdvanclyChargeInterestRule.class);
        final SavingsAccountCharge accountCharge = mock(SavingsAccountCharge.class);
        final Charge charge = mock(Charge.class);

        when(account.isEarlyWithdrawal(transactionDate)).thenReturn(true);
        when(account.getId()).thenReturn(accountId);
        when(account.productId()).thenReturn(savingsProductId);
        when(account.charges()).thenReturn(Set.of(accountCharge));
        when(account.getInterestPostingPeriodType()).thenReturn(SavingsPostingInterestPeriodType.DAILY.getValue());
        when(account.getTransactions()).thenReturn(List.of(interestPosting, withholdingTax));
        when(account.getCurrency()).thenReturn(CURRENCY);
        when(account.office()).thenReturn(office);
        when(account.findCurrentTransactionIdsWithPivotDateConfig()).thenReturn(Set.of());
        when(account.findCurrentReversedTransactionIdsWithPivotDateConfig()).thenReturn(Set.of());
        when(ruleRepository.findBySavingsProductId(savingsProductId)).thenReturn(List.of(rule));
        when(rule.chargeId()).thenReturn(chargeId);
        when(rule.isCumulative()).thenReturn(false);
        when(rule.isCustomPeriod()).thenReturn(true);
        when(rule.interestBasisMode()).thenReturn(InterestBasisMode.CUSTOM_PERIOD);
        when(rule.customPeriodReapplyPolicy()).thenReturn(CustomPeriodReapplyPolicy.UNTIL_SELECTED_PERIOD_INTEREST_EXHAUSTED);
        when(accountCharge.getCharge()).thenReturn(charge);
        when(accountCharge.isActive()).thenReturn(true);
        when(accountCharge.isPenaltyCharge()).thenReturn(true);
        when(accountCharge.getPercentage()).thenReturn(new BigDecimal("50"));
        when(charge.getId()).thenReturn(chargeId);
        when(applicationRepository.sumActiveAppliedAmountForSelectedPeriod(accountId, chargeId, selectedFromDate, selectedToDate))
                .thenReturn(new BigDecimal("100"));
        when(applicationRepository.sumActiveAppliedAmountForAccount(accountId)).thenReturn(new BigDecimal("150"));

        return service.applyIfApplicable(account, withdrawalTransaction, true, null, selectedFromDate, selectedToDate, appendPath,
                backdatedTxnsAllowedTill);
    }

    @Test
    void chargeAppendedAfterWithdrawalClosesTheWithdrawalsBalanceWindowInsteadOfLeavingItOpenEnded() {
        final AdvanclyChargeInterestRuleRepository ruleRepository = mock(AdvanclyChargeInterestRuleRepository.class);
        final DepositInterestChargeApplicationRepository applicationRepository = mock(DepositInterestChargeApplicationRepository.class);
        final SavingsAccountTransactionRepository transactionRepository = mock(SavingsAccountTransactionRepository.class);
        final SavingsAccountRepositoryWrapper savingsAccountRepository = mock(SavingsAccountRepositoryWrapper.class);
        final SavingsAccountTransactionSummaryWrapper summaryWrapper = mock(SavingsAccountTransactionSummaryWrapper.class);
        // Real helper (not a mock) so the fix's actual effect on the withdrawal's balance window is asserted.
        final SavingsAccountTransactionHelper transactionHelper = new SavingsAccountTransactionHelper(summaryWrapper);
        final JournalEntryWritePlatformService journalEntryWritePlatformService = mock(JournalEntryWritePlatformService.class);
        final CumulativeInterestForfeitureService cumulativeInterestForfeitureService = mock(CumulativeInterestForfeitureService.class);
        final JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        final AdvanclyInterestChargeApplicationService service = new AdvanclyInterestChargeApplicationService(ruleRepository,
                applicationRepository, transactionRepository, savingsAccountRepository, transactionHelper, journalEntryWritePlatformService,
                cumulativeInterestForfeitureService, jdbcTemplate);

        final Long accountId = 11L;
        final Long savingsProductId = 22L;
        final Long chargeId = 33L;
        final LocalDate transactionDate = LocalDate.of(2031, 4, 18);
        final LocalDate selectedFromDate = LocalDate.of(2031, 4, 1);
        final LocalDate selectedToDate = LocalDate.of(2031, 4, 15);
        final Office office = mock(Office.class);
        final SavingsAccount account = mock(SavingsAccount.class);
        final SavingsAccountTransaction withdrawalTransaction = SavingsAccountTransaction.withdrawal(account, office, null, transactionDate,
                Money.of(CURRENCY, new BigDecimal("25")), null);
        withdrawalTransaction.setRunningBalance(Money.of(CURRENCY, new BigDecimal("1000")));
        // Still open-ended, exactly as the O(1) append path leaves the trailing balance-bearing row before the
        // charge is appended after it.
        assertThat(withdrawalTransaction.getEndOfBalanceDate()).isNull();

        final SavingsAccountTransaction interestPosting = SavingsAccountTransaction.interestPosting(account, office,
                LocalDate.of(2031, 4, 10), Money.of(CURRENCY, new BigDecimal("250")), false);
        final SavingsAccountTransaction withholdingTax = SavingsAccountTransaction.withHoldTax(account, office, LocalDate.of(2031, 4, 10),
                Money.of(CURRENCY, new BigDecimal("50")), Map.of());
        final AdvanclyChargeInterestRule rule = mock(AdvanclyChargeInterestRule.class);
        final SavingsAccountCharge accountCharge = mock(SavingsAccountCharge.class);
        final Charge charge = mock(Charge.class);

        when(account.isEarlyWithdrawal(transactionDate)).thenReturn(true);
        when(account.getId()).thenReturn(accountId);
        when(account.productId()).thenReturn(savingsProductId);
        when(account.charges()).thenReturn(Set.of(accountCharge));
        when(account.getInterestPostingPeriodType()).thenReturn(SavingsPostingInterestPeriodType.DAILY.getValue());
        when(account.getTransactions()).thenReturn(List.of(interestPosting, withholdingTax));
        when(account.getCurrency()).thenReturn(CURRENCY);
        when(account.office()).thenReturn(office);
        when(account.findCurrentTransactionIdsWithPivotDateConfig()).thenReturn(Set.of());
        when(account.findCurrentReversedTransactionIdsWithPivotDateConfig()).thenReturn(Set.of());
        when(account.getSummary()).thenReturn(mock(org.apache.fineract.portfolio.savings.domain.SavingsAccountSummary.class));
        when(account.getSavingsAccountTransactionsWithPivotConfig()).thenReturn(List.of());
        when(ruleRepository.findBySavingsProductId(savingsProductId)).thenReturn(List.of(rule));
        when(rule.chargeId()).thenReturn(chargeId);
        when(rule.isCumulative()).thenReturn(false);
        when(rule.isCustomPeriod()).thenReturn(true);
        when(rule.interestBasisMode()).thenReturn(InterestBasisMode.CUSTOM_PERIOD);
        when(rule.customPeriodReapplyPolicy()).thenReturn(CustomPeriodReapplyPolicy.UNTIL_SELECTED_PERIOD_INTEREST_EXHAUSTED);
        when(accountCharge.getCharge()).thenReturn(charge);
        when(accountCharge.isActive()).thenReturn(true);
        when(accountCharge.isPenaltyCharge()).thenReturn(true);
        when(accountCharge.getPercentage()).thenReturn(new BigDecimal("50"));
        when(charge.getId()).thenReturn(chargeId);
        when(applicationRepository.sumActiveAppliedAmountForSelectedPeriod(accountId, chargeId, selectedFromDate, selectedToDate))
                .thenReturn(new BigDecimal("100"));
        when(applicationRepository.sumActiveAppliedAmountForAccount(accountId)).thenReturn(new BigDecimal("150"));

        final SavingsAccountTransaction chargeTransaction = service.applyIfApplicable(account, withdrawalTransaction, true, null,
                selectedFromDate, selectedToDate, false, false);

        assertThat(chargeTransaction).isNotNull();
        // The withdrawal's window is now closed (end date clamped to its own transaction date, contributing 0 days
        // since the charge transaction takes over the balance on that same day) instead of staying null/open-ended
        // and overlapping with the charge transaction that now follows it. This matches how core's own
        // resetAccountTransactionsEndOfDayBalances resolves same-day balance-bearing transactions.
        assertThat(withdrawalTransaction.getEndOfBalanceDate()).isEqualTo(transactionDate);
        assertThat(withdrawalTransaction.getBalanceNumberOfDays()).isEqualTo(0);
    }
}
