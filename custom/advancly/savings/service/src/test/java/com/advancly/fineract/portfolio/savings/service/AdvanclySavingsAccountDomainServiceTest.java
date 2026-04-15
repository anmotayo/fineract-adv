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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.advancly.fineract.portfolio.savings.domain.AdvanclySavingsAccountTransactionRepository;
import com.advancly.fineract.portfolio.savings.helper.SavingsAccountTransactionHelper;
import com.advancly.fineract.portfolio.savings.testutil.MoneyHelperInitializer;
import com.advancly.fineract.portfolio.savings.testutil.SavingsAccountSummaryTestBuilder;
import com.advancly.fineract.portfolio.savings.testutil.SavingsAccountTestBuilder;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Optional;
import org.apache.fineract.infrastructure.configuration.domain.ConfigurationDomainService;
import org.apache.fineract.infrastructure.event.business.service.BusinessEventNotifierService;
import org.apache.fineract.infrastructure.security.service.PlatformSecurityContext;
import org.apache.fineract.organisation.monetary.domain.MonetaryCurrency;
import org.apache.fineract.organisation.monetary.domain.Money;
import org.apache.fineract.portfolio.savings.domain.DepositAccountOnHoldTransactionRepository;
import org.apache.fineract.portfolio.savings.domain.SavingsAccount;
import org.apache.fineract.portfolio.savings.domain.SavingsAccountRepositoryWrapper;
import org.apache.fineract.portfolio.savings.domain.SavingsAccountSummary;
import org.apache.fineract.portfolio.savings.domain.SavingsAccountTransaction;
import org.apache.fineract.portfolio.savings.domain.SavingsAccountTransactionRepository;
import org.apache.fineract.portfolio.savings.domain.SavingsAccountTransactionSummaryWrapper;
import org.apache.fineract.portfolio.savings.domain.SavingsHelper;
import org.apache.fineract.portfolio.savings.exception.InsufficientAccountBalanceException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AdvanclySavingsAccountDomainServiceTest {

    @Mock
    private PlatformSecurityContext context;
    @Mock
    private SavingsAccountRepositoryWrapper savingsAccountRepository;
    @Mock
    private SavingsAccountTransactionRepository savingsAccountTransactionRepository;
    @Mock
    private ConfigurationDomainService configurationDomainService;
    @Mock
    private DepositAccountOnHoldTransactionRepository depositAccountOnHoldTransactionRepository;
    @Mock
    private BusinessEventNotifierService businessEventNotifierService;
    @Mock
    private SavingsAccountTransactionSummaryWrapper summaryWrapper;
    @Mock
    private SavingsHelper savingsHelper;
    @Mock
    private SavingsAccountTransactionHelper transactionHelper;
    @Mock
    private AdvanclySavingsAccountTransactionRepository advanclyTransactionRepository;
    @Mock
    private org.apache.fineract.portfolio.savings.domain.SavingsAccountDomainServiceJpa coreDomainService;

    private AdvanclySavingsAccountDomainService domainService;
    private MonetaryCurrency currency;

    @BeforeEach
    void setUp() {
        MoneyHelperInitializer.initialize();
        currency = new MonetaryCurrency("USD", 2, null);
        domainService = new AdvanclySavingsAccountDomainService(context, savingsAccountRepository, savingsAccountTransactionRepository,
                configurationDomainService, depositAccountOnHoldTransactionRepository, businessEventNotifierService, summaryWrapper,
                savingsHelper, transactionHelper, advanclyTransactionRepository, coreDomainService);
    }

    @Test
    void testHandleDeposit_appendPath_zeroInterest() {
        LocalDate today = LocalDate.now();
        SavingsAccountSummary summary = new SavingsAccountSummaryTestBuilder().withAccountBalance(BigDecimal.valueOf(1000))
                .withTotalDeposits(BigDecimal.valueOf(1000)).withRunningBalanceOnPivotDate(BigDecimal.valueOf(1000)).build();
        SavingsAccount account = new SavingsAccountTestBuilder().withId(1L).withSummary(summary).build();

        // Last transaction date is yesterday -> today is append path
        when(advanclyTransactionRepository.findLastTransactionDate(1L)).thenReturn(Optional.of(today.minusDays(1)));

        SavingsAccountTransaction deposit = domainService.handleDepositOptimized(account, today, BigDecimal.valueOf(500), null,
                new ArrayList<>(), Money.of(currency, BigDecimal.valueOf(1000)), currency, null);

        assertThat(deposit).isNotNull();
        verify(transactionHelper).updatePreviousTransactionBalanceEndDate(any(), eq(today), eq(currency));
        verify(transactionHelper).updateSummaryIncremental(eq(account), any(), eq(currency));
        verify(transactionHelper).setRunningBalanceForAppendPath(any(), any(), eq(currency));
    }

    @Test
    void testHandleWithdrawal_appendPath_insufficientBalance() {
        LocalDate today = LocalDate.now();
        SavingsAccountSummary summary = new SavingsAccountSummaryTestBuilder().withAccountBalance(BigDecimal.valueOf(100))
                .withRunningBalanceOnPivotDate(BigDecimal.valueOf(100)).build();
        SavingsAccount account = new SavingsAccountTestBuilder().withId(1L).withSummary(summary).build();

        when(advanclyTransactionRepository.findLastTransactionDate(1L)).thenReturn(Optional.of(today.minusDays(1)));

        doThrow(new InsufficientAccountBalanceException("transactionAmount", BigDecimal.valueOf(100), null, BigDecimal.valueOf(500)))
                .when(transactionHelper).validateBalanceForAppendPath(eq(account), eq(BigDecimal.valueOf(500)), eq(currency));

        assertThatThrownBy(() -> domainService.handleWithdrawalOptimized(account, today, BigDecimal.valueOf(500), null, true,
                new ArrayList<>(), Money.of(currency, BigDecimal.valueOf(100)), currency, null))
                .isInstanceOf(InsufficientAccountBalanceException.class);
    }

    @Test
    void testHandleDeposit_insertPath_afterInterestPosting() {
        LocalDate backdatedDate = LocalDate.of(2025, 7, 15);
        SavingsAccountSummary summary = new SavingsAccountSummaryTestBuilder().withAccountBalance(BigDecimal.valueOf(1000))
                .withRunningBalanceOnPivotDate(BigDecimal.valueOf(800)).build();
        SavingsAccount account = new SavingsAccountTestBuilder().withId(1L).withSummary(summary).build();

        // Last transaction date is today -> backdated date triggers insert path
        when(advanclyTransactionRepository.findLastTransactionDate(1L)).thenReturn(Optional.of(LocalDate.now()));
        when(transactionHelper.isBeforeLastPostingPeriod(eq(backdatedDate), any())).thenReturn(false);

        SavingsAccountTransaction deposit = domainService.handleDepositOptimized(account, backdatedDate, BigDecimal.valueOf(200), null,
                new ArrayList<>(), Money.of(currency, BigDecimal.valueOf(800)), currency, null);

        assertThat(deposit).isNotNull();
        // Should recalculate running balances from date
        verify(transactionHelper).recalculateDailyBalancesFromDate(any(), any(), eq(currency));
    }

    @Test
    void testHandleDeposit_insertPath_beforeInterestPosting_callsPostInterest() {
        LocalDate backdatedDate = LocalDate.of(2025, 3, 15);
        SavingsAccountSummary summary = new SavingsAccountSummaryTestBuilder().withAccountBalance(BigDecimal.valueOf(1000))
                .withRunningBalanceOnPivotDate(BigDecimal.valueOf(800)).build();
        SavingsAccount account = new SavingsAccountTestBuilder().withId(1L).withSummary(summary).withInterestRate(BigDecimal.valueOf(5))
                .build();
        account.setHelpers(summaryWrapper, savingsHelper);

        when(advanclyTransactionRepository.findLastTransactionDate(1L)).thenReturn(Optional.of(LocalDate.now()));
        when(transactionHelper.isBeforeLastPostingPeriod(eq(backdatedDate), any())).thenReturn(true);
        when(configurationDomainService.isSavingsInterestPostingAtCurrentPeriodEnd()).thenReturn(false);
        when(configurationDomainService.retrieveFinancialYearBeginningMonth()).thenReturn(1);
        when(configurationDomainService.isReversalTransactionAllowed()).thenReturn(false);

        domainService.handleDepositOptimized(account, backdatedDate, BigDecimal.valueOf(200), null, new ArrayList<>(),
                Money.of(currency, BigDecimal.valueOf(800)), currency, null);

        verify(configurationDomainService).isSavingsInterestPostingAtCurrentPeriodEnd();
        verify(configurationDomainService).retrieveFinancialYearBeginningMonth();
        verify(configurationDomainService).isReversalTransactionAllowed();
    }

    @Test
    void testHandleWithdrawal_insertPath_beforeInterestPosting_callsPostInterest() {
        LocalDate backdatedDate = LocalDate.of(2025, 3, 15);
        SavingsAccountSummary summary = new SavingsAccountSummaryTestBuilder().withAccountBalance(BigDecimal.valueOf(1000))
                .withRunningBalanceOnPivotDate(BigDecimal.valueOf(800)).build();
        SavingsAccount account = new SavingsAccountTestBuilder().withId(1L).withSummary(summary).withInterestRate(BigDecimal.valueOf(5))
                .build();
        account.setHelpers(summaryWrapper, savingsHelper);

        when(advanclyTransactionRepository.findLastTransactionDate(1L)).thenReturn(Optional.of(LocalDate.now()));
        when(transactionHelper.isBeforeLastPostingPeriod(eq(backdatedDate), any())).thenReturn(true);
        when(configurationDomainService.isSavingsInterestPostingAtCurrentPeriodEnd()).thenReturn(false);
        when(configurationDomainService.retrieveFinancialYearBeginningMonth()).thenReturn(1);
        when(configurationDomainService.isReversalTransactionAllowed()).thenReturn(false);

        domainService.handleWithdrawalOptimized(account, backdatedDate, BigDecimal.valueOf(200), null, false, new ArrayList<>(),
                Money.of(currency, BigDecimal.valueOf(800)), currency, null);

        verify(configurationDomainService).isSavingsInterestPostingAtCurrentPeriodEnd();
        verify(configurationDomainService).retrieveFinancialYearBeginningMonth();
        verify(configurationDomainService).isReversalTransactionAllowed();
    }
}
