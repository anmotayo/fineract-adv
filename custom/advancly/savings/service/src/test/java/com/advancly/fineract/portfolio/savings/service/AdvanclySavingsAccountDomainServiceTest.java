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
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.advancly.fineract.portfolio.savings.domain.SavingsAccountInterestChargeRepository;
import com.advancly.fineract.portfolio.savings.domain.DynamicDepositAccount;
import com.advancly.fineract.portfolio.savings.helper.SavingsAccountTransactionHelper;
import com.advancly.fineract.portfolio.savings.testutil.MoneyHelperInitializer;
import com.advancly.fineract.portfolio.savings.testutil.SavingsAccountSummaryTestBuilder;
import com.advancly.fineract.portfolio.savings.testutil.SavingsAccountTestBuilder;
import java.lang.reflect.Constructor;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import org.apache.fineract.accounting.journalentry.service.JournalEntryWritePlatformService;
import org.apache.fineract.infrastructure.event.business.service.BusinessEventNotifierService;
import org.apache.fineract.organisation.monetary.domain.MonetaryCurrency;
import org.apache.fineract.organisation.monetary.domain.Money;
import org.apache.fineract.organisation.office.domain.Office;
import org.apache.fineract.portfolio.client.domain.Client;
import org.apache.fineract.portfolio.savings.domain.SavingsAccount;
import org.apache.fineract.portfolio.savings.domain.SavingsAccountRepositoryWrapper;
import org.apache.fineract.portfolio.savings.domain.SavingsAccountSummary;
import org.apache.fineract.portfolio.savings.domain.SavingsAccountTransaction;
import org.apache.fineract.portfolio.savings.domain.SavingsAccountTransactionRepository;
import org.apache.fineract.portfolio.savings.domain.SavingsProduct;
import org.apache.fineract.portfolio.savings.exception.InsufficientAccountBalanceException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

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
    @Mock
    private DynamicDepositRateHistoryService dynamicDepositRateHistoryService;
    @Mock
    private SavingsAccountInterestChargeRepository interestChargeRepository;

    private AdvanclySavingsAccountDomainService domainService;
    private MonetaryCurrency currency;

    @BeforeEach
    void setUp() {
        MoneyHelperInitializer.initialize();
        currency = new MonetaryCurrency("USD", 2, null);
        domainService = new AdvanclySavingsAccountDomainService(savingsAccountRepository, savingsAccountTransactionRepository,
                businessEventNotifierService, transactionHelper, coreDomainService, journalEntryWritePlatformService,
                dynamicDepositRateHistoryService, interestChargeRepository);
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

    // Fast-path (handleDepositOptimized/handleWithdrawalOptimized) DynamicDepositAccount-specific hooks were removed:
    // AdvanclySavingsAccountWritePlatformService now routes every deposit-type account (including Dynamic Deposit)
    // through the core path instead, so these methods are only ever called with a plain savings account and no
    // longer special-case DynamicDepositAccount. See AdvanclySavingsAccountWritePlatformServiceTest for the routing
    // coverage.

    @Test
    void aReversalOnADynamicDepositAccountRefreshesTheDerivedInterestBasedChargeColumn() {
        final SavingsAccountSummary summary = new SavingsAccountSummaryTestBuilder().withAccountBalance(BigDecimal.valueOf(1000))
                .withRunningBalanceOnPivotDate(BigDecimal.valueOf(1000)).build();
        final DynamicDepositAccount dynamicDepositAccount = buildDynamicDepositAccount(summary);
        // Simulates the stale state Task 7's review found: a previous early-withdrawal charge posting left the
        // fast-read derived column non-zero, and the withdrawal/charge that produced it is now being reversed.
        dynamicDepositAccount.updateInterestBasedChargeDerived(BigDecimal.valueOf(12));

        final SavingsAccountTransaction originalWithdrawal = mock(SavingsAccountTransaction.class);
        final SavingsAccountTransaction reversalTransaction = mock(SavingsAccountTransaction.class);
        lenient().when(coreDomainService.handleReversal(eq(dynamicDepositAccount),
                eq(java.util.Collections.singletonList(originalWithdrawal)), eq(false))).thenReturn(reversalTransaction);

        // m_savings_account_interest_charge itself is already correct post-reversal (its queries exclude rows linked
        // to a reversed transaction) - the repository stub below simulates what it now reports.
        lenient().when(interestChargeRepository.sumPendingChargeAmount(1L)).thenReturn(BigDecimal.ZERO);

        domainService.handleReversal(dynamicDepositAccount, java.util.Collections.singletonList(originalWithdrawal), false);

        assertThat(dynamicDepositAccount.interestBasedChargeDerived()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    /**
     * Mirrors {@link SavingsAccountTestBuilder#build()}, but for the {@link DynamicDepositAccount} subclass: that
     * builder is hard-coded to {@code SavingsAccount.class}, so a {@code DynamicDepositAccount} fixture (needed here
     * for the reversal test's {@code instanceof DynamicDepositAccount} branch) is built the same way, by reflection,
     * directly in this test.
     */
    private DynamicDepositAccount buildDynamicDepositAccount(final SavingsAccountSummary summary) {
        final DynamicDepositAccount account = createInstance(DynamicDepositAccount.class);
        ReflectionTestUtils.setField(account, "id", 1L);
        ReflectionTestUtils.setField(account, "currency", currency);
        ReflectionTestUtils.setField(account, "summary", summary);
        ReflectionTestUtils.setField(account, "savingsAccountTransactions", new ArrayList<SavingsAccountTransaction>());

        final Office office = mock(Office.class);
        lenient().when(office.getId()).thenReturn(1L);
        final Client client = mock(Client.class);
        lenient().when(client.getOffice()).thenReturn(office);
        lenient().when(client.officeId()).thenReturn(1L);
        lenient().when(client.getId()).thenReturn(1L);
        ReflectionTestUtils.setField(account, "client", client);

        final SavingsProduct product = mock(SavingsProduct.class);
        lenient().when(product.getId()).thenReturn(1L);
        ReflectionTestUtils.setField(account, "product", product);

        return account;
    }

    private static <T> T createInstance(final Class<T> clazz) {
        try {
            final Constructor<T> constructor = clazz.getDeclaredConstructor();
            constructor.setAccessible(true);
            return constructor.newInstance();
        } catch (final Exception e) {
            throw new RuntimeException("Failed to create instance of " + clazz.getName(), e);
        }
    }
}
