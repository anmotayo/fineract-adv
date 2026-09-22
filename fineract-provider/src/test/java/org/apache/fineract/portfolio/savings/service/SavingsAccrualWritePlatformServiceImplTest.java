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
package org.apache.fineract.portfolio.savings.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Set;
import org.apache.fineract.infrastructure.businessdate.domain.BusinessDateType;
import org.apache.fineract.infrastructure.configuration.domain.ConfigurationDomainService;
import org.apache.fineract.infrastructure.core.domain.ActionContext;
import org.apache.fineract.infrastructure.core.domain.FineractPlatformTenant;
import org.apache.fineract.infrastructure.core.domain.LocalDateInterval;
import org.apache.fineract.infrastructure.core.service.ThreadLocalContextUtil;
import org.apache.fineract.organisation.monetary.domain.MonetaryCurrency;
import org.apache.fineract.organisation.monetary.domain.Money;
import org.apache.fineract.organisation.monetary.domain.MoneyHelper;
import org.apache.fineract.organisation.office.domain.Office;
import org.apache.fineract.portfolio.savings.SavingsCompoundingInterestPeriodType;
import org.apache.fineract.portfolio.savings.SavingsInterestCalculationDaysInYearType;
import org.apache.fineract.portfolio.savings.SavingsInterestCalculationType;
import org.apache.fineract.portfolio.savings.SavingsPostingInterestPeriodType;
import org.apache.fineract.portfolio.savings.domain.SavingsAccount;
import org.apache.fineract.portfolio.savings.domain.SavingsAccountAssembler;
import org.apache.fineract.portfolio.savings.domain.SavingsAccountRepositoryWrapper;
import org.apache.fineract.portfolio.savings.domain.SavingsAccountSummary;
import org.apache.fineract.portfolio.savings.domain.SavingsAccountTransaction;
import org.apache.fineract.portfolio.savings.domain.SavingsHelper;
import org.apache.fineract.portfolio.savings.domain.interest.SavingsAccountTransactionDetailsForPostingPeriod;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;

class SavingsAccrualWritePlatformServiceImplTest {

    private static final String TEST_TENANT = "default";
    private static final MonetaryCurrency CURRENCY = new MonetaryCurrency("USD", 2, null);

    private SavingsAccountRepositoryWrapper savingsAccountRepository;
    private SavingsHelper savingsHelper;
    private SavingsAccountDomainService savingsAccountDomainService;
    private SavingsAccrualWritePlatformServiceImpl service;

    @BeforeEach
    void setUp() {
        ThreadLocalContextUtil
                .setTenant(FineractPlatformTenant.builder().id(1L).tenantIdentifier(TEST_TENANT).name("Default").timezoneId("UTC").build());
        MoneyHelper.initializeTenantRoundingMode(TEST_TENANT, RoundingMode.DOWN.ordinal());
        ThreadLocalContextUtil.setActionContext(ActionContext.DEFAULT);
        final HashMap<BusinessDateType, LocalDate> businessDates = new HashMap<>();
        businessDates.put(BusinessDateType.BUSINESS_DATE, LocalDate.of(2026, 6, 30));
        businessDates.put(BusinessDateType.COB_DATE, LocalDate.of(2026, 6, 29));
        ThreadLocalContextUtil.setBusinessDates(businessDates);

        this.savingsAccountRepository = mock(SavingsAccountRepositoryWrapper.class);
        this.savingsHelper = mock(SavingsHelper.class);
        this.savingsAccountDomainService = mock(SavingsAccountDomainService.class);
        this.service = new SavingsAccrualWritePlatformServiceImpl(mock(SavingsAccountReadPlatformService.class),
                mock(SavingsAccountAssembler.class), this.savingsAccountRepository, this.savingsHelper,
                mock(ConfigurationDomainService.class), this.savingsAccountDomainService);
    }

    @AfterEach
    void tearDown() {
        ThreadLocalContextUtil.reset();
        MoneyHelper.clearCacheForTenant(TEST_TENANT);
    }

