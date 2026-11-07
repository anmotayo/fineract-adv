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
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.advancly.fineract.portfolio.savings.data.BulkTransactionDataValidator;
import com.advancly.fineract.portfolio.savings.domain.AdvanclySavingsAccountAssembler;
import com.advancly.fineract.portfolio.savings.domain.AdvanclySavingsAccountTransactionRepository;
import com.advancly.fineract.portfolio.savings.domain.AssembledSavingsAccount;
import com.advancly.fineract.portfolio.savings.testutil.MoneyHelperInitializer;
import com.advancly.fineract.portfolio.savings.testutil.SavingsAccountSummaryTestBuilder;
import com.advancly.fineract.portfolio.savings.testutil.SavingsAccountTestBuilder;
import com.advancly.fineract.portfolio.savings.testutil.SavingsAccountTransactionTestBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import org.apache.fineract.accounting.producttoaccountmapping.domain.ProductToGLAccountMappingRepository;
import org.apache.fineract.infrastructure.configuration.domain.ConfigurationDomainService;
import org.apache.fineract.infrastructure.core.api.JsonCommand;
import org.apache.fineract.infrastructure.core.data.CommandProcessingResult;
import org.apache.fineract.infrastructure.core.data.CommandProcessingResultBuilder;
import org.apache.fineract.infrastructure.core.serialization.FromJsonHelper;
import org.apache.fineract.infrastructure.security.service.PlatformSecurityContext;
import org.apache.fineract.organisation.monetary.domain.Money;
import org.apache.fineract.portfolio.client.exception.ClientNotActiveException;
import org.apache.fineract.portfolio.note.domain.NoteRepository;
import org.apache.fineract.portfolio.paymentdetail.domain.PaymentDetail;
import org.apache.fineract.portfolio.paymentdetail.domain.PaymentDetailRepository;
import org.apache.fineract.portfolio.paymentdetail.service.PaymentDetailWritePlatformService;
import org.apache.fineract.portfolio.paymenttype.domain.PaymentTypeRepository;
import org.apache.fineract.portfolio.savings.data.SavingsAccountTransactionDataValidator;
import org.apache.fineract.portfolio.savings.domain.GSIMRepositoy;
import org.apache.fineract.portfolio.savings.domain.SavingsAccount;
import org.apache.fineract.portfolio.savings.domain.SavingsAccountCharge;
import org.apache.fineract.portfolio.savings.domain.SavingsAccountTransaction;
import org.apache.fineract.portfolio.savings.service.SavingsAccountWritePlatformServiceJpaRepositoryImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

/** Routing between the append path and core: withdrawal fees and accrual reversals need core. */
@ExtendWith(MockitoExtension.class)
class AdvanclySavingsAccountWritePlatformServiceTransactionRulesTest {

    private static final Long SAVINGS_ID = 10L;
    private static final LocalDate DAY = LocalDate.of(2026, 5, 27);

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
    private PaymentTypeRepository paymentTypeRepositoryWrapper;
    @Mock
    private PaymentDetailRepository paymentDetailRepository;
    @Mock
    private AdvanclyInterestChargeApplicationService interestChargeApplicationService;
    @Mock
    private ProductToGLAccountMappingRepository productToGLAccountMappingRepository;
    @Mock
    private ConfigurationDomainService configurationDomainService;

    private AdvanclySavingsAccountWritePlatformService service;
    private FromJsonHelper fromJsonHelper;
    private SavingsAccount account;

