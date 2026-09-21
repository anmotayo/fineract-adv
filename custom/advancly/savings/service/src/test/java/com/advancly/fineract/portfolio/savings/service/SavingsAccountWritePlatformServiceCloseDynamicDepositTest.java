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
import org.apache.fineract.infrastructure.core.data.CommandProcessingResult;
import org.apache.fineract.infrastructure.core.exception.ErrorHandler;
import org.apache.fineract.infrastructure.core.exception.GeneralPlatformDomainRuleException;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Phase 4 closure collection, unified design: closing a Dynamic Deposit account with {@code withdrawBalance=true} must
 * settle the final period first - posting the interest, any withholding tax, and ONE capped interest-based charge that
 * covers both the charges still pending from earlier withdrawals in that period AND the penalty this very closure
 * incurs - and only then withdraw the resulting balance in exactly ONE transaction, leaving precisely zero behind for
 * {@code SavingsAccount#close(...)}'s own {@code results.in.balance.not.zero} check.
 *
 * The early-withdrawal charge service is deliberately REAL here (only its repositories are mocked), so that the
 * settlement withdrawal genuinely runs through {@code recordIfApplicable(...)} and the suppression that stops it
 * creating a second, never-appliable pending row for the same closure is actually exercised rather than assumed.
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
    /**
     * Deliberately REAL: the cumulative-mode closure scenarios below exist to prove that a real forfeiture against a
     * real account really does deduct itself from the balance the closure then pays out, and really does get its own
     * journal entries - neither of which a mock can show.
     */
    private CumulativeInterestForfeitureService cumulativeInterestForfeitureService;
    private DynamicDepositAccount account;
    private Office office;

    /** Charge-ledger application rows written during the call under test. */
    private final List<DepositInterestChargeApplication> savedApplications = new ArrayList<>();
    /** Every withdrawal the (mocked) domain service was asked to make, to prove there is exactly one. */
    private final List<SavingsAccountTransaction> withdrawals = new ArrayList<>();
    /** Every transaction handed to the accounting bridge across all journal-posting calls, in order. */
    private final List<Map<String, Object>> journalledTransactions = new ArrayList<>();
    /** Stands in for the database's IDENTITY sequence - see {@link #assignDatabaseIdsOnFlush()}. */
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

        // Real, wired to the very service under test: its force-posting call goes through the same
        // postInterest(account, true, date, false) path production uses, not a stub.
        this.cumulativeInterestForfeitureService = new CumulativeInterestForfeitureService(this.chargeInterestRuleRepository,
                this.interestChargeApplicationRepository, this.service, this.noteRepository, this.savingAccountRepositoryWrapper,
                this.journalEntryWritePlatformService, mock(JdbcTemplate.class));

        // Everything handed to the accounting bridge, from every journal-posting call in the flow, so a test can ask
        // what actually reached the ledger rather than assuming.
        lenient().doAnswer(invocation -> {
            final Map<String, Object> bridgeData = invocation.getArgument(0);
            @SuppressWarnings("unchecked")
            final List<Map<String, Object>> newTransactions = (List<Map<String, Object>>) bridgeData.get("newSavingsTransactions");
            this.journalledTransactions.addAll(newTransactions);
            return null;
        }).when(this.journalEntryWritePlatformService).createJournalEntriesForSavings(any());

        // DynamicDepositAccount's postInterest/calculateInterestUsing/closure-settlement overrides resolve their
        // collaborators through this static locator rather than Spring DI (see DynamicDepositAccountInterestTest for
        // the same pattern).
        final ApplicationContext applicationContext = mock(ApplicationContext.class);
        lenient().when(applicationContext.getBean(DepositAccountDynamicRateHistoryRepository.class)).thenReturn(this.rateHistoryRepository);
        lenient().when(applicationContext.getBean(CumulativeInterestForfeitureService.class))
                .thenReturn(this.cumulativeInterestForfeitureService);
        ReflectionTestUtils.setField(DynamicDepositServiceLocator.class, "applicationContext", applicationContext);
    }

    // Scenario 1: closure at/after maturity is not an early withdrawal - no charge at all, exactly as closure behaved
    // before this feature existed.
    @Test
    void closingAtOrAfterMaturityChargesNothingAndStillSettlesToZero() {
        prepareDynamicDepositClosure(MATURITY_BEFORE_CLOSURE, accountCharge(new BigDecimal("60")));

        this.service.close(1L, closeCommandFor(1L, true));

        assertThat(this.account.isClosed()).isTrue();
        // Posted ON MATURITY_BEFORE_CLOSURE, not CLOSED_DATE: this closure is at/after maturity, so the interest
        // transaction is dated at maturity (mirrors FixedDepositAccount#postMaturityInterest), even though the
        // accrual itself is capped at MATURITY_BEFORE_CLOSURE.minusDays(1) - interest must never be calculated for
        // the maturity day itself (see DynamicDepositAccount#postInterest's javadoc).
        final BigDecimal grossInterest = singleInterestPosting(MATURITY_BEFORE_CLOSURE).getAmount();
        assertThat(grossInterest).isGreaterThan(BigDecimal.ZERO);
        assertThat(payChargeTransactions()).isEmpty();
        assertThat(this.savedApplications).isEmpty();

        // One withdrawal, for principal plus the whole (uncharged) interest, and nothing left over.
        assertThat(this.withdrawals).hasSize(1);
        assertThat(this.withdrawals.get(0).getAmount()).isEqualByComparingTo(OPENING_BALANCE.add(grossInterest));
        assertThat(this.account.getSummary().getAccountBalance()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    // Scenario 2: no charge-driven rule configured - same outcome as closing at maturity, even though the closure date
    // itself is well before maturity.
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

    // Scenario 3: regression guard - a plain savings account closes exactly as it always did.
    @Test
    void closingAPlainSavingsAccountWithWithdrawBalanceNeverPostsExtraInterest() {
        // Even with withdrawBalance=true and a positive balance - the exact condition that makes a Dynamic Deposit
        // account reach the new settlement - a plain SavingsAccount (depositAccountType() -> SAVINGS_DEPOSIT, the
        // base-class default) must behave exactly as before: handleWithdrawal runs, and nothing else.
        // configurationDomainService is the sole dependency the settlement's postInterest(...) call reaches for
        // (isSavingsInterestPostingAtCurrentPeriodEnd()/retrieveFinancialYearBeginningMonth()) that no other step of
        // close() touches, so zero interactions with it is decisive proof the new branch was never entered.
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
                    // The real handleWithdrawal implementation (mocked away here) updates the account summary as
                    // part of the withdrawal itself; replicate just that net effect so account.close()'s own
                    // "results.in.balance.not.zero" check - unrelated to this fix - sees what it would in production.
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

    // Scenario 4 (final-review findings C1/C2/C3): every other scenario in this file avoids the cumulative rule, so
    // none of them ever reaches beginClosureSettlement's cumulative branch. These last two do - and they run the REAL
    // CumulativeInterestForfeitureService against this real account, not a mock, which is what makes them able to
    // observe the three things a mocked forfeiture service structurally cannot:
    // (a) the account balance the closure reads to size its payout really is net of the forfeiture (it is not, unless
    // SavingsAccountTransactionSummaryWrapper counts INTEREST_FORFEITURE and the service refreshes the summary
    // afterwards), (b) the forfeiture transaction really reaches the accounting bridge (it does not, if it is written
    // before the caller takes its "already existing transaction ids" snapshot and nobody journals it here), and
    // (c) the closure still settles to exactly zero.
    @Test
    void aCumulativeModeClosureForfeitsEverythingSettlesToZeroAndJournalsItsOwnForfeiture() {
        withBusinessDateOn(CLOSED_DATE);
        cumulativeProduct();
        prepareDynamicDepositClosure(MATURITY_AFTER_CLOSURE, accountCharge(new BigDecimal("100")));
        assignDatabaseIdsOnFlush();

        final CommandProcessingResult result = this.service.close(1L, closeCommandFor(1L, true));

        assertThat(result).isNotNull();
        assertThat(this.account.isClosed()).isTrue();

        // The closing interest really was force-posted by the forfeiture service - dated exactly where core's own
        // per-period closure path posts it, not a day earlier.
        final BigDecimal grossInterest = singleInterestPosting().getAmount();
        assertThat(grossInterest).isGreaterThan(BigDecimal.ZERO);

        // ...and all of it forfeited, as one INTEREST_FORFEITURE transaction - not a per-period charge.
        final SavingsAccountTransaction forfeiture = singleForfeitureTransaction();
        assertThat(forfeiture.getAmount()).isEqualByComparingTo(grossInterest);
        assertThat(forfeiture.getTransactionDate()).isEqualTo(CLOSED_DATE);
        assertThat(payChargeTransactions()).isEmpty();

        // (a) The balance core read to size the settlement withdrawal is principal - it deducted the forfeiture.
        // Before the summary fix this would have been OPENING_BALANCE + grossInterest.
        assertThat(this.withdrawals).hasSize(1);
        assertThat(this.withdrawals.get(0).getAmount()).isEqualByComparingTo(OPENING_BALANCE);

        // (b) Both the forced posting and the forfeiture reached the accounting bridge, each exactly once, carrying
        // the ids the flush assigned them - the forfeiture is what regressed here, the posting is the control.
        assertThat(journalledOfType(SavingsAccountTransactionType.INTEREST_POSTING)).hasSize(1);
        assertThat(journalledOfType(SavingsAccountTransactionType.INTEREST_FORFEITURE)).hasSize(1);
        assertThat(journalledOfType(SavingsAccountTransactionType.INTEREST_FORFEITURE).get(0).get("id")).isEqualTo(forfeiture.getId());
        assertThat(forfeiture.getId()).isNotNull();

        // (c) Exactly zero left behind, which is core's own close() invariant.
        assertThat(this.account.getSummary().getAccountBalance()).isEqualByComparingTo(BigDecimal.ZERO);

        // The application-ledger row is written so a later reversal/report can see what was taken.
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

        // The forced posting keeps the account's real WHT configuration, so tax is computed by the normal posting
        // flow. The cumulative forfeiture then charges 50% of the posted interest net of that WHT.
        assertThat(tax.getAmount()).isEqualByComparingTo(
                grossInterest.multiply(new BigDecimal("0.1")).setScale(tax.getAmount().scale(), MoneyHelper.getRoundingMode()));
        final BigDecimal netInterest = grossInterest.subtract(tax.getAmount());
        assertThat(forfeiture.getAmount()).isEqualByComparingTo(
                netInterest.multiply(new BigDecimal("0.5")).setScale(forfeiture.getAmount().scale(), MoneyHelper.getRoundingMode()));

        // Principal plus whatever interest survived both, paid out in one withdrawal, leaving exactly zero.
        assertThat(this.withdrawals).hasSize(1);
        assertThat(this.withdrawals.get(0).getAmount())
                .isEqualByComparingTo(OPENING_BALANCE.add(grossInterest).subtract(forfeiture.getAmount()).subtract(tax.getAmount()));
        assertThat(this.account.getSummary().getAccountBalance()).isEqualByComparingTo(BigDecimal.ZERO);

        // Both of the transactions this service writes after the forced posting are journalled here, since nothing
        // downstream would ever classify them as new again.
        assertThat(journalledOfType(SavingsAccountTransactionType.INTEREST_FORFEITURE)).hasSize(1);
        assertThat(journalledOfType(SavingsAccountTransactionType.WITHHOLD_TAX)).hasSize(1);
        assertThat(journalledOfType(SavingsAccountTransactionType.WITHHOLD_TAX).get(0).get("id")).isEqualTo(tax.getId());
    }

    // The backdated-closure guard: the forfeiture service force-posts through the public postInterest(...) overload,
    // whose interest-calculation bound is always today's business date - so on a closure dated earlier it would credit
    // (and then partly forfeit) interest for days after the account closed. The business date is left at the default
    // MoneyHelperInitializer sets, which is well after CLOSED_DATE, making this closure backdated.
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

    // Task 3 (Phase 5): the account-rule withdrawal lock must reject a premature closure that would withdraw funds
    // OUTRIGHT - before any settlement (interest posting, charge collection) or the withdrawal itself runs - rather
    // than partially settling the account and only then refusing the payout.
    @Test
    void closingEarlyWithAFundWithdrawalIsRejectedWhenWithdrawalIsDisallowed() {
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

    /**
     * @param expectedPostingDate
     *            CLOSED_DATE for every premature-closure scenario in this file (uncapped - see
     *            SavingsAccount#interestPostingUpToForClosure's javadoc), or the account's maturityDate for a normal
     *            (at-or-after-maturity) closure - the posting transaction itself still lands ON the maturity day (it is
     *            the day after the last day interest accrues, maturityDate.minusDays(1); see
     *            DynamicDepositAccount#postInterest's javadoc for why the guard is deliberately left uncapped while
     *            only the accrual math is capped).
     */
    private SavingsAccountTransaction singleInterestPosting(final LocalDate expectedPostingDate) {
        final List<SavingsAccountTransaction> postings = this.account.getTransactions().stream()
                .filter(SavingsAccountTransaction::isInterestPostingAndNotReversed).toList();
        assertThat(postings).hasSize(1);
        assertThat(postings.get(0).getTransactionDate()).isEqualTo(expectedPostingDate);
        return postings.get(0);
    }

    private List<SavingsAccountTransaction> payChargeTransactions() {
        return this.account.getTransactions().stream().filter(SavingsAccountTransaction::isInterestBasedCharge).toList();
    }

    private SavingsAccountTransaction singleForfeitureTransaction() {
        final List<SavingsAccountTransaction> forfeitures = this.account.getTransactions().stream()
                .filter(SavingsAccountTransaction::isInterestForfeitureAndNotReversed).toList();
        assertThat(forfeitures).hasSize(1);
        return forfeitures.get(0);
    }

    private SavingsAccountTransaction singleWithholdTaxTransaction() {
        final List<SavingsAccountTransaction> taxTransactions = this.account.getTransactions().stream()
                .filter(SavingsAccountTransaction::isWithHoldTaxAndNotReversed).toList();
        assertThat(taxTransactions).hasSize(1);
        return taxTransactions.get(0);
    }

    /** What actually reached the accounting bridge, of one transaction type, across every journal-posting call. */
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

    /**
     * Cumulative mode rejects a BACKDATED premature closure (see DynamicDepositAccount#beginClosureSettlement), so the
     * only closure date it can ever see in production is today's business date - which is also what bounds the forced
     * interest posting. Aligning the two here is what makes these scenarios the shape production actually allows.
     */
    private void withBusinessDateOn(final LocalDate businessDate) {
        final HashMap<BusinessDateType, LocalDate> businessDates = new HashMap<>();
        businessDates.put(BusinessDateType.BUSINESS_DATE, businessDate);
        businessDates.put(BusinessDateType.COB_DATE, businessDate.minusDays(1));
        ThreadLocalContextUtil.setBusinessDates(businessDates);
    }

    /**
     * Stands in for Hibernate's IDENTITY id assignment on flush. Without it every transaction created during the call
     * keeps a null id, and {@code SavingsAccount#deriveAccountingBridgeData}'s "was this id already there?" test - the
     * exact mechanism finding C3 is about - cannot be exercised at all, since a null id is indistinguishable from the
     * null a not-yet-flushed transaction contributed to the snapshot.
     */
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

    /** A tax group whose single component withholds {@code percentage}% whenever it is consulted. */
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

    /**
     * Builds the Dynamic Deposit account under test and stubs everything close() needs, including a domain service that
     * behaves like the real one: it withdraws whatever amount close() asks for and leaves a real, dated, id-bearing
     * withdrawal transaction behind.
     */
    private void prepareDynamicDepositClosure(final LocalDate maturityDate, final SavingsAccountCharge... charges) {
        this.account = buildAccount(maturityDate, charges);

        // Single rate-history row (2% APR from activation) so calculateInterestUsing can resolve a rate for the final
        // partial period (Jan 1 - Jan 19).
        final SavingsAccountTransaction openingDeposit = depositTransaction(1L, ACTIVATION_DATE, OPENING_BALANCE);
        lenient().when(this.rateHistoryRepository.findByAccountIdOrderByTransactionDateAscIdAsc(this.account.getId()))
                .thenReturn(List.of(DepositAccountDynamicRateHistory.createNew(this.account, openingDeposit, ACTIVATION_DATE,
                        DynamicDepositRateHistoryEventType.ACCOUNT_ACTIVATION, OPENING_BALANCE, 12, 2, null, null, BigDecimal.valueOf(2),
                        BigDecimal.valueOf(2), DynamicDepositRateSource.INTEREST_RATE_CHART)));

        lenient().when(this.configurationDomainService.isSavingsInterestPostingAtCurrentPeriodEnd()).thenReturn(false);
        lenient().when(this.configurationDomainService.retrieveFinancialYearBeginningMonth()).thenReturn(1);

        when(this.context.authenticatedUser()).thenReturn(mock(AppUser.class));
        when(this.savingAccountAssembler.assembleFrom(1L, false)).thenReturn(this.account);

        // lenient: the Task 3 withdrawal-lock test deliberately never reaches this call - the guard rejects the
        // closure before any withdrawal is attempted - so this stub goes unused there by design.
        lenient().when(
                this.savingsAccountDomainService.handleWithdrawal(eq(this.account), any(), eq(CLOSED_DATE), any(), any(), any(), eq(false)))
                .thenAnswer(invocation -> {
                    final BigDecimal amount = invocation.getArgument(3);
                    final SavingsAccountTransaction withdrawal = SavingsAccountTransaction.withdrawal(this.account, this.office, null,
                            CLOSED_DATE, Money.of(CURRENCY, amount), "test-withdrawal-ref");
                    // A real id, exactly as the database would have assigned by this point in production (the real
                    // domain service persists the withdrawal to generate one before returning) - the closure's own
                    // charge row references this transaction.
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

        // Withdrawals allowed by default - the account-rule withdrawal lock (Phase 5) is opt-in per test via
        // setDynamicDetail(...) with allowWithdrawal=false; every scenario here relies on withdrawals being possible
        // unless it says otherwise.
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
