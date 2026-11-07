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
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.advancly.fineract.portfolio.savings.data.DynamicDepositClosureData;
import com.advancly.fineract.portfolio.savings.domain.AdvanclyChargeInterestRule;
import com.advancly.fineract.portfolio.savings.domain.AdvanclyChargeInterestRuleRepository;
import com.advancly.fineract.portfolio.savings.domain.DepositAccountDynamicDetail;
import com.advancly.fineract.portfolio.savings.domain.DepositAccountDynamicRateHistory;
import com.advancly.fineract.portfolio.savings.domain.DepositAccountDynamicRateHistoryRepository;
import com.advancly.fineract.portfolio.savings.domain.DepositInterestChargeApplication;
import com.advancly.fineract.portfolio.savings.domain.DepositInterestChargeApplicationRepository;
import com.advancly.fineract.portfolio.savings.domain.DynamicDepositAccount;
import com.advancly.fineract.portfolio.savings.domain.DynamicDepositRateHistoryEventType;
import com.advancly.fineract.portfolio.savings.domain.DynamicDepositRateSource;
import com.advancly.fineract.portfolio.savings.domain.InterestBasisMode;
import com.advancly.fineract.portfolio.savings.helper.SavingsAccountTransactionHelper;
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
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.fineract.accounting.journalentry.service.JournalEntryWritePlatformService;
import org.apache.fineract.infrastructure.businessdate.domain.BusinessDateType;
import org.apache.fineract.infrastructure.configuration.domain.ConfigurationDomainService;
import org.apache.fineract.infrastructure.core.api.JsonCommand;
import org.apache.fineract.infrastructure.core.api.JsonQuery;
import org.apache.fineract.infrastructure.core.data.CommandProcessingResult;
import org.apache.fineract.infrastructure.core.data.EnumOptionData;
import org.apache.fineract.infrastructure.core.exception.ErrorHandler;
import org.apache.fineract.infrastructure.core.exception.GeneralPlatformDomainRuleException;
import org.apache.fineract.infrastructure.core.exception.PlatformApiDataValidationException;
import org.apache.fineract.infrastructure.core.serialization.FromJsonHelper;
import org.apache.fineract.infrastructure.core.service.ThreadLocalContextUtil;
import org.apache.fineract.infrastructure.dataqueries.service.EntityDatatableChecksWritePlatformService;
import org.apache.fineract.infrastructure.event.business.service.BusinessEventNotifierService;
import org.apache.fineract.infrastructure.security.service.PlatformSecurityContext;
import org.apache.fineract.organisation.holiday.domain.HolidayRepositoryWrapper;
import org.apache.fineract.organisation.monetary.domain.MonetaryCurrency;
import org.apache.fineract.organisation.monetary.domain.Money;
import org.apache.fineract.organisation.monetary.domain.MoneyHelper;
import org.apache.fineract.organisation.office.domain.Office;
import org.apache.fineract.organisation.staff.domain.StaffRepositoryWrapper;
import org.apache.fineract.organisation.workingdays.domain.WorkingDaysRepositoryWrapper;
import org.apache.fineract.portfolio.account.domain.StandingInstructionRepository;
import org.apache.fineract.portfolio.account.service.AccountAssociationsReadPlatformService;
import org.apache.fineract.portfolio.account.service.AccountTransfersReadPlatformService;
import org.apache.fineract.portfolio.charge.domain.Charge;
import org.apache.fineract.portfolio.charge.domain.ChargeCalculationType;
import org.apache.fineract.portfolio.charge.domain.ChargeRepositoryWrapper;
import org.apache.fineract.portfolio.client.domain.Client;
import org.apache.fineract.portfolio.note.domain.NoteRepository;
import org.apache.fineract.portfolio.paymentdetail.service.PaymentDetailWritePlatformService;
import org.apache.fineract.portfolio.paymenttype.service.PaymentTypeReadPlatformService;
import org.apache.fineract.portfolio.savings.SavingsAccountTransactionType;
import org.apache.fineract.portfolio.savings.WithHoldTaxPostingType;
import org.apache.fineract.portfolio.savings.data.SavingsAccountChargeDataValidator;
import org.apache.fineract.portfolio.savings.data.SavingsAccountDataValidator;
import org.apache.fineract.portfolio.savings.data.SavingsAccountTransactionDataValidator;
import org.apache.fineract.portfolio.savings.data.SavingsAccountTransactionEnumData;
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
import org.apache.fineract.portfolio.savings.service.SavingsAccountReadPlatformService;
import org.apache.fineract.portfolio.savings.service.SavingsAccountWritePlatformServiceJpaRepositoryImpl;
import org.apache.fineract.portfolio.savings.service.SavingsInterestReadPlatformService;
import org.apache.fineract.portfolio.tax.domain.TaxComponent;
import org.apache.fineract.portfolio.tax.domain.TaxGroup;
import org.apache.fineract.portfolio.tax.domain.TaxGroupMappings;
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
 * Closing a Dynamic Deposit with withdrawBalance=true settles the final period, then withdraws the balance in one
 * transaction leaving zero. The early-withdrawal charge service is real so its suppression is actually exercised.
 */
@ExtendWith(MockitoExtension.class)
class SavingsAccountWritePlatformServiceCloseDynamicDepositTest {

    private static final MonetaryCurrency CURRENCY = new MonetaryCurrency("USD", 2, null);
    private static final LocalDate ACTIVATION_DATE = LocalDate.of(2026, 1, 1);
    private static final LocalDate CLOSED_DATE = LocalDate.of(2026, 1, 20);
    private static final LocalDate MATURITY_AFTER_CLOSURE = LocalDate.of(2026, 7, 1);
    private static final LocalDate MATURITY_BEFORE_CLOSURE = LocalDate.of(2026, 1, 10);
    private static final BigDecimal OPENING_BALANCE = BigDecimal.valueOf(1000);
    private static final Long PRODUCT_ID = 10L;
    private static final Long CHARGE_ID = 55L;

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
    private AdvanclyChargeInterestRuleRepository chargeInterestRuleRepository;
    @Mock
    private DepositInterestChargeApplicationRepository interestChargeApplicationRepository;
    @Mock