    @Test
    void periodStartingBalanceUsesPivotBalanceWhenPeriodStartsOnStartInterestCalculationDate() {
        final LocalDate fromDate = LocalDate.of(2031, 4, 1);
        final SavingsAccount account = account(fromDate, new BigDecimal("300"));

        final Money periodStartingBalance = determinePeriodStartingBalance(account, fromDate);

        assertThat(periodStartingBalance.getAmount()).isEqualByComparingTo("300");
        verifyNoInteractions(this.savingsAccountRepository);
    }

    @Test
    void periodStartingBalanceUsesLatestNonInterestContributingBalanceBeforeFromDate() {
        final LocalDate fromDate = LocalDate.of(2031, 4, 16);
        final SavingsAccount account = account(null, null);
        final SavingsAccountTransaction previousTransaction = mock(SavingsAccountTransaction.class);
        when(previousTransaction.getRunningBalance(CURRENCY)).thenReturn(Money.of(CURRENCY, new BigDecimal("500")));
        when(this.savingsAccountRepository.findTransactionsBeforePivotDate(eq(1L), eq(fromDate), any(Pageable.class)))
                .thenReturn(List.of(previousTransaction));

        final Money periodStartingBalance = determinePeriodStartingBalance(account, fromDate);

        assertThat(periodStartingBalance.getAmount()).isEqualByComparingTo("500");
        verify(this.savingsAccountRepository).findTransactionsBeforePivotDate(eq(1L), eq(fromDate), any(Pageable.class));
    }

    @Test
    void periodStartingBalanceIgnoresStartInterestPivotAfterFirstAccrualRun() {
        final LocalDate startInterestCalculationDate = LocalDate.of(2031, 4, 1);
        final LocalDate fromDate = LocalDate.of(2031, 4, 16);
        final SavingsAccount account = account(startInterestCalculationDate, new BigDecimal("300"));
        final SavingsAccountTransaction previousTransaction = mock(SavingsAccountTransaction.class);
        when(previousTransaction.getRunningBalance(CURRENCY)).thenReturn(Money.of(CURRENCY, new BigDecimal("500")));
        when(this.savingsAccountRepository.findTransactionsBeforePivotDate(eq(1L), eq(fromDate), any(Pageable.class)))
                .thenReturn(List.of(previousTransaction));

        final Money periodStartingBalance = determinePeriodStartingBalance(account, fromDate);

        assertThat(periodStartingBalance.getAmount()).isEqualByComparingTo("500");
    }

