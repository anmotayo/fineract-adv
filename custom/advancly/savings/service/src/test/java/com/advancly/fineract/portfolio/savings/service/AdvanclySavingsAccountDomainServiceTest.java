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

import com.advancly.fineract.portfolio.savings.helper.SavingsAccountTransactionHelper;
import com.advancly.fineract.portfolio.savings.testutil.MoneyHelperInitializer;
import com.advancly.fineract.portfolio.savings.testutil.SavingsAccountSummaryTestBuilder;
import com.advancly.fineract.portfolio.savings.testutil.SavingsAccountTestBuilder;
import java.math.BigDecimal;
import java.time.LocalDate;
import org.apache.fineract.accounting.journalentry.service.JournalEntryWritePlatformService;
import org.apache.fineract.infrastructure.event.business.service.BusinessEventNotifierService;
import org.apache.fineract.organisation.monetary.domain.MonetaryCurrency;
import org.apache.fineract.organisation.monetary.domain.Money;
import org.apache.fineract.portfolio.savings.domain.SavingsAccount;
import org.apache.fineract.portfolio.savings.domain.SavingsAccountRepositoryWrapper;
import org.apache.fineract.portfolio.savings.domain.SavingsAccountSummary;
import org.apache.fineract.portfolio.savings.domain.SavingsAccountTransaction;
import org.apache.fineract.portfolio.savings.domain.SavingsAccountTransactionRepository;
import org.apache.fineract.portfolio.savings.exception.InsufficientAccountBalanceException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AdvanclySavingsAccountDomainServiceTest {

    @Mock
    private SavingsAccountRepositoryWrapper savingsAccountRepository;
    @Mock
    private SavingsAccountTransactionRepository savingsAccountTransactionRepository;
    @Mock
    private BusinessEventNotifierService businessEventNotifierService;
    @Mock
    private SavingsAccountTransactionHelper transactionHelper;
    @Mock
    private org.apache.fineract.portfolio.savings.domain.SavingsAccountDomainServiceJpa coreDomainService;
    @Mock
    private JournalEntryWritePlatformService journalEntryWritePlatformService;

    private AdvanclySavingsAccountDomainService domainService;
    private MonetaryCurrency currency;

    @BeforeEach
    void setUp() {
        MoneyHelperInitializer.initialize();
        currency = new MonetaryCurrency("USD", 2, null);
        domainService = new AdvanclySavingsAccountDomainService(savingsAccountRepository, savingsAccountTransactionRepository,
                businessEventNotifierService, transactionHelper, coreDomainService, journalEntryWritePlatformService);
    }

    @Test
    void testHandleDeposit_appendPath_setsRunningBalanceAndSummary() {
        LocalDate today = LocalDate.now();
        SavingsAccountSummary summary = new SavingsAccountSummaryTestBuilder().withAccountBalance(BigDecimal.valueOf(1000))
                .withTotalDeposits(BigDecimal.valueOf(1000)).withRunningBalanceOnPivotDate(BigDecimal.valueOf(1000)).build();
        SavingsAccount account = new SavingsAccountTestBuilder().withId(1L).withSummary(summary).build();

        SavingsAccountTransaction deposit = domainService.handleDepositOptimized(account, today, BigDecimal.valueOf(500), null,
                Money.of(currency, BigDecimal.valueOf(1000)), currency, null, false);

        assertThat(deposit).isNotNull();
        verify(transactionHelper).updatePreviousTransactionBalanceEndDate(any(), eq(today), eq(currency));
        verify(transactionHelper).setRunningBalanceForAppendPath(any(), any(), eq(currency));
        verify(transactionHelper).updateSummaryIncremental(eq(account), any(), eq(currency));
    }

    @Test
    void testHandleWithdrawal_appendPath_insufficientBalance() {
        LocalDate today = LocalDate.now();
        SavingsAccountSummary summary = new SavingsAccountSummaryTestBuilder().withAccountBalance(BigDecimal.valueOf(100))
                .withRunningBalanceOnPivotDate(BigDecimal.valueOf(100)).build();
        SavingsAccount account = new SavingsAccountTestBuilder().withId(1L).withSummary(summary).build();

        doThrow(new InsufficientAccountBalanceException("transactionAmount", BigDecimal.valueOf(100), null, BigDecimal.valueOf(500)))
                .when(transactionHelper).validateBalanceForAppendPath(eq(account), eq(BigDecimal.valueOf(500)), eq(currency));

        assertThatThrownBy(() -> domainService.handleWithdrawalOptimized(account, today, BigDecimal.valueOf(500), null, false,
                Money.of(currency, BigDecimal.valueOf(100)), currency, null, false))
                .isInstanceOf(InsufficientAccountBalanceException.class);
    }

    @Test
    void testHandleWithdrawal_appendPath_success() {
        LocalDate today = LocalDate.now();
        SavingsAccountSummary summary = new SavingsAccountSummaryTestBuilder().withAccountBalance(BigDecimal.valueOf(1000))
                .withRunningBalanceOnPivotDate(BigDecimal.valueOf(1000)).build();
        SavingsAccount account = new SavingsAccountTestBuilder().withId(1L).withSummary(summary).build();

        SavingsAccountTransaction withdrawal = domainService.handleWithdrawalOptimized(account, today, BigDecimal.valueOf(200), null, true,
                Money.of(currency, BigDecimal.valueOf(1000)), currency, null, false);

        assertThat(withdrawal).isNotNull();
        verify(transactionHelper).validateBalanceForAppendPath(eq(account), eq(BigDecimal.valueOf(200)), eq(currency));
        verify(transactionHelper).updatePreviousTransactionBalanceEndDate(any(), eq(today), eq(currency));
        verify(transactionHelper).setRunningBalanceForAppendPath(any(), any(), eq(currency));
        verify(transactionHelper).updateSummaryIncremental(eq(account), any(), eq(currency));
    }
}