    private SavingsAccountWritePlatformServiceJpaRepositoryImpl service;
    /** Real, so the forfeiture's balance deduction and journal entries are actually exercised. */
    private CumulativeInterestForfeitureService cumulativeInterestForfeitureService;
    private DynamicDepositAccount account;
    private Office office;

    private final List<DepositInterestChargeApplication> savedApplications = new ArrayList<>();
    private final List<SavingsAccountTransaction> withdrawals = new ArrayList<>();
    private final List<Map<String, Object>> journalledTransactions = new ArrayList<>();
    private long nextTransactionId = 1000L;

    @BeforeEach
    void setUp() {
        MoneyHelperInitializer.initialize();
        this.savedApplications.clear();
        this.withdrawals.clear();
        this.journalledTransactions.clear();
        this.nextTransactionId = 1000L;

        lenient().when(this.interestChargeApplicationRepository.sumActiveAppliedAmountForAccount(anyLong())).thenReturn(BigDecimal.ZERO);
        lenient().when(this.interestChargeApplicationRepository.saveAndFlush(any())).thenAnswer(invocation -> {
            final DepositInterestChargeApplication application = invocation.getArgument(0);
            this.savedApplications.add(application);
            return application;
        });

        this.service = new SavingsAccountWritePlatformServiceJpaRepositoryImpl(context, fromApiJsonDeserializer,
                savingAccountRepositoryWrapper, staffRepository, savingsAccountTransactionRepository, savingAccountAssembler,
                savingsAccountTransactionDataValidator, savingsAccountChargeDataValidator, paymentDetailWritePlatformService,
                journalEntryWritePlatformService, savingsAccountDomainService, noteRepository, accountTransfersReadPlatformService,
                accountAssociationsReadPlatformService, chargeRepository, savingsAccountChargeRepository, holidayRepository,
                workingDaysRepository, configurationDomainService, depositAccountOnHoldTransactionRepository,
                entityDatatableChecksWritePlatformService, appuserRepository, standingInstructionRepository, businessEventNotifierService,
                gsimRepository, savingsAccountInterestPostingService, errorHandler);

        // Real and wired to the service under test, so force-posting takes the production path.
        this.cumulativeInterestForfeitureService = new CumulativeInterestForfeitureService(this.chargeInterestRuleRepository,
                this.interestChargeApplicationRepository, this.service, this.noteRepository, this.savingAccountRepositoryWrapper,
                this.journalEntryWritePlatformService);

        lenient().doAnswer(invocation -> {
            final Map<String, Object> bridgeData = invocation.getArgument(0);
            @SuppressWarnings("unchecked")
            final List<Map<String, Object>> newTransactions = (List<Map<String, Object>>) bridgeData.get("newSavingsTransactions");
            this.journalledTransactions.addAll(newTransactions);
            return null;
        }).when(this.journalEntryWritePlatformService).createJournalEntriesForSavings(any());

        // DynamicDepositAccount resolves its collaborators via this static locator, not Spring DI.
        final ApplicationContext applicationContext = mock(ApplicationContext.class);
        lenient().when(applicationContext.getBean(DepositAccountDynamicRateHistoryRepository.class)).thenReturn(this.rateHistoryRepository);
        lenient().when(applicationContext.getBean(CumulativeInterestForfeitureService.class))
                .thenReturn(this.cumulativeInterestForfeitureService);
        ReflectionTestUtils.setField(DynamicDepositServiceLocator.class, "applicationContext", applicationContext);
    }