    @Test
    void juneAccrualUsesOpeningBalanceAndTransactionsBeforePostingDate() {
        final LocalDate startInterestCalculationDate = LocalDate.of(2026, 6, 1);
        final LocalDate tillDate = LocalDate.of(2026, 6, 30);
        final LocalDate accrualThroughDate = tillDate.minusDays(1);
        final SavingsAccount account = account(startInterestCalculationDate, new BigDecimal("88000"));
        final Office office = mock(Office.class);
        final List<SavingsAccountTransactionDetailsForPostingPeriod> transactions = List.of(
                deposit(1L, LocalDate.of(2026, 6, 2), LocalDate.of(2026, 6, 3), "14000", "102000", 2),
                deposit(2L, LocalDate.of(2026, 6, 4), LocalDate.of(2026, 6, 13), "15100", "117100", 10),
                deposit(3L, LocalDate.of(2026, 6, 14), LocalDate.of(2026, 6, 18), "25100", "142200", 5),
                withdrawal(4L, LocalDate.of(2026, 6, 19), LocalDate.of(2026, 6, 27), "25000", "117200", 9),
                deposit(5L, LocalDate.of(2026, 6, 28), null, "87230", "204430", null));
        when(account.findExistingTransactionIds()).thenReturn(Set.of());
        when(account.findExistingReversedTransactionIds()).thenReturn(Set.of());
        when(account.getManualPostingDates()).thenReturn(List.of());
        when(account.getInterestCalculationType()).thenReturn(SavingsInterestCalculationType.DAILY_BALANCE.getValue());
        when(account.getInterestCompoundingPeriodType())
                .thenReturn(SavingsCompoundingInterestPeriodType.NO_COMPOUNDING_SIMPLE_INTEREST.getValue());
        when(account.getInterestCalculationDaysInYearType()).thenReturn(SavingsInterestCalculationDaysInYearType.DAYS_365.getValue());
        when(account.getCurrency()).thenReturn(CURRENCY);
        when(account.getEffectiveInterestRateAsFractionAccrual(any(MathContext.class), eq(tillDate))).thenReturn(new BigDecimal("0.08"));
        when(account.getMinBalanceForInterestCalculation()).thenReturn(BigDecimal.ZERO);
        when(account.toSavingsAccountTransactionDetailsForPostingPeriodList()).thenReturn(transactions);
        when(account.getNominalAnnualInterestRateOverdraft()).thenReturn(BigDecimal.ZERO);
        when(account.retrieveOrderedAccrualTransactions()).thenReturn(List.of());
        when(account.office()).thenReturn(office);
        when(this.savingsHelper.fetchPostInterestTransactionIds(1L)).thenReturn(Set.of());
        when(this.savingsHelper.determineInterestPostingPeriods(eq(startInterestCalculationDate), eq(tillDate),
                eq(SavingsPostingInterestPeriodType.DAILY), eq(1), any()))
                .thenReturn(List.of(LocalDateInterval.create(startInterestCalculationDate, accrualThroughDate)));

        ReflectionTestUtils.invokeMethod(this.service, "addAccrualTransactions", account, startInterestCalculationDate, tillDate, 1, false,
                MoneyHelper.getMathContext(), null);

        final ArgumentCaptor<SavingsAccountTransaction> accrualCaptor = ArgumentCaptor.forClass(SavingsAccountTransaction.class);
        verify(account).addTransaction(accrualCaptor.capture());
        final BigDecimal totalAccrual = accrualCaptor.getAllValues().stream().map(SavingsAccountTransaction::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        // Unrounded daily-balance interest is 797.295342466; this tenant truncates monetary amounts to currency scale.
        assertThat(totalAccrual).isEqualByComparingTo("797.29");
        assertThat(accrualCaptor.getValue().getTransactionDate()).isEqualTo(accrualThroughDate);
        verify(account).setAccruedTillDate(accrualThroughDate);
        verify(this.savingsAccountRepository).saveAndFlush(account);
        verify(this.savingsAccountDomainService).postJournalEntries(eq(account), eq(Set.of()), eq(Set.of()), eq(false));
    }

    private Money determinePeriodStartingBalance(final SavingsAccount account, final LocalDate fromDate) {
        return ReflectionTestUtils.invokeMethod(this.service, "determinePeriodStartingBalance", account, CURRENCY, fromDate);
    }

    private SavingsAccount account(final LocalDate startInterestCalculationDate, final BigDecimal runningBalanceOnPivotDate) {
        final SavingsAccount account = mock(SavingsAccount.class);
        final SavingsAccountSummary summary = mock(SavingsAccountSummary.class);
        when(account.getId()).thenReturn(1L);
        when(account.getStartInterestCalculationDate()).thenReturn(startInterestCalculationDate);
        when(account.getSummary()).thenReturn(summary);
        when(summary.getRunningBalanceOnPivotDate()).thenReturn(runningBalanceOnPivotDate);
        return account;
    }

    private SavingsAccountTransactionDetailsForPostingPeriod deposit(final Long id, final LocalDate transactionDate,
            final LocalDate endOfBalanceDate, final String amount, final String runningBalance, final Integer balanceNumberOfDays) {
        return transaction(id, transactionDate, endOfBalanceDate, amount, runningBalance, balanceNumberOfDays, true, false);
    }

    private SavingsAccountTransactionDetailsForPostingPeriod withdrawal(final Long id, final LocalDate transactionDate,
            final LocalDate endOfBalanceDate, final String amount, final String runningBalance, final Integer balanceNumberOfDays) {
        return transaction(id, transactionDate, endOfBalanceDate, amount, runningBalance, balanceNumberOfDays, false, true);
    }

    private SavingsAccountTransactionDetailsForPostingPeriod transaction(final Long id, final LocalDate transactionDate,
            final LocalDate endOfBalanceDate, final String amount, final String runningBalance, final Integer balanceNumberOfDays,
            final boolean isDeposit, final boolean isWithdrawal) {
        return new SavingsAccountTransactionDetailsForPostingPeriod(id, transactionDate, endOfBalanceDate, new BigDecimal(runningBalance),
                new BigDecimal(amount), CURRENCY, balanceNumberOfDays, isDeposit, isWithdrawal, false, false, false, false, false);
    }
}