    @BeforeEach
    void setUp() {
        MoneyHelperInitializer.initialize();
        this.fromJsonHelper = new FromJsonHelper();
        this.service = new AdvanclySavingsAccountWritePlatformService(this.context, this.savingsAccountTransactionDataValidator,
                this.assembler, this.domainService, this.advanclyTransactionRepository, this.paymentDetailWritePlatformService,
                this.noteRepository, this.gsimRepository, this.delegate, new BulkTransactionDataValidator(this.fromJsonHelper),
                this.fromJsonHelper, this.paymentTypeRepositoryWrapper, this.paymentDetailRepository, this.interestChargeApplicationService,
                this.productToGLAccountMappingRepository, this.configurationDomainService);
        this.account = new SavingsAccountTestBuilder().withId(SAVINGS_ID).withSummary(new SavingsAccountSummaryTestBuilder()
                .withAccountBalance(BigDecimal.valueOf(5000)).withRunningBalanceOnPivotDate(BigDecimal.valueOf(5000)).build()).build();
    }

    // ---- withdrawal fee ----

    @Test
    void aWithdrawalCarryingAFeeCorePaysGoesThroughCore() {
        withCharge(true, true, false);
        stubAssembled();
        final JsonCommand command = command(DAY, 1000);
        final CommandProcessingResult delegateResult = new CommandProcessingResultBuilder().withEntityId(999L).build();
        when(this.delegate.withdrawal(SAVINGS_ID, command)).thenReturn(delegateResult);

        final CommandProcessingResult result = this.service.withdrawal(SAVINGS_ID, command);

        assertThat(result).isSameAs(delegateResult);
        verify(this.domainService, never()).handleWithdrawalOptimized(any(), any(), any(), any(), anyBoolean(), any(), any(), any(),
                anyBoolean());
    }

    // The early-withdrawal interest charge is applied after the withdrawal, so the append path can still serve it.
    @Test
    void aWithdrawalWithOnlyAPercentOfInterestChargeIsServedByTheAppendPath() {
        withCharge(true, true, true);
        stubAssembled();
        final JsonCommand command = command(DAY, 1000);
        final SavingsAccountTransaction withdrawal = new SavingsAccountTransactionTestBuilder().withId(301L)
                .withSavingsAccount(this.account).withRunningBalance(BigDecimal.valueOf(4000)).build();
        final PaymentDetail paymentDetail = Mockito.mock(PaymentDetail.class);
        when(this.paymentDetailWritePlatformService.createAndPersistPaymentDetail(eq(command), any())).thenReturn(paymentDetail);
        when(this.domainService.handleWithdrawalOptimized(eq(this.account), eq(DAY), eq(BigDecimal.valueOf(1000)), eq(paymentDetail),
                eq(true), any(Money.class), any(), any(), eq(false))).thenReturn(withdrawal);

        final CommandProcessingResult result = this.service.withdrawal(SAVINGS_ID, command);

        assertThat(result.getResourceId()).isEqualTo(301L);
        verify(this.delegate, never()).withdrawal(any(), any());
    }

    @Test
    void aBulkRequestWithAFeeBearingWithdrawalGoesThroughCore() {
        withCharge(true, true, false);
        stubAssembledForBulk();
        when(this.delegate.deposit(any(), any())).thenReturn(new CommandProcessingResultBuilder().withEntityId(1L).build());
        when(this.delegate.withdrawal(any(), any())).thenReturn(new CommandProcessingResultBuilder().withEntityId(2L).build());

        this.service.bulkTransaction(SAVINGS_ID, bulkCommand("deposit", "withdrawal"));

        verify(this.delegate).deposit(any(), any());
        verify(this.delegate).withdrawal(any(), any());
        verify(this.domainService, never()).handleDepositOptimized(any(), any(), any(), any(), any(), any(), any(), anyBoolean());
    }