    @Test
    void closingAtOrAfterMaturityChargesNothingAndStillSettlesToZero() {
        prepareDynamicDepositClosure(MATURITY_BEFORE_CLOSURE, accountCharge(new BigDecimal("60")));

        this.service.close(1L, closeCommandFor(1L, true));

        assertThat(this.account.isClosed()).isTrue();
        // Posted on the maturity date (like FixedDepositAccount#postMaturityInterest); accrual stops the day before.
        final BigDecimal grossInterest = singleInterestPosting(MATURITY_BEFORE_CLOSURE).getAmount();
        assertThat(grossInterest).isGreaterThan(BigDecimal.ZERO);
        assertThat(payChargeTransactions()).isEmpty();
        assertThat(this.savedApplications).isEmpty();

        assertThat(this.withdrawals).hasSize(1);
        assertThat(this.withdrawals.get(0).getAmount()).isEqualByComparingTo(OPENING_BALANCE.add(grossInterest));
        assertThat(this.account.getSummary().getAccountBalance()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void closingEarlyWithoutAChargeDrivenRuleChargesNothing() {
        prepareDynamicDepositClosure(MATURITY_AFTER_CLOSURE, accountCharge(new BigDecimal("60")));

        this.service.close(1L, closeCommandFor(1L, true));

        assertThat(this.account.isClosed()).isTrue();
        assertThat(this.account.isEarlyWithdrawal(CLOSED_DATE)).isTrue();
        final BigDecimal grossInterest = singleInterestPosting().getAmount();
        assertThat(payChargeTransactions()).isEmpty();
        assertThat(this.savedApplications).isEmpty();
        assertThat(this.withdrawals).hasSize(1);
        assertThat(this.withdrawals.get(0).getAmount()).isEqualByComparingTo(OPENING_BALANCE.add(grossInterest));
        assertThat(this.account.getSummary().getAccountBalance()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void closingAPlainSavingsAccountWithWithdrawBalanceNeverPostsExtraInterest() {
        // configurationDomainService is only used by the DD settlement's postInterest(...), so zero interactions
        // proves a plain account never enters that branch.
        final SavingsAccount plainAccount = new SavingsAccountTestBuilder().withId(2L).withSummary(
                new SavingsAccountSummaryTestBuilder().withAccountBalance(OPENING_BALANCE).withTotalDeposits(OPENING_BALANCE).build())
                .withActivationDate(ACTIVATION_DATE).build();
        ReflectionTestUtils.setField(plainAccount, "status", 300); // ACTIVE
        assertThat(plainAccount.depositAccountType().isDynamicDeposit()).isFalse();

        when(this.context.authenticatedUser()).thenReturn(mock(AppUser.class));
        when(this.savingAccountAssembler.assembleFrom(2L, false)).thenReturn(plainAccount);
        when(this.savingsAccountDomainService.handleWithdrawal(eq(plainAccount), any(), eq(CLOSED_DATE), eq(OPENING_BALANCE), any(), any(),
                eq(false))).thenAnswer(invocation -> {
                    final SavingsAccountTransaction withdrawal = SavingsAccountTransaction.withdrawal(plainAccount, mock(Office.class),
                            null, CLOSED_DATE, Money.of(CURRENCY, OPENING_BALANCE), "test-withdrawal-ref");
                    ReflectionTestUtils.setField(withdrawal, "id", 901L);
                    plainAccount.addTransaction(withdrawal);
                    // Mimic the real handleWithdrawal's balance update so close()'s zero-balance check passes.
                    ReflectionTestUtils.setField(plainAccount.getSummary(), "accountBalance", BigDecimal.ZERO);
                    return withdrawal;
                });

        final CommandProcessingResult result = this.service.close(2L, closeCommandFor(2L, true));

        assertThat(result).isNotNull();
        assertThat(plainAccount.isClosed()).isTrue();
        verify(this.savingsAccountDomainService).handleWithdrawal(eq(plainAccount), any(), eq(CLOSED_DATE), eq(OPENING_BALANCE), any(),
                any(), eq(false));
        verifyNoInteractions(this.configurationDomainService);
        verify(this.savingsAccountTransactionRepository, never()).save(any());
    }

    // Runs the real CumulativeInterestForfeitureService: the payout must be net of the forfeiture, the forfeiture
    // must be journalled, and the closure must still settle to zero.
    @Test
    void aCumulativeModeClosureForfeitsEverythingSettlesToZeroAndJournalsItsOwnForfeiture() {
        withBusinessDateOn(CLOSED_DATE);
        cumulativeProduct();
        prepareDynamicDepositClosure(MATURITY_AFTER_CLOSURE, accountCharge(new BigDecimal("100")));
        assignDatabaseIdsOnFlush();

        final CommandProcessingResult result = this.service.close(1L, closeCommandFor(1L, true));

        assertThat(result).isNotNull();
        assertThat(this.account.isClosed()).isTrue();

        final BigDecimal grossInterest = singleInterestPosting().getAmount();
        assertThat(grossInterest).isGreaterThan(BigDecimal.ZERO);

        final SavingsAccountTransaction forfeiture = singleForfeitureTransaction();
        assertThat(forfeiture.getAmount()).isEqualByComparingTo(grossInterest);
        assertThat(forfeiture.getTransactionDate()).isEqualTo(CLOSED_DATE);
        assertThat(payChargeTransactions()).isEmpty();

        // Payout is principal only: the forfeiture was deducted from the balance.
        assertThat(this.withdrawals).hasSize(1);
        assertThat(this.withdrawals.get(0).getAmount()).isEqualByComparingTo(OPENING_BALANCE);

        // Both the forced posting and the forfeiture are journalled exactly once, with their flushed ids.
        assertThat(journalledOfType(SavingsAccountTransactionType.INTEREST_POSTING)).hasSize(1);
        assertThat(journalledOfType(SavingsAccountTransactionType.INTEREST_CHARGE)).hasSize(1);
        assertThat(journalledOfType(SavingsAccountTransactionType.INTEREST_CHARGE).get(0).get("id")).isEqualTo(forfeiture.getId());
        assertThat(forfeiture.getId()).isNotNull();

        assertThat(this.account.getSummary().getAccountBalance()).isEqualByComparingTo(BigDecimal.ZERO);

        assertThat(this.savedApplications).hasSize(1);
        assertThat(this.savedApplications.get(0).interestBasisMode()).isEqualTo(InterestBasisMode.CUMULATIVE);
        assertThat(this.savedApplications.get(0).appliedAmount()).isEqualByComparingTo(forfeiture.getAmount());
    }

    @Test
    void aPartialCumulativeClosureTaxesPostedInterestNormallyAndForfeitsNetInterest() {
        withBusinessDateOn(CLOSED_DATE);
        cumulativeProduct();
        prepareDynamicDepositClosure(MATURITY_AFTER_CLOSURE, accountCharge(new BigDecimal("50")));
        withholdingTaxAt(new BigDecimal("10"));
        assignDatabaseIdsOnFlush();

        this.service.close(1L, closeCommandFor(1L, true));

        final BigDecimal grossInterest = singleInterestPosting().getAmount();
        final SavingsAccountTransaction forfeiture = singleForfeitureTransaction();
        final SavingsAccountTransaction tax = singleWithholdTaxTransaction();

        // Forfeiture is 50% of the posted interest net of WHT.
        assertThat(tax.getAmount()).isEqualByComparingTo(
                grossInterest.multiply(new BigDecimal("0.1")).setScale(tax.getAmount().scale(), MoneyHelper.getRoundingMode()));
        final BigDecimal netInterest = grossInterest.subtract(tax.getAmount());
        assertThat(forfeiture.getAmount()).isEqualByComparingTo(
                netInterest.multiply(new BigDecimal("0.5")).setScale(forfeiture.getAmount().scale(), MoneyHelper.getRoundingMode()));

        assertThat(this.withdrawals).hasSize(1);
        assertThat(this.withdrawals.get(0).getAmount())
                .isEqualByComparingTo(OPENING_BALANCE.add(grossInterest).subtract(forfeiture.getAmount()).subtract(tax.getAmount()));
        assertThat(this.account.getSummary().getAccountBalance()).isEqualByComparingTo(BigDecimal.ZERO);

        // Transactions written after the forced posting must be journalled here; nothing downstream sees them as new.
        assertThat(journalledOfType(SavingsAccountTransactionType.INTEREST_CHARGE)).hasSize(1);
        assertThat(journalledOfType(SavingsAccountTransactionType.WITHHOLD_TAX)).hasSize(1);
        assertThat(journalledOfType(SavingsAccountTransactionType.WITHHOLD_TAX).get(0).get("id")).isEqualTo(tax.getId());
    }

    // The forced posting is bounded by the business date, so a backdated cumulative closure would credit interest past
    // the closure date. The business date stays at MoneyHelperInitializer's default, after CLOSED_DATE.
    @Test
    void aBackdatedCumulativeClosureIsRejectedRatherThanPostingInterestPastTheClosureDate() {
        cumulativeProduct();
        prepareDynamicDepositClosure(MATURITY_AFTER_CLOSURE, accountCharge(new BigDecimal("100")));

        assertThatThrownBy(() -> this.service.close(1L, closeCommandFor(1L, true))).isInstanceOf(GeneralPlatformDomainRuleException.class);

        assertThat(this.account.isClosed()).isFalse();
        assertThat(this.withdrawals).isEmpty();
        assertThat(this.savedApplications).isEmpty();
        assertThat(this.account.getTransactions().stream().filter(SavingsAccountTransaction::isInterestPostingAndNotReversed).toList())
                .isEmpty();
    }

    // Core close() applies no per-period charge on premature closure; only the withdrawal/transfer wrappers do.
    @Test
    void aPerPeriodRuleAppliesNoChargeOnPrematureClosure() {
        final AdvanclyChargeInterestRule rule = mock(AdvanclyChargeInterestRule.class);
        lenient().when(rule.chargeId()).thenReturn(CHARGE_ID);
        lenient().when(rule.isCumulative()).thenReturn(false);
        lenient().when(this.chargeInterestRuleRepository.findBySavingsProductId(PRODUCT_ID)).thenReturn(List.of(rule));
        prepareDynamicDepositClosure(MATURITY_AFTER_CLOSURE, accountCharge(new BigDecimal("60")));

        this.service.close(1L, closeCommandFor(1L, true));

        assertThat(this.account.isEarlyWithdrawal(CLOSED_DATE)).isTrue();
        final BigDecimal grossInterest = singleInterestPosting().getAmount();
        assertThat(payChargeTransactions()).isEmpty();
        assertThat(this.account.getTransactions().stream().filter(SavingsAccountTransaction::isInterestChargeAndNotReversed).toList())
                .isEmpty();
        assertThat(this.savedApplications).isEmpty();
        assertThat(this.withdrawals).hasSize(1);
        assertThat(this.withdrawals.get(0).getAmount()).isEqualByComparingTo(OPENING_BALANCE.add(grossInterest));
        assertThat(this.account.getSummary().getAccountBalance()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    // A transfer payout re-runs applyIfApplicable on its withdrawal; a closure must still forfeit only once.
    @Test
    void aCumulativeForfeitureIsNotAppliedAgainWhenTheClosurePayoutIsAnAccountTransfer() {
        withBusinessDateOn(CLOSED_DATE);
        cumulativeProduct();
        prepareDynamicDepositClosure(MATURITY_AFTER_CLOSURE, accountCharge(new BigDecimal("50")));
        assignDatabaseIdsOnFlush();
        final AdvanclyInterestChargeApplicationService chargeApplicationService = new AdvanclyInterestChargeApplicationService(
                this.chargeInterestRuleRepository, this.interestChargeApplicationRepository, this.savingsAccountTransactionRepository,
                this.savingAccountRepositoryWrapper, mock(SavingsAccountTransactionHelper.class), this.journalEntryWritePlatformService,
                this.cumulativeInterestForfeitureService);

        final boolean coreStillOwesInterestPosting = this.account.beginClosureSettlement(CLOSED_DATE);
        assertThat(coreStillOwesInterestPosting).isFalse();
        final BigDecimal forfeitedOnce = singleForfeitureTransaction().getAmount();
        assertThat(this.savedApplications).hasSize(1);

        final SavingsAccountTransaction transferWithdrawal = SavingsAccountTransaction.withdrawal(this.account, this.office, null,
                CLOSED_DATE, Money.of(CURRENCY, this.account.getSummary().getAccountBalance()), "test-transfer-ref");
        ReflectionTestUtils.setField(transferWithdrawal, "id", 950L);
        this.account.addTransaction(transferWithdrawal);
        chargeApplicationService.applyIfApplicable(this.account, transferWithdrawal, false, null, null, null, false, false);

        assertThat(this.savedApplications).hasSize(1);
        assertThat(this.account.getTransactions().stream().filter(SavingsAccountTransaction::isInterestChargeAndNotReversed).toList())
                .hasSize(1);
        assertThat(singleForfeitureTransaction().getAmount()).isEqualByComparingTo(forfeitedOnce);
    }

    // The transfer wrapper's applyIfApplicable must not charge (or throw for missing dates) on a closure payout.
    @Test
    void aPerPeriodRuleIsNotAppliedWhenTheClosurePayoutIsAnAccountTransfer() {
        withBusinessDateOn(CLOSED_DATE);
        final AdvanclyChargeInterestRule rule = mock(AdvanclyChargeInterestRule.class);
        lenient().when(rule.chargeId()).thenReturn(CHARGE_ID);
        lenient().when(rule.isCumulative()).thenReturn(false);
        lenient().when(this.chargeInterestRuleRepository.findBySavingsProductId(PRODUCT_ID)).thenReturn(List.of(rule));
        prepareDynamicDepositClosure(MATURITY_AFTER_CLOSURE, accountCharge(new BigDecimal("60")));
        assignDatabaseIdsOnFlush();
        final AdvanclyInterestChargeApplicationService chargeApplicationService = new AdvanclyInterestChargeApplicationService(
                this.chargeInterestRuleRepository, this.interestChargeApplicationRepository, this.savingsAccountTransactionRepository,
                this.savingAccountRepositoryWrapper, mock(SavingsAccountTransactionHelper.class), this.journalEntryWritePlatformService,
                this.cumulativeInterestForfeitureService);

        this.account.beginClosureSettlement(CLOSED_DATE);
        final SavingsAccountTransaction transferWithdrawal = SavingsAccountTransaction.withdrawal(this.account, this.office, null,
                CLOSED_DATE, Money.of(CURRENCY, OPENING_BALANCE), "test-transfer-ref");
        ReflectionTestUtils.setField(transferWithdrawal, "id", 950L);
        this.account.addTransaction(transferWithdrawal);

        final SavingsAccountTransaction charge = chargeApplicationService.applyIfApplicable(this.account, transferWithdrawal, false, null,
                null, null, false, false);

        assertThat(charge).isNull();
        assertThat(this.savedApplications).isEmpty();
        assertThat(this.account.getTransactions().stream().filter(SavingsAccountTransaction::isInterestChargeAndNotReversed).toList())
                .isEmpty();
    }

    // Preview scenarios preview one account, then close a fresh identical one and compare.
    @Test
    void thePreviewOfAPartialCumulativeEarlyClosureEqualsWhatTheClosureWithdraws() {
        withBusinessDateOn(CLOSED_DATE);
        cumulativeProduct();
        prepareDynamicDepositClosure(MATURITY_AFTER_CLOSURE, accountCharge(new BigDecimal("50")));
        withholdingTaxAt(new BigDecimal("10"));

        final DynamicDepositClosureData preview = closureReadService().calculatePrematureAmount(1L, closeQuery(null));

        assertThat(preview.isEarlyClosure()).isTrue();
        assertThat(preview.isForfeitureApplicable()).isTrue();
        assertThat(preview.getForfeiturePercentage()).isEqualByComparingTo(new BigDecimal("50"));
        assertThat(preview.getForfeitureAmount()).isGreaterThan(BigDecimal.ZERO);
        assertThat(preview.getPrincipal()).isEqualByComparingTo(OPENING_BALANCE);

        prepareDynamicDepositClosure(MATURITY_AFTER_CLOSURE, accountCharge(new BigDecimal("50")));
        withholdingTaxAt(new BigDecimal("10"));
        assignDatabaseIdsOnFlush();
        this.service.close(1L, closeCommandFor(1L, true));

        assertThat(this.withdrawals).hasSize(1);
        assertThat(preview.getMaturityAmount()).isEqualByComparingTo(this.withdrawals.get(0).getAmount());
        assertThat(preview.getForfeitureAmount()).isEqualByComparingTo(singleForfeitureTransaction().getAmount());
        assertThat(preview.getInterestPosted()).isEqualByComparingTo(singleInterestPosting().getAmount());
        assertThat(preview.getWithholdTax()).isEqualByComparingTo(singleWithholdTaxTransaction().getAmount());
    }

    @Test
    void thePreviewHonoursAnEarlyWithdrawalChargePercentageOverride() {
        withBusinessDateOn(CLOSED_DATE);
        cumulativeProduct();
        prepareDynamicDepositClosure(MATURITY_AFTER_CLOSURE, accountCharge(new BigDecimal("50")));

        final DynamicDepositClosureData atConfiguredPercentage = closureReadService().calculatePrematureAmount(1L, closeQuery(null));
        prepareDynamicDepositClosure(MATURITY_AFTER_CLOSURE, accountCharge(new BigDecimal("50")));
        final DynamicDepositClosureData overridden = closureReadService().calculatePrematureAmount(1L, closeQuery(new BigDecimal("100")));

        assertThat(overridden.getForfeiturePercentage()).isEqualByComparingTo(new BigDecimal("100"));
        assertThat(overridden.getForfeitureAmount()).isGreaterThan(atConfiguredPercentage.getForfeitureAmount());
        assertThat(overridden.getMaturityAmount()).isEqualByComparingTo(OPENING_BALANCE);
    }

    @Test
    void thePreviewOfAnEarlyClosureWithoutAChargeRuleForfeitsNothingAndEqualsTheClosurePayout() {
        withBusinessDateOn(CLOSED_DATE);
        prepareDynamicDepositClosure(MATURITY_AFTER_CLOSURE, accountCharge(new BigDecimal("60")));

        final DynamicDepositClosureData preview = closureReadService().calculatePrematureAmount(1L, closeQuery(null));

        assertThat(preview.isForfeitureApplicable()).isFalse();
        assertThat(preview.getForfeitureAmount()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(preview.getForfeiturePercentage()).isNull();

        prepareDynamicDepositClosure(MATURITY_AFTER_CLOSURE, accountCharge(new BigDecimal("60")));
        this.service.close(1L, closeCommandFor(1L, true));
        assertThat(preview.getMaturityAmount()).isEqualByComparingTo(this.withdrawals.get(0).getAmount());
    }

    @Test
    void aPerPeriodRulePreviewForfeitsNothingBecauseAClosureAppliesNoPerPeriodCharge() {
        withBusinessDateOn(CLOSED_DATE);
        final AdvanclyChargeInterestRule rule = mock(AdvanclyChargeInterestRule.class);
        lenient().when(rule.chargeId()).thenReturn(CHARGE_ID);
        lenient().when(rule.isCumulative()).thenReturn(false);
        lenient().when(this.chargeInterestRuleRepository.findBySavingsProductId(PRODUCT_ID)).thenReturn(List.of(rule));
        prepareDynamicDepositClosure(MATURITY_AFTER_CLOSURE, accountCharge(new BigDecimal("60")));

        final DynamicDepositClosureData preview = closureReadService().calculatePrematureAmount(1L, closeQuery(null));

        assertThat(preview.isForfeitureApplicable()).isFalse();
        assertThat(preview.getMaturityAmount()).isGreaterThan(OPENING_BALANCE);
    }

    @Test
    void theClosureTemplateProjectsTheMaturityPayoutWithoutForfeiture() {
        withBusinessDateOn(CLOSED_DATE);
        cumulativeProduct();
        prepareDynamicDepositClosure(MATURITY_AFTER_CLOSURE, accountCharge(new BigDecimal("50")));

        final DynamicDepositClosureData template = closureReadService().retrieveClosureTemplate(1L);

        assertThat(template.getClosedOnDate()).isEqualTo(MATURITY_AFTER_CLOSURE);
        assertThat(template.isEarlyClosure()).isFalse();
        assertThat(template.isForfeitureApplicable()).isFalse();
        assertThat(template.getMaturityAmount()).isGreaterThan(OPENING_BALANCE);
        assertThat(template.getOnAccountClosureOptions()).extracting(EnumOptionData::getId).containsExactly(100L, 200L, 300L, 400L);
    }

    @Test
    void aPrematureClosurePreviewOffersNoReinvestOption() {
        withBusinessDateOn(CLOSED_DATE);
        prepareDynamicDepositClosure(MATURITY_AFTER_CLOSURE, accountCharge(new BigDecimal("60")));

        final DynamicDepositClosureData preview = closureReadService().calculatePrematureAmount(1L, closeQuery(null));

        assertThat(preview.getOnAccountClosureOptions()).extracting(EnumOptionData::getId).containsExactly(100L, 200L);
    }

    @Test
    void thePreviewRefusesABackdatedCumulativeClosureJustAsTheClosureDoes() {
        // Business date left well after CLOSED_DATE, so this closure is backdated.
        cumulativeProduct();
        prepareDynamicDepositClosure(MATURITY_AFTER_CLOSURE, accountCharge(new BigDecimal("100")));

        assertThatThrownBy(() -> closureReadService().calculatePrematureAmount(1L, closeQuery(null)))
                .isInstanceOf(GeneralPlatformDomainRuleException.class);
    }

    @Test
    void thePreviewRefusesAPrematureClosureOnOrAfterMaturity() {
        withBusinessDateOn(CLOSED_DATE);
        prepareDynamicDepositClosure(MATURITY_BEFORE_CLOSURE, accountCharge(new BigDecimal("60")));

        assertThatThrownBy(() -> closureReadService().calculatePrematureAmount(1L, closeQuery(null)))
                .isInstanceOf(PlatformApiDataValidationException.class);
    }

    // A closure withholds MATURITY-type tax (see AdvanclyDepositAccountDomainService), so the preview must too.
    @Test
    void thePreviewTaxesInterestThatIsConfiguredToBeWithheldAtMaturityAsTheClosureDoes() {
        withBusinessDateOn(CLOSED_DATE);
        prepareDynamicDepositClosure(MATURITY_AFTER_CLOSURE, accountCharge(new BigDecimal("60")));
        withholdingTaxAt(new BigDecimal("10"));
        ReflectionTestUtils.setField(this.account.accountTermAndPreClosure(), "withHoldTaxPostingType",
                WithHoldTaxPostingType.MATURITY.getValue());

        final DynamicDepositClosureData preview = closureReadService().calculatePrematureAmount(1L, closeQuery(null));

        assertThat(preview.getWithholdTax()).isGreaterThan(BigDecimal.ZERO);
        assertThat(preview.getWithholdTax()).isEqualByComparingTo(
                preview.getInterestPosted().multiply(new BigDecimal("0.1")).setScale(2, java.math.RoundingMode.HALF_UP));
        assertThat(preview.getMaturityAmount())
                .isEqualByComparingTo(OPENING_BALANCE.add(preview.getInterestPosted()).subtract(preview.getWithholdTax()));
    }

    @Test
    void theTemplateNamesTheLinkedAccountTheInterestWillBeSentTo() {
        withBusinessDateOn(CLOSED_DATE);
        prepareDynamicDepositClosure(MATURITY_AFTER_CLOSURE, accountCharge(new BigDecimal("60")));
        final org.apache.fineract.portfolio.account.data.PortfolioAccountData linked = mock(
                org.apache.fineract.portfolio.account.data.PortfolioAccountData.class);
        when(linked.getId()).thenReturn(77L);
        when(linked.getAccountNo()).thenReturn("000000077");
        when(this.accountAssociationsReadPlatformService.retriveSavingsLinkedAssociation(1L)).thenReturn(linked);

        final DynamicDepositClosureData template = closureReadService().retrieveClosureTemplate(1L);

        assertThat(template.getLinkedAccountId()).isEqualTo(77L);
        assertThat(template.getLinkedAccountNo()).isEqualTo("000000077");
    }

    @Test
    void theTemplateHasNoLinkedAccountWhenThereIsNone() {
        withBusinessDateOn(CLOSED_DATE);
        prepareDynamicDepositClosure(MATURITY_AFTER_CLOSURE, accountCharge(new BigDecimal("60")));

        final DynamicDepositClosureData template = closureReadService().retrieveClosureTemplate(1L);

        assertThat(template.getLinkedAccountId()).isNull();
        assertThat(template.getLinkedAccountNo()).isNull();
    }

    private DynamicDepositAccountClosureReadPlatformServiceImpl closureReadService() {
        when(this.savingAccountRepositoryWrapper.findOneWithNotFoundDetection(1L)).thenReturn(this.account);
        return new DynamicDepositAccountClosureReadPlatformServiceImpl(this.context, this.savingAccountRepositoryWrapper,
                this.savingAccountAssembler, this.configurationDomainService, new FromJsonHelper(),
                this.cumulativeInterestForfeitureService,
                new AdvanclyInterestChargeApplicationService(this.chargeInterestRuleRepository, this.interestChargeApplicationRepository,
                        this.savingsAccountTransactionRepository, this.savingAccountRepositoryWrapper,
                        mock(SavingsAccountTransactionHelper.class), this.journalEntryWritePlatformService,
                        this.cumulativeInterestForfeitureService),
                mock(PaymentTypeReadPlatformService.class), mock(SavingsAccountReadPlatformService.class),
                this.accountAssociationsReadPlatformService);
    }

    private static JsonQuery closeQuery(final BigDecimal earlyWithdrawalChargePercentage) {
        final JsonObject payload = new JsonObject();
        payload.addProperty("dateFormat", "dd MMMM yyyy");
        payload.addProperty("locale", "en");
        payload.addProperty("closedOnDate", "20 January 2026");
        if (earlyWithdrawalChargePercentage != null) {
            payload.addProperty("earlyWithdrawalChargePercentage", earlyWithdrawalChargePercentage);
        }
        final String json = payload.toString();
        final FromJsonHelper fromJsonHelper = new FromJsonHelper();
        return JsonQuery.from(json, JsonParser.parseString(json), fromJsonHelper);
    }

    @Test
    void aPercentageOverrideSetOnTheAccountIsTheOneTheClosureForfeitsAt() {
        withBusinessDateOn(CLOSED_DATE);
        cumulativeProduct();
        prepareDynamicDepositClosure(MATURITY_AFTER_CLOSURE, accountCharge(new BigDecimal("50")));
        assignDatabaseIdsOnFlush();

        this.account.setWithdrawalChargePercentageOverride(new BigDecimal("100"));
        this.account.beginClosureSettlement(CLOSED_DATE);

        assertThat(singleForfeitureTransaction().getAmount()).isEqualByComparingTo(singleInterestPosting().getAmount());
    }

    @Test
    void withoutAnOverrideTheClosureForfeitsAtTheConfiguredPercentage() {
        withBusinessDateOn(CLOSED_DATE);
        cumulativeProduct();
        prepareDynamicDepositClosure(MATURITY_AFTER_CLOSURE, accountCharge(new BigDecimal("50")));
        assignDatabaseIdsOnFlush();

        this.account.beginClosureSettlement(CLOSED_DATE);

        assertThat(singleForfeitureTransaction().getAmount())
                .isEqualByComparingTo(singleInterestPosting().getAmount().multiply(new BigDecimal("0.5")));
    }

    // The generic savings close route rejects this; the dedicated DD prematureClose route allows it.
    @Test
    void closingEarlyWithAFundWithdrawalIsRejectedOnTheGenericSavingsRouteWhenWithdrawalIsDisallowed() {
        prepareDynamicDepositClosure(MATURITY_AFTER_CLOSURE);
        this.account.setDynamicDetail(DepositAccountDynamicDetail.createNew(this.account, false, false));

        assertThatThrownBy(() -> this.service.close(1L, closeCommandFor(1L, true))).isInstanceOf(GeneralPlatformDomainRuleException.class);

        assertThat(this.account.isClosed()).isFalse();
        assertThat(payChargeTransactions()).isEmpty();
        assertThat(this.withdrawals).isEmpty();
    }

    // === fixtures ===

    private SavingsAccountTransaction singleInterestPosting() {
        return singleInterestPosting(CLOSED_DATE);
    }

    /** Expected posting date: CLOSED_DATE for premature closures, the maturity date otherwise. */
    private SavingsAccountTransaction singleInterestPosting(final LocalDate expectedPostingDate) {
        final List<SavingsAccountTransaction> postings = this.account.getTransactions().stream()
                .filter(SavingsAccountTransaction::isInterestPostingAndNotReversed).toList();
        assertThat(postings).hasSize(1);
        assertThat(postings.get(0).getTransactionDate()).isEqualTo(expectedPostingDate);
        return postings.get(0);
    }

    private List<SavingsAccountTransaction> payChargeTransactions() {
        return this.account.getTransactions().stream().filter(SavingsAccountTransaction::isPayCharge).toList();
    }

    private SavingsAccountTransaction singleForfeitureTransaction() {
        final List<SavingsAccountTransaction> forfeitures = this.account.getTransactions().stream()
                .filter(SavingsAccountTransaction::isInterestChargeAndNotReversed).toList();
        assertThat(forfeitures).hasSize(1);
        return forfeitures.get(0);
    }

    private SavingsAccountTransaction singleWithholdTaxTransaction() {
        final List<SavingsAccountTransaction> taxTransactions = this.account.getTransactions().stream()
                .filter(SavingsAccountTransaction::isWithHoldTaxAndNotReversed).toList();
        assertThat(taxTransactions).hasSize(1);
        return taxTransactions.get(0);
    }

    private List<Map<String, Object>> journalledOfType(final SavingsAccountTransactionType type) {
        return this.journalledTransactions.stream()
                .filter(transaction -> ((SavingsAccountTransactionEnumData) transaction.get("type")).getTransactionTypeEnum() == type)
                .toList();
    }

    private void cumulativeProduct() {
        final AdvanclyChargeInterestRule rule = mock(AdvanclyChargeInterestRule.class);
        lenient().when(rule.chargeId()).thenReturn(CHARGE_ID);
        lenient().when(rule.isCumulative()).thenReturn(true);
        lenient().when(this.chargeInterestRuleRepository.findBySavingsProductId(PRODUCT_ID)).thenReturn(List.of(rule));
    }

    /** Cumulative mode rejects backdated closures, so these tests close on the business date as production must. */
    private void withBusinessDateOn(final LocalDate businessDate) {
        final HashMap<BusinessDateType, LocalDate> businessDates = new HashMap<>();
        businessDates.put(BusinessDateType.BUSINESS_DATE, businessDate);
        businessDates.put(BusinessDateType.COB_DATE, businessDate.minusDays(1));
        ThreadLocalContextUtil.setBusinessDates(businessDates);
    }

    /** Fakes IDENTITY id assignment on flush so deriveAccountingBridgeData can tell new transactions from old. */
    private void assignDatabaseIdsOnFlush() {
        lenient().when(this.savingAccountRepositoryWrapper.saveAndFlush(any(SavingsAccount.class))).thenAnswer(invocation -> {
            for (final SavingsAccountTransaction transaction : this.account.getTransactions()) {
                if (transaction.getId() == null) {
                    ReflectionTestUtils.setField(transaction, "id", this.nextTransactionId++);
                }
            }
            return invocation.getArgument(0);
        });
    }

    private void withholdingTaxAt(final BigDecimal percentage) {
        final TaxComponent component = mock(TaxComponent.class);
        lenient().when(component.getApplicablePercentage(any())).thenReturn(percentage);
        final TaxGroupMappings mappings = mock(TaxGroupMappings.class);
        lenient().when(mappings.occursOnDayFromAndUpToAndIncluding(any())).thenReturn(true);
        lenient().when(mappings.getTaxComponent()).thenReturn(component);
        final TaxGroup taxGroup = mock(TaxGroup.class);
        lenient().when(taxGroup.getTaxGroupMappings()).thenReturn(Set.of(mappings));

        final DepositAccountTermAndPreClosure term = DepositAccountTermAndPreClosure.createNew(null, null, null, null, null, null, null,
                null, null, null, false, null, WithHoldTaxPostingType.INTEREST_POSTING);
        term.updateMaturityDetails(null, MATURITY_AFTER_CLOSURE);
        ReflectionTestUtils.setField(this.account, "accountTermAndPreClosure", term);
        ReflectionTestUtils.setField(this.account, "taxGroup", taxGroup);
        this.account.setWithHoldTax(true);
    }

    /** Stubs close()'s collaborators; the domain service performs a real, id-bearing withdrawal. */
    private void prepareDynamicDepositClosure(final LocalDate maturityDate, final SavingsAccountCharge... charges) {
        this.account = buildAccount(maturityDate, charges);

        // Single 2% rate-history row from activation.
        final SavingsAccountTransaction openingDeposit = depositTransaction(1L, ACTIVATION_DATE, OPENING_BALANCE);
        lenient().when(this.rateHistoryRepository.findByAccountIdOrderByTransactionDateAscIdAsc(this.account.getId()))
                .thenReturn(List.of(DepositAccountDynamicRateHistory.createNew(this.account, openingDeposit, ACTIVATION_DATE,
                        DynamicDepositRateHistoryEventType.ACCOUNT_ACTIVATION, OPENING_BALANCE, 12, 2, null, null, BigDecimal.valueOf(2),
                        BigDecimal.valueOf(2), DynamicDepositRateSource.INTEREST_RATE_CHART)));

        lenient().when(this.configurationDomainService.isSavingsInterestPostingAtCurrentPeriodEnd()).thenReturn(false);
        lenient().when(this.configurationDomainService.retrieveFinancialYearBeginningMonth()).thenReturn(1);

        // lenient: settlement-only scenarios never reach close().
        lenient().when(this.context.authenticatedUser()).thenReturn(mock(AppUser.class));
        lenient().when(this.savingAccountAssembler.assembleFrom(1L, false)).thenReturn(this.account);

        // lenient: unused where a guard rejects the closure first.
        lenient().when(
                this.savingsAccountDomainService.handleWithdrawal(eq(this.account), any(), eq(CLOSED_DATE), any(), any(), any(), eq(false)))
                .thenAnswer(invocation -> {
                    final BigDecimal amount = invocation.getArgument(3);
                    final SavingsAccountTransaction withdrawal = SavingsAccountTransaction.withdrawal(this.account, this.office, null,
                            CLOSED_DATE, Money.of(CURRENCY, amount), "test-withdrawal-ref");
                    // Real id, as the DB would have assigned; the closure's charge row references it.
                    ReflectionTestUtils.setField(withdrawal, "id", 900L + this.withdrawals.size());
                    this.account.addTransaction(withdrawal);
                    final BigDecimal balanceBefore = this.account.getSummary().getAccountBalance();
                    ReflectionTestUtils.setField(this.account.getSummary(), "accountBalance", balanceBefore.subtract(amount));
                    this.withdrawals.add(withdrawal);
                    return withdrawal;
                });
    }

    private SavingsAccountCharge accountCharge(final BigDecimal percentage) {
        final Charge definition = mock(Charge.class);
        lenient().when(definition.getId()).thenReturn(CHARGE_ID);
        lenient().when(definition.getAmount()).thenReturn(percentage);
        lenient().when(definition.isPenalty()).thenReturn(true);
        lenient().when(definition.isActive()).thenReturn(true);
        lenient().when(definition.getChargeCalculation()).thenReturn(ChargeCalculationType.PERCENT_OF_INTEREST.getValue());

        final SavingsAccountCharge accountCharge = mock(SavingsAccountCharge.class);
        lenient().when(accountCharge.getCharge()).thenReturn(definition);
        lenient().when(accountCharge.getPercentage()).thenReturn(percentage);
        lenient().when(accountCharge.isActive()).thenReturn(true);
        lenient().when(accountCharge.isPenaltyCharge()).thenReturn(true);
        return accountCharge;
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

    private DynamicDepositAccount buildAccount(final LocalDate maturityDate, final SavingsAccountCharge... charges) {
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
        ReflectionTestUtils.setField(newAccount, "charges", new HashSet<>(Arrays.asList(charges)));

        final DepositAccountTermAndPreClosure term = DepositAccountTermAndPreClosure.createNew(null, null, null, null, null, null, null,
                null, null, null, false, null, null);
        term.updateMaturityDetails(null, maturityDate);
        ReflectionTestUtils.setField(newAccount, "accountTermAndPreClosure", term);

        final SavingsAccountSummary summary = new SavingsAccountSummaryTestBuilder().withAccountBalance(OPENING_BALANCE)
                .withTotalDeposits(OPENING_BALANCE).build();
        ReflectionTestUtils.setField(newAccount, "summary", summary);
        ReflectionTestUtils.setField(newAccount, "savingsAccountTransactionSummaryWrapper", new SavingsAccountTransactionSummaryWrapper());

        final AccountTransfersReadPlatformService accountTransfersReadPlatformServiceForHelper = mock(
                AccountTransfersReadPlatformService.class);
        lenient().when(accountTransfersReadPlatformServiceForHelper.fetchPostInterestTransactionIds(anyLong())).thenReturn(List.of());
        final SavingsInterestReadPlatformService savingsInterestReadPlatformService = mock(SavingsInterestReadPlatformService.class);
        ReflectionTestUtils.setField(newAccount, "savingsHelper",
                new SavingsHelper(accountTransfersReadPlatformServiceForHelper, savingsInterestReadPlatformService));

        this.office = mock(Office.class);
        lenient().when(this.office.getId()).thenReturn(1L);
        final Client client = mock(Client.class);
        lenient().when(client.getOffice()).thenReturn(this.office);
        lenient().when(client.officeId()).thenReturn(1L);
        lenient().when(client.getId()).thenReturn(1L);
        ReflectionTestUtils.setField(newAccount, "client", client);

        final SavingsProduct product = mock(SavingsProduct.class);
        lenient().when(product.getId()).thenReturn(PRODUCT_ID);
        ReflectionTestUtils.setField(newAccount, "product", product);

        // Withdrawals allowed by default; tests opt into the lock via setDynamicDetail(...).
        newAccount.setDynamicDetail(DepositAccountDynamicDetail.createNew(newAccount, true, false));

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
