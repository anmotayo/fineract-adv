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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.advancly.fineract.portfolio.savings.data.BulkTransactionDataValidator;
import com.advancly.fineract.portfolio.savings.domain.AdvanclySavingsAccountAssembler;
import com.advancly.fineract.portfolio.savings.domain.AdvanclySavingsAccountTransactionRepository;
import com.advancly.fineract.portfolio.savings.domain.AssembledSavingsAccount;
import com.advancly.fineract.portfolio.savings.domain.DepositAccountDynamicDetail;
import com.advancly.fineract.portfolio.savings.domain.DynamicDepositAccount;
import com.advancly.fineract.portfolio.savings.domain.SavingsProductEarlyWithdrawalChargeRepository;
import com.advancly.fineract.portfolio.savings.testutil.MoneyHelperInitializer;
import com.advancly.fineract.portfolio.savings.testutil.SavingsAccountSummaryTestBuilder;
import com.advancly.fineract.portfolio.savings.testutil.SavingsAccountTestBuilder;
import com.advancly.fineract.portfolio.savings.testutil.SavingsAccountTransactionTestBuilder;
import java.lang.reflect.Constructor;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;
import org.apache.fineract.infrastructure.core.api.JsonCommand;
import org.apache.fineract.infrastructure.core.data.CommandProcessingResult;
import org.apache.fineract.infrastructure.core.data.CommandProcessingResultBuilder;
import org.apache.fineract.infrastructure.core.exception.GeneralPlatformDomainRuleException;
import org.apache.fineract.infrastructure.core.serialization.FromJsonHelper;
import org.apache.fineract.infrastructure.security.service.PlatformSecurityContext;
import org.apache.fineract.organisation.monetary.domain.MonetaryCurrency;
import org.apache.fineract.organisation.monetary.domain.Money;
import org.apache.fineract.portfolio.note.domain.NoteRepository;
import org.apache.fineract.portfolio.paymentdetail.domain.PaymentDetail;
import org.apache.fineract.portfolio.paymentdetail.domain.PaymentDetailRepository;
import org.apache.fineract.portfolio.paymentdetail.service.PaymentDetailWritePlatformService;
import org.apache.fineract.portfolio.paymenttype.domain.PaymentTypeRepositoryWrapper;
import org.apache.fineract.portfolio.savings.data.SavingsAccountTransactionDataValidator;
import org.apache.fineract.portfolio.savings.domain.GSIMRepositoy;
import org.apache.fineract.portfolio.savings.domain.SavingsAccount;
import org.apache.fineract.portfolio.savings.domain.SavingsAccountTransaction;
import org.apache.fineract.portfolio.savings.service.SavingsAccountWritePlatformServiceJpaRepositoryImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Direct coverage for the withdrawal-lock guard added to {@link AdvanclySavingsAccountWritePlatformService#withdrawal}
 * (Phase 5 Task 2). Mirrors the mocking pattern already used by
 * {@link AdvanclySavingsAccountWritePlatformServiceBulkTest} for this same class.
 *
 * Covers both the same-day (append-path) and BACKDATED routes through {@code withdrawal(...)}: the guard was originally
 * placed AFTER the backdated early-return to {@code delegate.withdrawal(...)}, which let a backdated cash withdrawal on
 * a locked Dynamic Deposit account bypass the rule entirely (core Fineract has no notion of it). The backdated test
 * below is the regression test for that fix - it fails without the fix (the exception is never thrown,
 * {@code delegate.withdrawal(...)} is invoked instead) and passes with it.
 */
@ExtendWith(MockitoExtension.class)
class AdvanclySavingsAccountWritePlatformServiceWithdrawalLockTest {

    @Mock
    private PlatformSecurityContext context;
    @Mock
    private SavingsAccountTransactionDataValidator savingsAccountTransactionDataValidator;
    @Mock
    private AdvanclySavingsAccountAssembler assembler;
    @Mock
    private AdvanclySavingsAccountDomainService domainService;
    @Mock
    private AdvanclySavingsAccountTransactionRepository advanclyTransactionRepository;
    @Mock
    private PaymentDetailWritePlatformService paymentDetailWritePlatformService;
    @Mock
    private NoteRepository noteRepository;
    @Mock
    private GSIMRepositoy gsimRepository;
    @Mock
    private SavingsAccountWritePlatformServiceJpaRepositoryImpl delegate;
    @Mock
    private PaymentTypeRepositoryWrapper paymentTypeRepositoryWrapper;
    @Mock
    private PaymentDetailRepository paymentDetailRepository;
    @Mock
    private SavingsProductEarlyWithdrawalChargeRepository productEarlyWithdrawalChargeRepository;
    @Mock
    private CumulativeInterestForfeitureService cumulativeInterestForfeitureService;
    @Mock
    private DynamicDepositEarlyWithdrawalChargeService earlyWithdrawalChargeService;

    private AdvanclySavingsAccountWritePlatformService service;
    private FromJsonHelper fromJsonHelper;
    private BulkTransactionDataValidator bulkValidator;

    @BeforeEach
    void setUp() {
        MoneyHelperInitializer.initialize();
        fromJsonHelper = new FromJsonHelper();
        bulkValidator = new BulkTransactionDataValidator(fromJsonHelper);
        service = new AdvanclySavingsAccountWritePlatformService(context, savingsAccountTransactionDataValidator, assembler, domainService,
                advanclyTransactionRepository, paymentDetailWritePlatformService, noteRepository, gsimRepository, delegate, bulkValidator,
                fromJsonHelper, paymentTypeRepositoryWrapper, paymentDetailRepository, productEarlyWithdrawalChargeRepository,
                cumulativeInterestForfeitureService, earlyWithdrawalChargeService);
    }

    @Test
    void sameDayWithdrawalBlockedForDynamicDepositWithAllowWithdrawalFalse() {
        Long savingsId = 10L;
        LocalDate transactionDate = LocalDate.of(2026, 5, 27);
        DynamicDepositAccount account = dynamicDepositAccount(savingsId, BigDecimal.valueOf(5000), false);
        AssembledSavingsAccount assembled = AssembledSavingsAccount.of(account, null);

        JsonCommand command = withdrawalCommand(transactionDate, BigDecimal.valueOf(1000));

        when(advanclyTransactionRepository.findLastTransactionDate(savingsId)).thenReturn(Optional.empty());
        when(assembler.assembleForAppendPath(savingsId)).thenReturn(assembled);

        assertThatThrownBy(() -> service.withdrawal(savingsId, command)).isInstanceOf(GeneralPlatformDomainRuleException.class);

        verify(domainService, never()).handleWithdrawalOptimized(any(), any(), any(), any(), org.mockito.ArgumentMatchers.anyBoolean(),
                any(), any(), any(), org.mockito.ArgumentMatchers.anyBoolean());
        verify(delegate, never()).withdrawal(any(), any());
    }

    @Test
    void backdatedWithdrawalBlockedForDynamicDepositWithAllowWithdrawalFalse() {
        Long savingsId = 10L;
        LocalDate lastTxnDate = LocalDate.of(2026, 5, 27);
        LocalDate transactionDate = lastTxnDate.minusDays(2);
        DynamicDepositAccount account = dynamicDepositAccount(savingsId, BigDecimal.valueOf(5000), false);
        AssembledSavingsAccount assembled = AssembledSavingsAccount.of(account, null);

        JsonCommand command = withdrawalCommand(transactionDate, BigDecimal.valueOf(1000));

        // Backdated: findLastTransactionDate returns a date AFTER transactionDate, so the method's isBackdated
        // branch would (pre-fix) route straight to delegate.withdrawal(...) before the guard ever ran.
        when(advanclyTransactionRepository.findLastTransactionDate(savingsId)).thenReturn(Optional.of(lastTxnDate));
        when(assembler.assembleForAppendPath(savingsId)).thenReturn(assembled);

        assertThatThrownBy(() -> service.withdrawal(savingsId, command)).isInstanceOf(GeneralPlatformDomainRuleException.class);

        verify(delegate, never()).withdrawal(any(), any());
    }

    @Test
    void sameDayWithdrawalSucceedsForPlainSavingsAccount() {
        Long savingsId = 10L;
        LocalDate transactionDate = LocalDate.of(2026, 5, 27);
        SavingsAccount account = new SavingsAccountTestBuilder().withId(savingsId).withSummary(new SavingsAccountSummaryTestBuilder()
                .withAccountBalance(BigDecimal.valueOf(5000)).withRunningBalanceOnPivotDate(BigDecimal.valueOf(5000)).build()).build();
        SavingsAccountTransaction lastTxn = new SavingsAccountTransactionTestBuilder().withId(101L).withSavingsAccount(account)
                .withRunningBalance(BigDecimal.valueOf(5000)).build();
        AssembledSavingsAccount assembled = AssembledSavingsAccount.of(account, lastTxn);

        JsonCommand command = withdrawalCommand(transactionDate, BigDecimal.valueOf(1000));

        when(advanclyTransactionRepository.findLastTransactionDate(savingsId)).thenReturn(Optional.empty());
        when(assembler.assembleForAppendPath(savingsId)).thenReturn(assembled);

        PaymentDetail paymentDetail = Mockito.mock(PaymentDetail.class);
        when(paymentDetailWritePlatformService.createAndPersistPaymentDetail(eq(command), any())).thenReturn(paymentDetail);

        SavingsAccountTransaction withdrawal = new SavingsAccountTransactionTestBuilder().withId(301L).withSavingsAccount(account)
                .withRunningBalance(BigDecimal.valueOf(4000)).build();
        when(domainService.handleWithdrawalOptimized(eq(account), eq(transactionDate), eq(BigDecimal.valueOf(1000)), eq(paymentDetail),
                eq(true), any(Money.class), eq(account.getCurrency()), eq(lastTxn), eq(false))).thenReturn(withdrawal);

        CommandProcessingResult result = service.withdrawal(savingsId, command);

        assertThat(result.getResourceId()).isEqualTo(301L);
        verify(delegate, never()).withdrawal(any(), any());
    }

    @Test
    void backdatedWithdrawalSucceedsForPlainSavingsAccountAndDelegatesToCore() {
        Long savingsId = 10L;
        LocalDate lastTxnDate = LocalDate.of(2026, 5, 27);
        LocalDate transactionDate = lastTxnDate.minusDays(2);
        SavingsAccount account = new SavingsAccountTestBuilder().withId(savingsId).build();
        AssembledSavingsAccount assembled = AssembledSavingsAccount.of(account, null);

        JsonCommand command = withdrawalCommand(transactionDate, BigDecimal.valueOf(1000));

        when(advanclyTransactionRepository.findLastTransactionDate(savingsId)).thenReturn(Optional.of(lastTxnDate));
        when(assembler.assembleForAppendPath(savingsId)).thenReturn(assembled);

        CommandProcessingResult delegateResult = new CommandProcessingResultBuilder().withEntityId(999L).build();
        when(delegate.withdrawal(savingsId, command)).thenReturn(delegateResult);

        CommandProcessingResult result = service.withdrawal(savingsId, command);

        assertThat(result).isSameAs(delegateResult);
        verify(delegate).withdrawal(savingsId, command);
    }

    private JsonCommand withdrawalCommand(LocalDate transactionDate, BigDecimal transactionAmount) {
        JsonCommand command = Mockito.mock(JsonCommand.class);
        when(command.localDateValueOfParameterNamed("transactionDate")).thenReturn(transactionDate);
        when(command.bigDecimalValueOfParameterNamed("transactionAmount")).thenReturn(transactionAmount);
        return command;
    }

    /**
     * Minimal DynamicDepositAccount test double - mirrors the reflective-construction pattern used by
     * DynamicDepositAccountWithdrawalLockTest (Task 1) and the equivalent helper added to
     * AdvanclyAccountTransfersWritePlatformServiceTest for Task 2's transfer-guard tests.
     */
    private DynamicDepositAccount dynamicDepositAccount(Long savingsId, BigDecimal runningBalance, boolean allowWithdrawal) {
        DynamicDepositAccount account = createInstance(DynamicDepositAccount.class);
        ReflectionTestUtils.setField(account, "id", savingsId);
        ReflectionTestUtils.setField(account, "currency", new MonetaryCurrency("USD", 2, null));
        ReflectionTestUtils.setField(account, "summary", new SavingsAccountSummaryTestBuilder().withAccountBalance(runningBalance)
                .withRunningBalanceOnPivotDate(runningBalance).build());
        DepositAccountDynamicDetail dynamicDetail = DepositAccountDynamicDetail.createNew(account, allowWithdrawal, false);
        ReflectionTestUtils.setField(account, "dynamicDetail", dynamicDetail);
        return account;
    }

    private static <T> T createInstance(Class<T> clazz) {
        try {
            Constructor<T> constructor = clazz.getDeclaredConstructor();
            constructor.setAccessible(true);
            return constructor.newInstance();
        } catch (Exception e) {
            throw new RuntimeException("Failed to create instance of " + clazz.getName(), e);
        }
    }
}