    @Test
    void aBulkRequestOfOnlyDepositsIsServedByTheAppendPathEvenWhenTheAccountHasAWithdrawalFee() {
        withCharge(true, true, false);
        stubAssembledForBulk();
        final PaymentDetail paymentDetail = Mockito.mock(PaymentDetail.class);
        when(this.paymentTypeRepositoryWrapper.findById(1L))
                .thenReturn(java.util.Optional.of(Mockito.mock(org.apache.fineract.portfolio.paymenttype.domain.PaymentType.class)));
        when(this.paymentDetailRepository.saveAndFlush(any(PaymentDetail.class))).thenReturn(paymentDetail);
        final SavingsAccountTransaction deposit = new SavingsAccountTransactionTestBuilder().withId(101L).withSavingsAccount(this.account)
                .withRunningBalance(BigDecimal.valueOf(6000)).build();
        when(this.domainService.handleDepositOptimized(eq(this.account), any(), any(), any(), any(Money.class), any(), any(), eq(false)))
                .thenReturn(deposit);

        this.service.bulkTransaction(SAVINGS_ID, bulkCommand("deposit", "deposit"));

        verify(this.delegate, never()).deposit(any(), any());
    }

    // ---- accruals ----

    @Test
    void aDepositOnAnAccrualAccountWithAnAccrualOnOrAfterItsDateGoesThroughCore() {
        accrualAccountWithAccrualOnOrAfter(true);
        stubAssembled();
        final JsonCommand command = command(DAY, 1000);
        final CommandProcessingResult delegateResult = new CommandProcessingResultBuilder().withEntityId(999L).build();
        when(this.delegate.deposit(SAVINGS_ID, command)).thenReturn(delegateResult);

        assertThat(this.service.deposit(SAVINGS_ID, command)).isSameAs(delegateResult);

        verify(this.domainService, never()).handleDepositOptimized(any(), any(), any(), any(), any(), any(), any(), anyBoolean());
    }

    @Test
    void aWithdrawalOnAnAccrualAccountWithAnAccrualOnOrAfterItsDateGoesThroughCore() {
        accrualAccountWithAccrualOnOrAfter(true);
        stubAssembled();
        final JsonCommand command = command(DAY, 1000);
        final CommandProcessingResult delegateResult = new CommandProcessingResultBuilder().withEntityId(999L).build();
        when(this.delegate.withdrawal(SAVINGS_ID, command)).thenReturn(delegateResult);

        assertThat(this.service.withdrawal(SAVINGS_ID, command)).isSameAs(delegateResult);

        verify(this.domainService, never()).handleWithdrawalOptimized(any(), any(), any(), any(), anyBoolean(), any(), any(), any(),
                anyBoolean());
    }

    // Only an accrual-accounting product has accruals to reverse, whatever its accrued-till date says.
    @Test
    void anAccountThatIsNotOnAccrualAccountingIsNeverAskedAboutAccruals() {
        this.account.setAccruedTillDate(DAY);
        stubAssembled();
        final JsonCommand command = command(DAY, 1000);
        final SavingsAccountTransaction deposit = new SavingsAccountTransactionTestBuilder().withId(101L).withSavingsAccount(this.account)
                .withRunningBalance(BigDecimal.valueOf(6000)).build();
        when(this.paymentDetailWritePlatformService.createAndPersistPaymentDetail(eq(command), any()))
                .thenReturn(Mockito.mock(PaymentDetail.class));
        when(this.domainService.handleDepositOptimized(eq(this.account), eq(DAY), any(), any(), any(Money.class), any(), any(), eq(false)))
                .thenReturn(deposit);

        this.service.deposit(SAVINGS_ID, command);

        verify(this.delegate, never()).deposit(any(), any());
    }

    @Test
    void anAccrualAccountThatWasNeverAccruedIsServedByTheAppendPath() {
        when(this.account.savingsProduct().isAccrualBasedAccountingEnabled()).thenReturn(true);
        stubAssembled();
        final JsonCommand command = command(DAY, 1000);
        final SavingsAccountTransaction deposit = new SavingsAccountTransactionTestBuilder().withId(101L).withSavingsAccount(this.account)
                .withRunningBalance(BigDecimal.valueOf(6000)).build();
        when(this.paymentDetailWritePlatformService.createAndPersistPaymentDetail(eq(command), any()))
                .thenReturn(Mockito.mock(PaymentDetail.class));
        when(this.domainService.handleDepositOptimized(eq(this.account), eq(DAY), any(), any(), any(Money.class), any(), any(), eq(false)))
                .thenReturn(deposit);

        this.service.deposit(SAVINGS_ID, command);

        verify(this.delegate, never()).deposit(any(), any());
    }

