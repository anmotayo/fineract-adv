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

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.advancly.fineract.portfolio.savings.data.BulkTransactionDataValidator;
import com.advancly.fineract.portfolio.savings.domain.AdvanclySavingsAccountAssembler;
import com.advancly.fineract.portfolio.savings.domain.AdvanclySavingsAccountTransactionRepository;
import com.advancly.fineract.portfolio.savings.domain.AssembledSavingsAccount;
import com.advancly.fineract.portfolio.savings.domain.DepositAccountDynamicDetail;
import com.advancly.fineract.portfolio.savings.domain.DynamicDepositAccount;
import com.advancly.fineract.portfolio.savings.domain.EarlyWithdrawalChargeMode;
import com.advancly.fineract.portfolio.savings.domain.SavingsProductEarlyWithdrawalCharge;
import com.advancly.fineract.portfolio.savings.domain.SavingsProductEarlyWithdrawalChargeRepository;
import com.advancly.fineract.portfolio.savings.testutil.MoneyHelperInitializer;
import com.advancly.fineract.portfolio.savings.testutil.SavingsAccountSummaryTestBuilder;
import com.advancly.fineract.portfolio.savings.testutil.SavingsAccountTestBuilder;
import com.advancly.fineract.portfolio.savings.testutil.SavingsAccountTransactionTestBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.lang.reflect.Constructor;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.apache.fineract.infrastructure.core.api.JsonCommand;
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
import org.apache.fineract.portfolio.savings.domain.DepositAccountTermAndPreClosure;
import org.apache.fineract.portfolio.savings.domain.GSIMRepositoy;
import org.apache.fineract.portfolio.savings.domain.SavingsAccount;
import org.apache.fineract.portfolio.savings.domain.SavingsAccountTransaction;
import org.apache.fineract.portfolio.savings.domain.SavingsProduct;
import org.apache.fineract.portfolio.savings.service.SavingsAccountWritePlatformServiceJpaRepositoryImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Drives the REAL {@link AdvanclySavingsAccountWritePlatformService#withdrawal(Long, JsonCommand)} - and therefore its
 * real {@code isEarlyForForfeiture}/{@code isCumulativeMode} private helpers - against a real {@link JsonCommand} built
 * from JSON the same way {@code SavingsAccountWritePlatformServiceCloseDynamicDepositTest} builds its close command.
 * Only the forfeiture service itself is mocked, since what is under test here is purely the trigger decision: which
 * combinations of product mode, account type and request flag reach {@code forfeitIfApplicable(...)} at all.
 */
class AdvanclySavingsAccountWritePlatformServiceCumulativeForfeitureTest {

    private static final Long SAVINGS_ID = 10L;
    private static final Long PRODUCT_ID = 42L;
    private static final Long CHARGE_ID = 7L;
    private static final LocalDate WITHDRAWAL_DATE = LocalDate.of(2026, 2, 15);
    private static final LocalDate MATURITY_AFTER_WITHDRAWAL = LocalDate.of(2026, 7, 1);
    private static final MonetaryCurrency CURRENCY = new MonetaryCurrency("USD", 2, null);

    private PlatformSecurityContext context;
    private SavingsAccountTransactionDataValidator savingsAccountTransactionDataValidator;
    private AdvanclySavingsAccountAssembler assembler;
    private AdvanclySavingsAccountDomainService domainService;
    private AdvanclySavingsAccountTransactionRepository advanclyTransactionRepository;
    private PaymentDetailWritePlatformService paymentDetailWritePlatformService;
    private NoteRepository noteRepository;
    private GSIMRepositoy gsimRepository;
    private SavingsAccountWritePlatformServiceJpaRepositoryImpl delegate;
    private PaymentTypeRepositoryWrapper paymentTypeRepositoryWrapper;
    private PaymentDetailRepository paymentDetailRepository;
    private SavingsProductEarlyWithdrawalChargeRepository productEarlyWithdrawalChargeRepository;
    private CumulativeInterestForfeitureService forfeitureService;
    private AdvanclySavingsAccountWritePlatformService service;

    @BeforeEach
    void setUp() {
        MoneyHelperInitializer.initialize();
        this.context = mock(PlatformSecurityContext.class);
        this.savingsAccountTransactionDataValidator = mock(SavingsAccountTransactionDataValidator.class);
        this.assembler = mock(AdvanclySavingsAccountAssembler.class);
        this.domainService = mock(AdvanclySavingsAccountDomainService.class);
        this.advanclyTransactionRepository = mock(AdvanclySavingsAccountTransactionRepository.class);
        this.paymentDetailWritePlatformService = mock(PaymentDetailWritePlatformService.class);
        this.noteRepository = mock(NoteRepository.class);
        this.gsimRepository = mock(GSIMRepositoy.class);
        this.delegate = mock(SavingsAccountWritePlatformServiceJpaRepositoryImpl.class);
        this.paymentTypeRepositoryWrapper = mock(PaymentTypeRepositoryWrapper.class);
        this.paymentDetailRepository = mock(PaymentDetailRepository.class);
        this.productEarlyWithdrawalChargeRepository = mock(SavingsProductEarlyWithdrawalChargeRepository.class);
        this.forfeitureService = mock(CumulativeInterestForfeitureService.class);

        final FromJsonHelper fromJsonHelper = new FromJsonHelper();
        this.service = new AdvanclySavingsAccountWritePlatformService(this.context, this.savingsAccountTransactionDataValidator,
                this.assembler, this.domainService, this.advanclyTransactionRepository, this.paymentDetailWritePlatformService,
                this.noteRepository, this.gsimRepository, this.delegate, new BulkTransactionDataValidator(fromJsonHelper), fromJsonHelper,
                this.paymentTypeRepositoryWrapper, this.paymentDetailRepository, this.productEarlyWithdrawalChargeRepository,
                this.forfeitureService);

        lenient().when(this.advanclyTransactionRepository.findLastTransactionDate(SAVINGS_ID)).thenReturn(Optional.empty());
    }

    @Test
    void aDynamicDepositWithdrawalBeforeMaturityForfeitsOnACumulativeProductWithNoRequestFlagAtAll() {
        withProductMode(EarlyWithdrawalChargeMode.CUMULATIVE);
        givenDynamicDepositAccount();

        this.service.withdrawal(SAVINGS_ID, withdrawalCommand(WITHDRAWAL_DATE, null));

        verify(this.forfeitureService).forfeitIfApplicable(any(SavingsAccount.class), eq(WITHDRAWAL_DATE), eq(false), eq(false));
    }

    @Test
    void aPerPeriodProductKeepsItsExistingBehaviourAndNeverForfeits() {
        withProductMode(EarlyWithdrawalChargeMode.PER_PERIOD);
        givenDynamicDepositAccount();

        this.service.withdrawal(SAVINGS_ID, withdrawalCommand(WITHDRAWAL_DATE, null));

        verify(this.forfeitureService, never()).forfeitIfApplicable(any(), any(), anyBoolean(), anyBoolean());
    }

    @Test
    void aProductWithNoEarlyWithdrawalChargeRowNeverForfeits() {
        // The product flag is enforced transitively: no flag means no row, and no row means no forfeiture.
        lenient().when(this.productEarlyWithdrawalChargeRepository.findBySavingsProductId(PRODUCT_ID)).thenReturn(List.of());
        givenDynamicDepositAccount();

        this.service.withdrawal(SAVINGS_ID, withdrawalCommand(WITHDRAWAL_DATE, null));

        verify(this.forfeitureService, never()).forfeitIfApplicable(any(), any(), anyBoolean(), anyBoolean());
    }

    @Test
    void aDynamicDepositWithdrawalOnOrAfterMaturityIsNotEarlySoNothingIsForfeited() {
        withProductMode(EarlyWithdrawalChargeMode.CUMULATIVE);
        givenDynamicDepositAccount(WITHDRAWAL_DATE.minusDays(1));

        this.service.withdrawal(SAVINGS_ID, withdrawalCommand(WITHDRAWAL_DATE, null));

        verify(this.forfeitureService, never()).forfeitIfApplicable(any(), any(), anyBoolean(), anyBoolean());
    }

    @Test
    void plainSavingsEarlinessComesFromTheRequestBecauseTheAccountHasNoMaturityDateToAnswerFrom() {
        withProductMode(EarlyWithdrawalChargeMode.CUMULATIVE);
        givenPlainSavingsAccount();

        this.service.withdrawal(SAVINGS_ID, withdrawalCommand(WITHDRAWAL_DATE, false));

        verify(this.forfeitureService, never()).forfeitIfApplicable(any(), any(), anyBoolean(), anyBoolean());
    }

    @Test
    void plainSavingsForfeitsOnceTheRequestAssertsTheWithdrawalIsEarly() {
        withProductMode(EarlyWithdrawalChargeMode.CUMULATIVE);
        givenPlainSavingsAccount();

        this.service.withdrawal(SAVINGS_ID, withdrawalCommand(WITHDRAWAL_DATE, true));

        verify(this.forfeitureService).forfeitIfApplicable(any(SavingsAccount.class), eq(WITHDRAWAL_DATE), eq(false), eq(false));
    }

    @Test
    void aBackdatedCumulativeWithdrawalIsRejectedRatherThanForfeitingAgainstAnUnboundedInterestPosting() {
        withProductMode(EarlyWithdrawalChargeMode.CUMULATIVE);
        givenDynamicDepositAccount();
        lenient().when(this.advanclyTransactionRepository.findLastTransactionDate(SAVINGS_ID))
                .thenReturn(Optional.of(WITHDRAWAL_DATE.plusDays(3)));

        final JsonCommand command = withdrawalCommand(WITHDRAWAL_DATE, null);

        assertThatThrownBy(() -> this.service.withdrawal(SAVINGS_ID, command)).isInstanceOf(GeneralPlatformDomainRuleException.class);

        verify(this.forfeitureService, never()).forfeitIfApplicable(any(), any(), anyBoolean(), anyBoolean());
        verify(this.delegate, never()).withdrawal(any(), any());
    }

    private void withProductMode(final EarlyWithdrawalChargeMode mode) {
        lenient().when(this.productEarlyWithdrawalChargeRepository.findBySavingsProductId(PRODUCT_ID))
                .thenReturn(List.of(SavingsProductEarlyWithdrawalCharge.createNew(PRODUCT_ID, CHARGE_ID, mode)));
    }

    private void givenDynamicDepositAccount() {
        givenDynamicDepositAccount(MATURITY_AFTER_WITHDRAWAL);
    }

    private void givenDynamicDepositAccount(final LocalDate maturityDate) {
        final DynamicDepositAccount account = createInstance(DynamicDepositAccount.class);
        ReflectionTestUtils.setField(account, "id", SAVINGS_ID);
        ReflectionTestUtils.setField(account, "currency", CURRENCY);
        ReflectionTestUtils.setField(account, "summary", new SavingsAccountSummaryTestBuilder().withAccountBalance(BigDecimal.valueOf(5000))
                .withRunningBalanceOnPivotDate(BigDecimal.valueOf(5000)).build());
        ReflectionTestUtils.setField(account, "dynamicDetail", DepositAccountDynamicDetail.createNew(account, true, false));

        final DepositAccountTermAndPreClosure term = DepositAccountTermAndPreClosure.createNew(null, null, null, null, null, null, null,
                null, null, null, false, null, null);
        term.updateMaturityDetails(null, maturityDate);
        ReflectionTestUtils.setField(account, "accountTermAndPreClosure", term);

        final SavingsProduct product = mock(SavingsProduct.class);
        lenient().when(product.getId()).thenReturn(PRODUCT_ID);
        ReflectionTestUtils.setField(account, "product", product);

        lenient().when(this.assembler.assembleForAppendPath(SAVINGS_ID)).thenReturn(AssembledSavingsAccount.of(account, null));
        // A Dynamic Deposit account is not a plain savings deposit, so withdrawal(...) always routes it to core.
        lenient().when(this.delegate.withdrawal(eq(SAVINGS_ID), any())).thenReturn(null);
    }

    private void givenPlainSavingsAccount() {
        final SavingsAccount account = new SavingsAccountTestBuilder().withId(SAVINGS_ID).withSummary(new SavingsAccountSummaryTestBuilder()
                .withAccountBalance(BigDecimal.valueOf(5000)).withRunningBalanceOnPivotDate(BigDecimal.valueOf(5000)).build()).build();
        final SavingsProduct product = mock(SavingsProduct.class);
        lenient().when(product.getId()).thenReturn(PRODUCT_ID);
        ReflectionTestUtils.setField(account, "product", product);

        final SavingsAccountTransaction lastTransaction = new SavingsAccountTransactionTestBuilder().withId(101L)
                .withSavingsAccount(account).withRunningBalance(BigDecimal.valueOf(5000)).build();
        lenient().when(this.assembler.assembleForAppendPath(SAVINGS_ID)).thenReturn(AssembledSavingsAccount.of(account, lastTransaction));

        final PaymentDetail paymentDetail = mock(PaymentDetail.class);
        lenient().when(this.paymentDetailWritePlatformService.createAndPersistPaymentDetail(any(), any())).thenReturn(paymentDetail);
        final SavingsAccountTransaction withdrawal = new SavingsAccountTransactionTestBuilder().withId(301L).withSavingsAccount(account)
                .withRunningBalance(BigDecimal.valueOf(4900)).build();
        lenient()
                .when(this.domainService.handleWithdrawalOptimized(eq(account), eq(WITHDRAWAL_DATE), any(BigDecimal.class),
                        eq(paymentDetail), eq(true), any(Money.class), eq(account.getCurrency()), eq(lastTransaction), eq(false)))
                .thenReturn(withdrawal);
    }

    /**
     * A real JsonCommand, built from JSON exactly as the command-processing pipeline builds one, so that
     * {@code withdrawal(...)}'s own parameter reads - including
     * {@code booleanPrimitiveValueOfParameterNamed("applyEarlyWithdrawalCharge")}, whose absent/false/true cases are
     * what this class is really about - all run for real rather than through a stubbed mock.
     */
    private JsonCommand withdrawalCommand(final LocalDate transactionDate, final Boolean applyEarlyWithdrawalCharge) {
        final JsonObject payload = new JsonObject();
        payload.addProperty("dateFormat", "dd MMMM yyyy");
        payload.addProperty("locale", "en");
        payload.addProperty("transactionDate",
                transactionDate.format(java.time.format.DateTimeFormatter.ofPattern("dd MMMM yyyy", java.util.Locale.ENGLISH)));
        payload.addProperty("transactionAmount", 100);
        if (applyEarlyWithdrawalCharge != null) {
            payload.addProperty("applyEarlyWithdrawalCharge", applyEarlyWithdrawalCharge);
        }
        final String json = payload.toString();
        return JsonCommand.fromExistingCommand(null, json, JsonParser.parseString(json), new FromJsonHelper(), null, null, null, null, null,
                null, SAVINGS_ID, null, null, null, null, null, null, null);
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
