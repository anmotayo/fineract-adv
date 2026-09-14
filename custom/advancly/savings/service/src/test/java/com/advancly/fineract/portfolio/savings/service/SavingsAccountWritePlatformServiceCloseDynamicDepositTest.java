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
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.advancly.fineract.portfolio.savings.domain.DepositAccountDynamicRateHistory;
import com.advancly.fineract.portfolio.savings.domain.DepositAccountDynamicRateHistoryRepository;
import com.advancly.fineract.portfolio.savings.domain.DepositAccountInterestCharge;
import com.advancly.fineract.portfolio.savings.domain.DepositAccountInterestChargeRepository;
import com.advancly.fineract.portfolio.savings.domain.DynamicDepositAccount;
import com.advancly.fineract.portfolio.savings.domain.DynamicDepositRateHistoryEventType;
import com.advancly.fineract.portfolio.savings.domain.DynamicDepositRateSource;
import com.advancly.fineract.portfolio.savings.testutil.MoneyHelperInitializer;
import com.advancly.fineract.portfolio.savings.testutil.SavingsAccountSummaryTestBuilder;
import com.advancly.fineract.portfolio.savings.testutil.SavingsAccountTestBuilder;
import com.advancly.fineract.portfolio.savings.testutil.SavingsAccountTransactionTestBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.lang.reflect.Constructor;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.apache.fineract.accounting.journalentry.service.JournalEntryWritePlatformService;
import org.apache.fineract.infrastructure.configuration.domain.ConfigurationDomainService;
import org.apache.fineract.infrastructure.core.api.JsonCommand;
import org.apache.fineract.infrastructure.core.data.CommandProcessingResult;
import org.apache.fineract.infrastructure.core.exception.ErrorHandler;
import org.apache.fineract.infrastructure.core.serialization.FromJsonHelper;
import org.apache.fineract.infrastructure.dataqueries.service.EntityDatatableChecksWritePlatformService;
import org.apache.fineract.infrastructure.event.business.service.BusinessEventNotifierService;
import org.apache.fineract.infrastructure.security.service.PlatformSecurityContext;
import org.apache.fineract.organisation.holiday.domain.HolidayRepositoryWrapper;
import org.apache.fineract.organisation.monetary.domain.MonetaryCurrency;
import org.apache.fineract.organisation.monetary.domain.Money;
import org.apache.fineract.organisation.office.domain.Office;
import org.apache.fineract.organisation.staff.domain.StaffRepositoryWrapper;
import org.apache.fineract.organisation.workingdays.domain.WorkingDaysRepositoryWrapper;
import org.apache.fineract.portfolio.account.domain.StandingInstructionRepository;
import org.apache.fineract.portfolio.account.service.AccountAssociationsReadPlatformService;
import org.apache.fineract.portfolio.account.service.AccountTransfersReadPlatformService;
import org.apache.fineract.portfolio.charge.domain.Charge;
import org.apache.fineract.portfolio.charge.domain.ChargeCalculationType;
import org.apache.fineract.portfolio.charge.domain.ChargeRepositoryWrapper;
import org.apache.fineract.portfolio.charge.domain.ChargeTimeType;
import org.apache.fineract.portfolio.client.domain.Client;
import org.apache.fineract.portfolio.note.domain.NoteRepository;
import org.apache.fineract.portfolio.paymentdetail.service.PaymentDetailWritePlatformService;
import org.apache.fineract.portfolio.savings.SavingsAccountTransactionType;
import org.apache.fineract.portfolio.savings.data.SavingsAccountChargeDataValidator;
import org.apache.fineract.portfolio.savings.data.SavingsAccountDataValidator;
import org.apache.fineract.portfolio.savings.data.SavingsAccountTransactionDataValidator;
import org.apache.fineract.portfolio.savings.domain.DepositAccountOnHoldTransactionRepository;
import org.apache.fineract.portfolio.savings.domain.DepositAccountTermAndPreClosure;
import org.apache.fineract.portfolio.savings.domain.GSIMRepositoy;
import org.apache.fineract.portfolio.savings.domain.SavingsAccount;
import org.apache.fineract.portfolio.savings.domain.SavingsAccountAssembler;
import org.apache.fineract.portfolio.savings.domain.SavingsAccountCharge;
import org.apache.fineract.portfolio.savings.domain.SavingsAccountChargeRepositoryWrapper;
import org.apache.fineract.portfolio.savings.domain.SavingsAccountRepositoryWrapper;
import org.apache.fineract.portfolio.savings.domain.SavingsAccountSummary;
import org.apache.fineract.portfolio.savings.domain.SavingsAccountTransaction;
import org.apache.fineract.portfolio.savings.domain.SavingsAccountTransactionRepository;
import org.apache.fineract.portfolio.savings.domain.SavingsAccountTransactionSummaryWrapper;
import org.apache.fineract.portfolio.savings.domain.SavingsHelper;
import org.apache.fineract.portfolio.savings.domain.SavingsProduct;
import org.apache.fineract.portfolio.savings.service.SavingsAccountDomainService;
import org.apache.fineract.portfolio.savings.service.SavingsAccountInterestPostingService;
import org.apache.fineract.portfolio.savings.service.SavingsAccountWritePlatformServiceJpaRepositoryImpl;
import org.apache.fineract.useradministration.domain.AppUser;
import org.apache.fineract.useradministration.domain.AppUserRepositoryWrapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationContext;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Phase 4 closure-collection fix: when a Dynamic Deposit account with a pending early-withdrawal-penalty charge row is
 * prematurely closed (withdrawBalance=true, positive balance),
 * {@code SavingsAccountWritePlatformServiceJpaRepositoryImpl#close} must post final interest up to the closure date so
 * the pending charge - created by the withdrawal just above it - actually gets applied instead of staying pending
 * forever (no scheduled posting job will ever run for a closed account again). Verifies the new gated call this fix
 * inserts into {@code close(...)}, not the charge-application arithmetic itself (already covered by
 * {@link DynamicDepositAccountInterestTest}).
 */
@ExtendWith(MockitoExtension.class)
class SavingsAccountWritePlatformServiceCloseDynamicDepositTest {

    private static final MonetaryCurrency CURRENCY = new MonetaryCurrency("USD", 2, null);
    private static final LocalDate ACTIVATION_DATE = LocalDate.of(2026, 1, 1);
    private static final LocalDate CLOSED_DATE = LocalDate.of(2026, 1, 20);
    private static final BigDecimal OPENING_BALANCE = BigDecimal.valueOf(1000);

    @Mock
    private PlatformSecurityContext context;
    @Mock
    private SavingsAccountDataValidator fromApiJsonDeserializer;
    @Mock
    private SavingsAccountRepositoryWrapper savingAccountRepositoryWrapper;
    @Mock
    private StaffRepositoryWrapper staffRepository;
    @Mock
    private SavingsAccountTransactionRepository savingsAccountTransactionRepository;
    @Mock
    private SavingsAccountAssembler savingAccountAssembler;
    @Mock
    private SavingsAccountTransactionDataValidator savingsAccountTransactionDataValidator;
    @Mock
    private SavingsAccountChargeDataValidator savingsAccountChargeDataValidator;
    @Mock
    private PaymentDetailWritePlatformService paymentDetailWritePlatformService;
    @Mock
    private JournalEntryWritePlatformService journalEntryWritePlatformService;
    @Mock
    private SavingsAccountDomainService savingsAccountDomainService;
    @Mock
    private NoteRepository noteRepository;
    @Mock
    private AccountTransfersReadPlatformService accountTransfersReadPlatformService;
    @Mock
    private AccountAssociationsReadPlatformService accountAssociationsReadPlatformService;
    @Mock
    private ChargeRepositoryWrapper chargeRepository;
    @Mock
    private SavingsAccountChargeRepositoryWrapper savingsAccountChargeRepository;
    @Mock
    private HolidayRepositoryWrapper holidayRepository;
    @Mock
    private WorkingDaysRepositoryWrapper workingDaysRepository;
    @Mock
    private ConfigurationDomainService configurationDomainService;
    @Mock
    private DepositAccountOnHoldTransactionRepository depositAccountOnHoldTransactionRepository;
    @Mock
    private EntityDatatableChecksWritePlatformService entityDatatableChecksWritePlatformService;
    @Mock
    private AppUserRepositoryWrapper appuserRepository;
    @Mock
    private StandingInstructionRepository standingInstructionRepository;
    @Mock
    private BusinessEventNotifierService businessEventNotifierService;
    @Mock
    private GSIMRepositoy gsimRepository;
    @Mock
    private SavingsAccountInterestPostingService savingsAccountInterestPostingService;
    @Mock
    private ErrorHandler errorHandler;

    @Mock
    private DepositAccountDynamicRateHistoryRepository rateHistoryRepository;
    @Mock
    private DepositAccountInterestChargeRepository interestChargeRepository;

    private SavingsAccountWritePlatformServiceJpaRepositoryImpl service;
    private DynamicDepositAccount account;
    private Office office;

    @BeforeEach
    void setUp() {
        MoneyHelperInitializer.initialize();

        // DynamicDepositAccount's postInterest/calculateInterestUsing overrides resolve repositories through this
        // static locator rather than Spring DI (see DynamicDepositAccountInterestTest for the same pattern).
        final ApplicationContext applicationContext = mock(ApplicationContext.class);
        lenient().when(applicationContext.getBean(DepositAccountDynamicRateHistoryRepository.class)).thenReturn(this.rateHistoryRepository);
        lenient().when(applicationContext.getBean(DepositAccountInterestChargeRepository.class)).thenReturn(this.interestChargeRepository);
        ReflectionTestUtils.setField(DynamicDepositServiceLocator.class, "applicationContext", applicationContext);

        this.service = new SavingsAccountWritePlatformServiceJpaRepositoryImpl(context, fromApiJsonDeserializer,
                savingAccountRepositoryWrapper, staffRepository, savingsAccountTransactionRepository, savingAccountAssembler,
                savingsAccountTransactionDataValidator, savingsAccountChargeDataValidator, paymentDetailWritePlatformService,
                journalEntryWritePlatformService, savingsAccountDomainService, noteRepository, accountTransfersReadPlatformService,
                accountAssociationsReadPlatformService, chargeRepository, savingsAccountChargeRepository, holidayRepository,
                workingDaysRepository, configurationDomainService, depositAccountOnHoldTransactionRepository,
                entityDatatableChecksWritePlatformService, appuserRepository, standingInstructionRepository, businessEventNotifierService,
                gsimRepository, savingsAccountInterestPostingService, errorHandler);

        this.account = buildAccount();
    }

    @Test
    void closingWithWithdrawBalancePostsFinalInterestAndAppliesThePendingEarlyWithdrawalCharge() throws Exception {
        // Single rate-history row (2% APR from activation) so calculateInterestUsing can resolve a rate for the
        // final partial period (Jan 1 - Jan 20).
        final SavingsAccountTransaction openingDeposit = depositTransaction(1L, ACTIVATION_DATE, OPENING_BALANCE);
        lenient().when(this.rateHistoryRepository.findByAccountIdOrderByTransactionDateAscIdAsc(this.account.getId()))
                .thenReturn(List.of(DepositAccountDynamicRateHistory.createNew(this.account, openingDeposit, ACTIVATION_DATE,
                        DynamicDepositRateHistoryEventType.ACCOUNT_ACTIVATION, OPENING_BALANCE, 12, 2, null, null, BigDecimal.valueOf(2),
                        BigDecimal.valueOf(2), DynamicDepositRateSource.INTEREST_RATE_CHART)));

        // A pending early-withdrawal-penalty charge row, exactly as Task 7's withdraw hook would have created it
        // moments earlier in the same close() call - percentage 100 so the charge consumes the ENTIRE gross interest
        // this test's injected postInterest call posts, leaving the account at precisely zero balance afterwards
        // (account.close()'s own "results.in.balance.not.zero" check runs right after). A lower percentage would
        // leave a residual, uncredited-interest balance and is a separate, pre-existing concern noted in the report.
        final Charge chargeDefinition = mock(Charge.class);
        lenient().when(chargeDefinition.getAmount()).thenReturn(new BigDecimal("100"));
        lenient().when(chargeDefinition.getChargeCalculation()).thenReturn(ChargeCalculationType.PERCENT_OF_INTEREST.getValue());
        final SavingsAccountCharge attributedCharge = SavingsAccountCharge.createNewWithoutSavingsAccount(chargeDefinition,
                new BigDecimal("100"), ChargeTimeType.SAVINGS_ACTIVATION, ChargeCalculationType.PERCENT_OF_INTEREST, null, true, null,
                null);
        final DepositAccountInterestCharge pendingRow = DepositAccountInterestCharge.createNew(this.account,
                mock(SavingsAccountTransaction.class), attributedCharge, mock(Charge.class), ACTIVATION_DATE, CLOSED_DATE, BigDecimal.ZERO,
                new BigDecimal("100"), BigDecimal.ZERO);
        lenient().when(this.interestChargeRepository.findPendingByAccountIdUpTo(anyLong(), any()))
                .thenReturn(new ArrayList<>(List.of(pendingRow)));
        lenient().when(this.interestChargeRepository.sumPendingChargeAmount(anyLong())).thenReturn(BigDecimal.ZERO);
        lenient().when(this.interestChargeRepository.sumPostedChargeAmount(anyLong())).thenAnswer(invocation -> pendingRow.chargeAmount());

        lenient().when(this.configurationDomainService.isSavingsInterestPostingAtCurrentPeriodEnd()).thenReturn(false);
        lenient().when(this.configurationDomainService.retrieveFinancialYearBeginningMonth()).thenReturn(1);

        when(this.context.authenticatedUser()).thenReturn(mock(AppUser.class));
        when(this.savingAccountAssembler.assembleFrom(1L, false)).thenReturn(this.account);

        // Simulates the real withdraw-hook path (core SavingsAccount.withdraw() -> DynamicDepositAccount#withdraw
        // override) that already creates the pending row above: here it is stubbed directly since
        // savingsAccountDomainService is itself mocked in this unit test and that hook is Task 7's concern, already
        // covered elsewhere. This stub only needs to withdraw the full posted balance to zero and leave a real,
        // dated withdrawal transaction behind, exactly as the real domain service call would.
        when(this.savingsAccountDomainService.handleWithdrawal(eq(this.account), any(), eq(CLOSED_DATE), eq(OPENING_BALANCE), any(), any(),
                eq(false))).thenAnswer(invocation -> {
                    final SavingsAccountTransaction withdrawal = SavingsAccountTransaction.withdrawal(this.account, this.office, null,
                            CLOSED_DATE, Money.of(CURRENCY, OPENING_BALANCE), "test-withdrawal-ref");
                    // Assign a real id, exactly as the database would have by this point in production (the real
                    // domain service persists the withdrawal transaction to generate one before returning) - without
                    // this, this test's own postInterest call and the withdrawal above would collide as two
                    // different null-id transactions when existingTransactionIds is captured, hiding the new
                    // interest/charge transactions from postJournalEntries' newSavingsTransactions computation.
                    ReflectionTestUtils.setField(withdrawal, "id", 900L);
                    this.account.addTransaction(withdrawal);
                    return withdrawal;
                });

        final JsonCommand command = closeCommand();

        final CommandProcessingResult result = this.service.close(1L, command);

        assertThat(result).isNotNull();
        assertThat(this.account.isClosed()).isTrue();

        // The core assertion: the pending charge row must have been applied at closure, not left pending.
        assertThat(pendingRow.isPending()).isFalse();
        assertThat(pendingRow.chargeAmount()).isGreaterThan(BigDecimal.ZERO);
        assertThat(this.account.interestBasedChargeDerived()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(this.account.interestBasedChargePostedDerived()).isEqualByComparingTo(pendingRow.chargeAmount());

        // A genuine new interest-posting transaction (not a correction/no-op) and a PAY_CHARGE transaction, both
        // dated the closure date, must exist.
        final List<SavingsAccountTransaction> interestPostings = this.account.getTransactions().stream()
                .filter(SavingsAccountTransaction::isInterestPostingAndNotReversed).toList();
        assertThat(interestPostings).hasSize(1);
        assertThat(interestPostings.get(0).getTransactionDate()).isEqualTo(CLOSED_DATE);

        final List<SavingsAccountTransaction> chargeTransactions = this.account.getTransactions().stream()
                .filter(SavingsAccountTransaction::isPayCharge).toList();
        assertThat(chargeTransactions).hasSize(1);
        assertThat(chargeTransactions.get(0).getAmount()).isEqualByComparingTo(interestPostings.get(0).getAmount());

        // The 100% charge percentage consumes the entire freshly-posted interest, so the account settles at exactly
        // zero - proving close()'s own "results.in.balance.not.zero" validation, which runs immediately after this
        // fix's injected call, was satisfied rather than thrown.
        assertThat(this.account.getSummary().getAccountBalance()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void closingAPlainSavingsAccountWithWithdrawBalanceNeverPostsExtraInterest() {
        // Regression guard: even with withdrawBalance=true and a positive balance - the exact condition that makes a
        // Dynamic Deposit account reach the new gated call - a plain SavingsAccount (depositAccountType() ->
        // SAVINGS_DEPOSIT, the base-class default) must behave exactly as before this fix: handleWithdrawal runs, and
        // nothing else. configurationDomainService is the sole dependency this fix's postInterest(...) call reaches
        // for (isSavingsInterestPostingAtCurrentPeriodEnd()/retrieveFinancialYearBeginningMonth()) that no other
        // step of close() touches, so zero interactions with it is decisive proof the new branch was never entered.
        final SavingsAccount plainAccount = new SavingsAccountTestBuilder().withId(2L).withSummary(
                new SavingsAccountSummaryTestBuilder().withAccountBalance(OPENING_BALANCE).withTotalDeposits(OPENING_BALANCE).build())
                .withActivationDate(ACTIVATION_DATE).build();
        ReflectionTestUtils.setField(plainAccount, "status", 300); // ACTIVE
        assertThat(plainAccount.depositAccountType().isDynamicDeposit()).isFalse();

        when(this.context.authenticatedUser()).thenReturn(mock(AppUser.class));
        when(this.savingAccountAssembler.assembleFrom(2L, false)).thenReturn(plainAccount);
        when(this.savingsAccountDomainService.handleWithdrawal(eq(plainAccount), any(), eq(CLOSED_DATE), eq(OPENING_BALANCE), any(), any(),
                eq(false))).thenAnswer(invocation -> {
                    final SavingsAccountTransaction withdrawal = SavingsAccountTransaction.withdrawal(plainAccount, this.office, null,
                            CLOSED_DATE, Money.of(CURRENCY, OPENING_BALANCE), "test-withdrawal-ref");
                    ReflectionTestUtils.setField(withdrawal, "id", 901L);
                    plainAccount.addTransaction(withdrawal);
                    // The real handleWithdrawal implementation (mocked away here) updates the account summary as
                    // part of the withdrawal itself; replicate just that net effect so account.close()'s own
                    // "results.in.balance.not.zero" check - unrelated to this fix - sees what it would in production.
                    ReflectionTestUtils.setField(plainAccount.getSummary(), "accountBalance", BigDecimal.ZERO);
                    return withdrawal;
                });

        final JsonCommand command = closeCommandFor(2L, true);

        final CommandProcessingResult result = this.service.close(2L, command);

        assertThat(result).isNotNull();
        assertThat(plainAccount.isClosed()).isTrue();
        verify(this.savingsAccountDomainService).handleWithdrawal(eq(plainAccount), any(), eq(CLOSED_DATE), eq(OPENING_BALANCE), any(),
                any(), eq(false));
        verifyNoInteractions(this.configurationDomainService);
        verify(this.savingsAccountTransactionRepository, never()).save(any());
    }

    private JsonCommand closeCommand() {
        return closeCommandFor(1L, true);
    }

    private JsonCommand closeCommandFor(final Long savingsId, final boolean withdrawBalance) {
        final JsonObject payload = new JsonObject();
        payload.addProperty("dateFormat", "dd MMMM yyyy");
        payload.addProperty("locale", "en");
        payload.addProperty("closedOnDate", "20 January 2026");
        payload.addProperty("withdrawBalance", withdrawBalance);
        final String json = payload.toString();
        final FromJsonHelper fromJsonHelper = new FromJsonHelper();
        return JsonCommand.fromExistingCommand(null, json, JsonParser.parseString(json), fromJsonHelper, null, null, null, null, null, null,
                savingsId, null, null, null, null, null, null, null);
    }

    private SavingsAccountTransaction depositTransaction(final Long id, final LocalDate date, final BigDecimal amount) {
        final SavingsAccountTransaction transaction = new SavingsAccountTransactionTestBuilder().withId(id).withSavingsAccount(this.account)
                .withType(SavingsAccountTransactionType.DEPOSIT).withDate(date).withAmount(amount).build();
        this.account.getTransactions().add(transaction);
        return transaction;
    }

    private DynamicDepositAccount buildAccount() {
        final DynamicDepositAccount newAccount = createInstance(DynamicDepositAccount.class);
        ReflectionTestUtils.setField(newAccount, "id", 1L);
        ReflectionTestUtils.setField(newAccount, "status", 300); // ACTIVE
        ReflectionTestUtils.setField(newAccount, "currency", CURRENCY);
        ReflectionTestUtils.setField(newAccount, "activatedOnDate", ACTIVATION_DATE);
        ReflectionTestUtils.setField(newAccount, "nominalAnnualInterestRate", BigDecimal.valueOf(2));
        ReflectionTestUtils.setField(newAccount, "interestCompoundingPeriodType", 8); // NO_COMPOUNDING_SIMPLE_INTEREST
        ReflectionTestUtils.setField(newAccount, "interestPostingPeriodType", 4); // MONTHLY
        ReflectionTestUtils.setField(newAccount, "interestCalculationType", 1); // DAILY_BALANCE
        ReflectionTestUtils.setField(newAccount, "interestCalculationDaysInYearType", 365);
        ReflectionTestUtils.setField(newAccount, "savingsAccountTransactions", new ArrayList<SavingsAccountTransaction>());
        ReflectionTestUtils.setField(newAccount, "accountTermAndPreClosure",
                DepositAccountTermAndPreClosure.createNew(null, null, null, null, null, null, null, null, null, null, false, null, null));

        final SavingsAccountSummary summary = new SavingsAccountSummaryTestBuilder().withAccountBalance(OPENING_BALANCE)
                .withTotalDeposits(OPENING_BALANCE).build();
        ReflectionTestUtils.setField(newAccount, "summary", summary);
        ReflectionTestUtils.setField(newAccount, "savingsAccountTransactionSummaryWrapper", new SavingsAccountTransactionSummaryWrapper());

        final AccountTransfersReadPlatformService accountTransfersReadPlatformServiceForHelper = mock(
                AccountTransfersReadPlatformService.class);
        lenient().when(accountTransfersReadPlatformServiceForHelper.fetchPostInterestTransactionIds(anyLong())).thenReturn(List.of());
        final org.apache.fineract.portfolio.savings.service.SavingsInterestReadPlatformService savingsInterestReadPlatformService = mock(
                org.apache.fineract.portfolio.savings.service.SavingsInterestReadPlatformService.class);
        final SavingsHelper savingsHelper = new SavingsHelper(accountTransfersReadPlatformServiceForHelper,
                savingsInterestReadPlatformService);
        ReflectionTestUtils.setField(newAccount, "savingsHelper", savingsHelper);

        this.office = mock(Office.class);
        lenient().when(this.office.getId()).thenReturn(1L);
        final Client client = mock(Client.class);
        lenient().when(client.getOffice()).thenReturn(this.office);
        lenient().when(client.officeId()).thenReturn(1L);
        lenient().when(client.getId()).thenReturn(1L);
        ReflectionTestUtils.setField(newAccount, "client", client);

        final SavingsProduct product = mock(SavingsProduct.class);
        lenient().when(product.getId()).thenReturn(10L);
        ReflectionTestUtils.setField(newAccount, "product", product);

        return newAccount;
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