    @Test
    void anAccrualAccountWithNoAccrualInTheWayIsServedByTheAppendPath() {
        accrualAccountWithAccrualOnOrAfter(false);
        stubAssembled();
        final JsonCommand command = command(DAY, 1000);
        final SavingsAccountTransaction deposit = new SavingsAccountTransactionTestBuilder().withId(101L).withSavingsAccount(this.account)
                .withRunningBalance(BigDecimal.valueOf(6000)).build();
        when(this.paymentDetailWritePlatformService.createAndPersistPaymentDetail(eq(command), any()))
                .thenReturn(Mockito.mock(PaymentDetail.class));
        when(this.domainService.handleDepositOptimized(eq(this.account), eq(DAY), any(), any(), any(Money.class), any(), any(), eq(false)))
                .thenReturn(deposit);

        this.service.deposit(SAVINGS_ID, command);

        verify(this.delegate, never()).deposit(any(), any());
    }

    // ---- client and group ----

    @Test
    void aDepositForAClientThatIsNotActiveIsRefusedBeforeAnythingIsWritten() {
        when(this.account.getClient().isNotActive()).thenReturn(true);
        stubAssembled();

        assertThatThrownBy(() -> this.service.deposit(SAVINGS_ID, command(DAY, 1000))).isInstanceOf(ClientNotActiveException.class);

        verify(this.domainService, never()).handleDepositOptimized(any(), any(), any(), any(), any(), any(), any(), anyBoolean());
    }

    @Test
    void aWithdrawalForAClientThatIsNotActiveIsRefusedBeforeAnythingIsWritten() {
        when(this.account.getClient().isNotActive()).thenReturn(true);
        stubAssembled();

        assertThatThrownBy(() -> this.service.withdrawal(SAVINGS_ID, command(DAY, 1000))).isInstanceOf(ClientNotActiveException.class);

        verify(this.domainService, never()).handleWithdrawalOptimized(any(), any(), any(), any(), anyBoolean(), any(), any(), any(),
                anyBoolean());
    }

    @Test
    void aBulkRequestForAClientThatIsNotActiveIsRefusedBeforeAnythingIsWritten() {
        when(this.account.getClient().isNotActive()).thenReturn(true);
        stubAssembledForBulk();

        assertThatThrownBy(() -> this.service.bulkTransaction(SAVINGS_ID, bulkCommand("deposit", "deposit")))
                .isInstanceOf(ClientNotActiveException.class);

        verify(this.domainService, never()).handleDepositOptimized(any(), any(), any(), any(), any(), any(), any(), anyBoolean());
    }

    // ---- made backdated by a concurrent writer (pre-lock check saw nothing later) ----

    @Test
    void aDepositMadeBackdatedByAConcurrentWriterGoesThroughCore() {
        stubAssembledWithLastTransactionOn(DAY.plusDays(1));
        final JsonCommand command = command(DAY, 1000);
        final CommandProcessingResult delegateResult = new CommandProcessingResultBuilder().withEntityId(999L).build();
        when(this.delegate.deposit(SAVINGS_ID, command)).thenReturn(delegateResult);

        assertThat(this.service.deposit(SAVINGS_ID, command)).isSameAs(delegateResult);

        verify(this.domainService, never()).handleDepositOptimized(any(), any(), any(), any(), any(), any(), any(), anyBoolean());
    }

    @Test
    void aBulkRequestMadeBackdatedByAConcurrentWriterGoesThroughCore() {
        stubAssembledWithLastTransactionOn(DAY.plusDays(1));
        when(this.delegate.deposit(eq(SAVINGS_ID), any())).thenReturn(new CommandProcessingResultBuilder().withEntityId(999L).build());

        this.service.bulkTransaction(SAVINGS_ID, bulkCommand("deposit", "deposit"));

        verify(this.delegate, Mockito.times(2)).deposit(any(), any());
        verify(this.domainService, never()).handleDepositOptimized(any(), any(), any(), any(), any(), any(), any(), anyBoolean());
    }

    // ---- helpers ----

    private void stubAssembledWithLastTransactionOn(final LocalDate lastTransactionDate) {
        when(this.advanclyTransactionRepository.findLastTransactionDate(SAVINGS_ID)).thenReturn(Optional.empty());
        when(this.assembler.assembleForAppendPath(SAVINGS_ID))
                .thenReturn(AssembledSavingsAccount.of(this.account, null, lastTransactionDate));
    }

    private void stubAssembled() {
        when(this.advanclyTransactionRepository.findLastTransactionDate(SAVINGS_ID)).thenReturn(Optional.empty());
        when(this.assembler.assembleForAppendPath(SAVINGS_ID)).thenReturn(AssembledSavingsAccount.of(this.account, null));
    }

    private void stubAssembledForBulk() {
        stubAssembled();
    }

    private void withCharge(final boolean withdrawalFee, final boolean active, final boolean percentageOfInterest) {
        final SavingsAccountCharge charge = Mockito.mock(SavingsAccountCharge.class);
        Mockito.lenient().when(charge.isWithdrawalFee()).thenReturn(withdrawalFee);
        Mockito.lenient().when(charge.isActive()).thenReturn(active);
        Mockito.lenient().when(charge.isPercentageOfInterest()).thenReturn(percentageOfInterest);
        ReflectionTestUtils.setField(this.account, "charges", new HashSet<>(List.of(charge)));
    }

    // The tests transact on DAY: accrued till DAY puts the accruals in the way, accrued till the day before does not.
    private void accrualAccountWithAccrualOnOrAfter(final boolean accrualInTheWay) {
        when(this.account.savingsProduct().isAccrualBasedAccountingEnabled()).thenReturn(true);
        this.account.setAccruedTillDate(accrualInTheWay ? DAY : DAY.minusDays(1));
    }

    private JsonCommand command(final LocalDate transactionDate, final int amount) {
        final JsonCommand command = Mockito.mock(JsonCommand.class);
        Mockito.lenient().when(command.localDateValueOfParameterNamed("transactionDate")).thenReturn(transactionDate);
        Mockito.lenient().when(command.bigDecimalValueOfParameterNamed("transactionAmount")).thenReturn(BigDecimal.valueOf(amount));
        return command;
    }

    private JsonCommand bulkCommand(final String firstType, final String secondType) {
        final JsonObject payload = new JsonObject();
        payload.addProperty("dateFormat", "dd MMMM yyyy");
        payload.addProperty("locale", "en");
        final JsonArray transactions = new JsonArray();
        transactions.add(bulkTransaction(firstType, "REC-001", 5000));
        transactions.add(bulkTransaction(secondType, "REC-002", 2000));
        payload.add("transactions", transactions);
        final String json = payload.toString();
        return JsonCommand.fromExistingCommand(null, json, JsonParser.parseString(json), this.fromJsonHelper, null, null, null, null, null,
                null, SAVINGS_ID, null, null, null, null, null, null, null);
    }

    private static JsonObject bulkTransaction(final String type, final String receipt, final int amount) {
        final JsonObject txn = new JsonObject();
        txn.addProperty("type", type);
        txn.addProperty("transactionDate", "05 April 2026");
        txn.addProperty("transactionAmount", amount);
        txn.addProperty("paymentTypeId", 1);
        txn.addProperty("receiptNumber", receipt);
        return txn;
    }
}
