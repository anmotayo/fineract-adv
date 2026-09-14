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
import com.advancly.fineract.portfolio.savings.domain.DepositProductDynamicDetail;
import com.advancly.fineract.portfolio.savings.domain.DepositProductDynamicDetailRepository;
import com.advancly.fineract.portfolio.savings.domain.DepositProductEarlyWithdrawalCharge;
import com.advancly.fineract.portfolio.savings.domain.DepositProductEarlyWithdrawalChargeRepository;
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
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
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
import org.apache.fineract.portfolio.savings.service.SavingsInterestReadPlatformService;
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
    private DepositAccountInterestChargeRepository interestChargeRepository;
    @Mock
    private DepositProductEarlyWithdrawalChargeRepository productEarlyWithdrawalChargeRepository;
    @Mock
    private DepositProductDynamicDetailRepository productDynamicDetailRepository;

    private SavingsAccountWritePlatformServiceJpaRepositoryImpl service;
    private DynamicDepositAccount account;
    private Office office;

    /** Rows that already exist in m_deposit_account_interest_charge for the account under test. */
    private final List<DepositAccountInterestCharge> existingRows = new ArrayList<>();
    /** Rows written during the call under test - i.e. the closure's own already-applied row, if any. */
    private final List<DepositAccountInterestCharge> newlySavedRows = new ArrayList<>();
    /** Every withdrawal the (mocked) domain service was asked to make, to prove there is exactly one. */
    private final List<SavingsAccountTransaction> withdrawals = new ArrayList<>();

    @BeforeEach
    void setUp() {
        MoneyHelperInitializer.initialize();
        this.existingRows.clear();
        this.newlySavedRows.clear();
        this.withdrawals.clear();

        lenient().when(this.interestChargeRepository.findPendingByAccountIdUpTo(anyLong(), any())).thenAnswer(
                invocation -> new ArrayList<>(this.existingRows.stream().filter(DepositAccountInterestCharge::isPending).toList()));
        lenient().when(this.interestChargeRepository.saveAndFlush(any())).thenAnswer(invocation -> {
            final DepositAccountInterestCharge row = invocation.getArgument(0);
            this.newlySavedRows.add(row);
            return row;
        });
        lenient().when(this.interestChargeRepository.sumPendingChargeAmount(anyLong()))
                .thenAnswer(invocation -> allRows().stream().filter(DepositAccountInterestCharge::isPending)
                        .map(DepositAccountInterestCharge::chargeAmount).reduce(BigDecimal.ZERO, BigDecimal::add));
        lenient().when(this.interestChargeRepository.sumPostedChargeAmount(anyLong())).thenAnswer(invocation -> allRows().stream()
                .filter(row -> !row.isPending()).map(DepositAccountInterestCharge::chargeAmount).reduce(BigDecimal.ZERO, BigDecimal::add));

        lenient().when(this.productEarlyWithdrawalChargeRepository.findBySavingsProductId(PRODUCT_ID))
                .thenReturn(List.of(DepositProductEarlyWithdrawalCharge.createNew(PRODUCT_ID, CHARGE_ID)));
        lenient().when(this.productDynamicDetailRepository.findByProductId(PRODUCT_ID))
                .thenReturn(Optional.of(DepositProductDynamicDetail.createNew(null, true, false, true)));

        // The early-withdrawal charge service is real: the settlement withdrawal must genuinely pass through its
        // suppression guard rather than through a mock that would record nothing for any reason at all.
        final DynamicDepositEarlyWithdrawalChargeService earlyWithdrawalChargeService = new DynamicDepositEarlyWithdrawalChargeService(
                this.interestChargeRepository, this.productEarlyWithdrawalChargeRepository, this.productDynamicDetailRepository);

        // DynamicDepositAccount's postInterest/calculateInterestUsing/closure-settlement overrides resolve their
        // collaborators through this static locator rather than Spring DI (see DynamicDepositAccountInterestTest for
        // the same pattern).
        final ApplicationContext applicationContext = mock(ApplicationContext.class);
        lenient().when(applicationContext.getBean(DepositAccountDynamicRateHistoryRepository.class)).thenReturn(this.rateHistoryRepository);
        lenient().when(applicationContext.getBean(DepositAccountInterestChargeRepository.class)).thenReturn(this.interestChargeRepository);
        lenient().when(applicationContext.getBean(DynamicDepositEarlyWithdrawalChargeService.class))
                .thenReturn(earlyWithdrawalChargeService);
        ReflectionTestUtils.setField(DynamicDepositServiceLocator.class, "applicationContext", applicationContext);

        this.service = new SavingsAccountWritePlatformServiceJpaRepositoryImpl(context, fromApiJsonDeserializer,
                savingAccountRepositoryWrapper, staffRepository, savingsAccountTransactionRepository, savingAccountAssembler,
                savingsAccountTransactionDataValidator, savingsAccountChargeDataValidator, paymentDetailWritePlatformService,
                journalEntryWritePlatformService, savingsAccountDomainService, noteRepository, accountTransfersReadPlatformService,
                accountAssociationsReadPlatformService, chargeRepository, savingsAccountChargeRepository, holidayRepository,
                workingDaysRepository, configurationDomainService, depositAccountOnHoldTransactionRepository,
                entityDatatableChecksWritePlatformService, appuserRepository, standingInstructionRepository, businessEventNotifierService,
                gsimRepository, savingsAccountInterestPostingService, errorHandler);
    }

    // Scenario 1: one pending charge from an earlier early withdrawal, plus the closure's own contribution - two
    // contributions in the same period, capped once, paid by one charge transaction, settled by one withdrawal.
    @Test
    void aPendingChargeAndTheClosuresOwnContributionAreCappedTogetherAndSettledByOneWithdrawal() {
        final SavingsAccountCharge accountCharge = accountCharge(new BigDecimal("60"));
        prepareDynamicDepositClosure(MATURITY_AFTER_CLOSURE, accountCharge);
        // An earlier early withdrawal in this same period left this behind at 40%.
        final DepositAccountInterestCharge earlierRow = pendingRow(accountCharge, new BigDecimal("40"));

        final CommandProcessingResult result = this.service.close(1L, closeCommandFor(1L, true));

        assertThat(result).isNotNull();
        assertThat(this.account.isClosed()).isTrue();

        final BigDecimal grossInterest = singleInterestPosting().getAmount();
        assertThat(grossInterest).isGreaterThan(BigDecimal.ZERO);

        // 40% + 60% = 100% of gross interest: the whole interest is charged, and not a cent more.
        final SavingsAccountTransaction chargeTransaction = singleChargeTransaction();
        assertThat(chargeTransaction.getAmount()).isEqualByComparingTo(grossInterest);
        assertThat(chargeTransaction.getTransactionDate()).isEqualTo(CLOSED_DATE);

        // Both contributions were finalized: the pre-existing row here and now, the closure's own row created
        // already-applied against the settlement withdrawal.
        assertThat(earlierRow.isPending()).isFalse();
        assertThat(earlierRow.interestChargeTransaction()).isSameAs(chargeTransaction);
        assertThat(this.newlySavedRows).hasSize(1);
        final DepositAccountInterestCharge closureRow = this.newlySavedRows.get(0);
        assertThat(closureRow.isPending()).isFalse();
        assertThat(closureRow.chargePercentage()).isEqualByComparingTo("60");
        assertThat(closureRow.interestChargeTransaction()).isSameAs(chargeTransaction);
        assertThat(closureRow.interestPostingTransaction()).isSameAs(singleInterestPosting());
        assertThat(closureRow.withdrawalTransaction()).isSameAs(this.withdrawals.get(0));

        // The actual 40/60 split, not just its sum: recomputedChargeAmount(...)'s formula applied to each
        // contribution's own percentage, pro-rated against what the charge transaction actually moved (its total may
        // differ from the sum of the two contributions' raw recomputed amounts by a rounding cent, which is why the
        // pro-rata step, not the raw percentages, is what must be replicated here). Asserting only the SUM (as this
        // test previously did) would still pass for a wrong 0/100 or 50/50 split as long as the two shares added up
        // to the charge transaction's total - these assertions pin down each row's own amount.
        final MathContext pctMc = new MathContext(8, MoneyHelper.getRoundingMode());
        final BigDecimal recomputedEarlierShare = grossInterest.multiply(new BigDecimal("40")).divide(BigDecimal.valueOf(100), pctMc);
        final BigDecimal recomputedClosureShare = grossInterest.multiply(new BigDecimal("60")).divide(BigDecimal.valueOf(100), pctMc);
        final BigDecimal recomputedTotal = recomputedEarlierShare.add(recomputedClosureShare);
        final BigDecimal appliedTotal = chargeTransaction.getAmount();
        final BigDecimal expectedEarlierRowAmount = recomputedEarlierShare.multiply(appliedTotal).divide(recomputedTotal, pctMc)
                .setScale(CURRENCY.getDigitsAfterDecimal(), RoundingMode.DOWN);
        final BigDecimal expectedClosureRowAmount = appliedTotal.subtract(expectedEarlierRowAmount);

        assertThat(earlierRow.chargeAmount()).isEqualByComparingTo(expectedEarlierRowAmount);
        assertThat(closureRow.chargeAmount()).isEqualByComparingTo(expectedClosureRowAmount);
        // Sanity bound so a degenerate 0/100 (or any other wrong split) cannot slip past even if the hand-replicated
        // formula above were somehow also wrong: the smaller (40%) share must be a genuine, non-trivial fraction of
        // the total, clearly less than the larger (60%) share.
        assertThat(expectedEarlierRowAmount).isGreaterThan(BigDecimal.ZERO);
        assertThat(expectedClosureRowAmount).isGreaterThan(expectedEarlierRowAmount);

        assertThat(earlierRow.chargeAmount().add(closureRow.chargeAmount())).isEqualByComparingTo(chargeTransaction.getAmount());

        // Exactly ONE withdrawal, for the whole final balance, leaving exactly zero.
        assertThat(this.withdrawals).hasSize(1);
        assertThat(this.withdrawals.get(0).getAmount()).isEqualByComparingTo(OPENING_BALANCE);
        assertThat(this.account.getSummary().getAccountBalance()).isEqualByComparingTo(BigDecimal.ZERO);

        assertThat(this.account.interestBasedChargeDerived()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(this.account.interestBasedChargePostedDerived()).isEqualByComparingTo(chargeTransaction.getAmount());
    }

    // Scenario 2: three pending charges at 60% each plus a 60% closure contribution - 240% uncapped. The aggregate cap
    // must limit the lot to the interest actually posted, so principal is never touched.
    @Test
    void severalPendingChargesPlusTheClosureAreCappedInAggregateSoPrincipalIsNeverTouched() {
        final SavingsAccountCharge accountCharge = accountCharge(new BigDecimal("60"));
        prepareDynamicDepositClosure(MATURITY_AFTER_CLOSURE, accountCharge);
        final DepositAccountInterestCharge row1 = pendingRow(accountCharge, new BigDecimal("60"));
        final DepositAccountInterestCharge row2 = pendingRow(accountCharge, new BigDecimal("60"));
        final DepositAccountInterestCharge row3 = pendingRow(accountCharge, new BigDecimal("60"));

        this.service.close(1L, closeCommandFor(1L, true));

        final BigDecimal grossInterest = singleInterestPosting().getAmount();
        final SavingsAccountTransaction chargeTransaction = singleChargeTransaction();
        // 4 x 60% = 240% of gross, capped at gross (no withholding tax here) - never more.
        assertThat(chargeTransaction.getAmount()).isEqualByComparingTo(grossInterest);

        assertThat(this.newlySavedRows).hasSize(1);
        final DepositAccountInterestCharge closureRow = this.newlySavedRows.get(0);
        assertThat(List.of(row1, row2, row3).stream().allMatch(row -> !row.isPending())).isTrue();
        // The four shares sum to exactly what the single charge transaction moved - no more, no less.
        assertThat(row1.chargeAmount().add(row2.chargeAmount()).add(row3.chargeAmount()).add(closureRow.chargeAmount()))
                .isEqualByComparingTo(chargeTransaction.getAmount());

        // Principal is intact: the single withdrawal pays out exactly the opening balance (interest in, charge out).
        assertThat(this.withdrawals).hasSize(1);
        assertThat(this.withdrawals.get(0).getAmount()).isEqualByComparingTo(OPENING_BALANCE);
        assertThat(this.account.getSummary().getAccountBalance()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    // Scenario 3: closure at/after maturity is not an early withdrawal - no charge at all, exactly as closure behaved
    // before this feature existed.
    @Test
    void closingAtOrAfterMaturityChargesNothingAndStillSettlesToZero() {
        prepareDynamicDepositClosure(MATURITY_BEFORE_CLOSURE, accountCharge(new BigDecimal("60")));

        this.service.close(1L, closeCommandFor(1L, true));

        assertThat(this.account.isClosed()).isTrue();
        final BigDecimal grossInterest = singleInterestPosting().getAmount();
        assertThat(grossInterest).isGreaterThan(BigDecimal.ZERO);
        assertThat(payChargeTransactions()).isEmpty();
        assertThat(this.newlySavedRows).isEmpty();

        // One withdrawal, for principal plus the whole (uncharged) interest, and nothing left over.
        assertThat(this.withdrawals).hasSize(1);
        assertThat(this.withdrawals.get(0).getAmount()).isEqualByComparingTo(OPENING_BALANCE.add(grossInterest));
        assertThat(this.account.getSummary().getAccountBalance()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    // Scenario 4: the product has the early-withdrawal penalty switched off - same outcome as closing at maturity,
    // even though the closure date itself is well before maturity.
    @Test
    void closingEarlyOnAProductWithThePenaltyDisabledChargesNothing() {
        lenient().when(this.productDynamicDetailRepository.findByProductId(PRODUCT_ID))
                .thenReturn(Optional.of(DepositProductDynamicDetail.createNew(null, true, false, false)));
        prepareDynamicDepositClosure(MATURITY_AFTER_CLOSURE, accountCharge(new BigDecimal("60")));

        this.service.close(1L, closeCommandFor(1L, true));

        assertThat(this.account.isClosed()).isTrue();
        assertThat(this.account.isEarlyWithdrawal(CLOSED_DATE)).isTrue();
        final BigDecimal grossInterest = singleInterestPosting().getAmount();
        assertThat(payChargeTransactions()).isEmpty();
        assertThat(this.newlySavedRows).isEmpty();
        assertThat(this.withdrawals).hasSize(1);
        assertThat(this.withdrawals.get(0).getAmount()).isEqualByComparingTo(OPENING_BALANCE.add(grossInterest));
        assertThat(this.account.getSummary().getAccountBalance()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    // Scenario 5: regression guard - a plain savings account closes exactly as it always did.
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
        verifyNoInteractions(this.interestChargeRepository);
    }

    // Regression test (review finding): closureSettlement stays set (non-null) for the entire duration of close()'s
    // withdrawal call - by design, since DynamicDepositEarlyWithdrawalChargeService's suppression guard depends on it
    // staying set through any re-entrant posting the withdrawal triggers, and it is only ever cleared afterwards by
    // completeClosureSettlement(...). But core's SavingsAccountDomainServiceJpa#handleWithdrawal can itself RE-ENTER
    // account.postInterest(...) a second time while still inside that same withdrawal call, whenever
    // isBeforeLastPostingPeriod(...) is true - i.e. a backdated closure whose period already has an interest posting
    // transaction dated after the closure date. Before this fix, applyPendingInterestBasedCharges(...)'s closure-
    // contribution SELECTION had no guard against being handed the SAME (still non-null, still "qualifying") closure
    // settlement a second time, so a second boundary that also covers closedDate - 1 would select it again -
    // double-charging the closure penalty and overwriting ClosureSettlement#recordApplied(...)'s previously recorded
    // amount/transactions with whatever the second (wrong) application computed.
    //
    // Faithfully reproducing core's exact re-entrancy trigger through the full close()/handleWithdrawal path would
    // require this Mockito-only, no-JPA test harness to reverse-engineer SavingsHelper#determineInterestPostingPeriods'
    // manual-posting-date bookkeeping (which core's own comment notes only ACCIDENTALLY prevents this in some
    // isSavingsInterestPostingAtCurrentPeriodEnd configurations and not others) - fragile and indirect. Instead this
    // test drives applyPendingInterestBasedCharges(...) itself - the exact method the guard lives in - directly,
    // twice in a row, against the SAME ClosureSettlement instance prepareClosureSettlement(...) built (nothing here
    // ever calls completeClosureSettlement(...), so it is never cleared between the two calls - precisely the
    // window core's re-entrant call would also see), each time as isClosureBoundary=true exactly as a boundary
    // covering closedDate - 1 would be. This is the "more targeted unit test that directly proves the guard's
    // SELECTION logic itself" the review allowed as an alternative to a full end-to-end reproduction.
    @Test
    void aSecondAttemptToApplyTheSameClosureContributionIsANoOp() throws Exception {
        final SavingsAccountCharge accountCharge = accountCharge(new BigDecimal("60"));
        this.account = buildAccount(MATURITY_AFTER_CLOSURE, accountCharge);

        // Step 1 of the real closure sequence: resolves and remembers the qualifying 60% charge, exactly as
        // SavingsAccountWritePlatformServiceJpaRepositoryImpl#close(...) does before its own interest posting.
        this.account.prepareClosureSettlement(CLOSED_DATE);
        assertThat(this.account.isClosureSettlementInProgress()).isTrue();

        final Method applyPendingInterestBasedCharges = DynamicDepositAccount.class.getDeclaredMethod("applyPendingInterestBasedCharges",
                LocalDate.class, Money.class, SavingsAccountTransaction.class, boolean.class, boolean.class);
        applyPendingInterestBasedCharges.setAccessible(true);

        // First application - the real one, as the closure boundary's own interest posting would trigger it.
        final SavingsAccountTransaction firstPosting = new SavingsAccountTransactionTestBuilder().withId(700L)
                .withSavingsAccount(this.account).withType(SavingsAccountTransactionType.INTEREST_POSTING)
                .withDate(CLOSED_DATE.minusDays(1)).withAmount(new BigDecimal("100.00")).build();
        this.account.getTransactions().add(firstPosting);
        applyPendingInterestBasedCharges.invoke(this.account, CLOSED_DATE, Money.of(CURRENCY, new BigDecimal("100.00")), firstPosting,
                false, true);

        final List<SavingsAccountTransaction> afterFirstCall = payChargeTransactions();
        assertThat(afterFirstCall).hasSize(1);
        final SavingsAccountTransaction chargeTransactionAfterFirstCall = afterFirstCall.get(0);
        assertThat(chargeTransactionAfterFirstCall.getAmount()).isEqualByComparingTo("60.00");

        // Second application - simulating core's re-entrant postInterest(...) landing on another boundary that also
        // contains closedDate - 1, with closureSettlement still the very same, still-non-null instance (nothing has
        // called completeClosureSettlement(...) yet). Without the one-shot applied guard this recomputes the
        // closure's 60% against a DIFFERENT gross figure (50.00) and posts a SECOND PAY_CHARGE transaction, silently
        // overwriting the settlement's previously recorded amount/transactions.
        final SavingsAccountTransaction secondPosting = new SavingsAccountTransactionTestBuilder().withId(701L)
                .withSavingsAccount(this.account).withType(SavingsAccountTransactionType.INTEREST_POSTING)
                .withDate(CLOSED_DATE.minusDays(1)).withAmount(new BigDecimal("50.00")).build();
        this.account.getTransactions().add(secondPosting);
        applyPendingInterestBasedCharges.invoke(this.account, CLOSED_DATE, Money.of(CURRENCY, new BigDecimal("50.00")), secondPosting,
                false, true);

        // Exactly ONE PAY_CHARGE transaction - not two - and it is the very same transaction from the first call, not
        // a second one that replaced or sat alongside it.
        final List<SavingsAccountTransaction> afterSecondCall = payChargeTransactions();
        assertThat(afterSecondCall).hasSize(1);
        assertThat(afterSecondCall.get(0)).isSameAs(chargeTransactionAfterFirstCall);
        assertThat(afterSecondCall.get(0).getAmount()).isEqualByComparingTo("60.00");
    }

    // === fixtures ===

    private List<DepositAccountInterestCharge> allRows() {
        final List<DepositAccountInterestCharge> rows = new ArrayList<>(this.existingRows);
        rows.addAll(this.newlySavedRows);
        return rows;
    }

    private SavingsAccountTransaction singleInterestPosting() {
        final List<SavingsAccountTransaction> postings = this.account.getTransactions().stream()
                .filter(SavingsAccountTransaction::isInterestPostingAndNotReversed).toList();
        assertThat(postings).hasSize(1);
        assertThat(postings.get(0).getTransactionDate()).isEqualTo(CLOSED_DATE);
        return postings.get(0);
    }

    private List<SavingsAccountTransaction> payChargeTransactions() {
        return this.account.getTransactions().stream().filter(SavingsAccountTransaction::isPayCharge).toList();
    }

    private SavingsAccountTransaction singleChargeTransaction() {
        final List<SavingsAccountTransaction> chargeTransactions = payChargeTransactions();
        assertThat(chargeTransactions).hasSize(1);
        return chargeTransactions.get(0);
    }

    /**
     * Builds the Dynamic Deposit account under test and stubs everything close() needs, including a domain service that
     * behaves like the real one: it withdraws whatever amount close() asks for, runs the same early-withdrawal hook the
     * real withdrawal path runs, and leaves a real, dated, id-bearing withdrawal transaction behind.
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

        when(this.savingsAccountDomainService.handleWithdrawal(eq(this.account), any(), eq(CLOSED_DATE), any(), any(), any(), eq(false)))
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
                    // Mirrors DynamicDepositAccount#withdraw, which the real core domain service reaches: every
                    // withdrawal offers itself to the early-withdrawal charge hook. The settlement withdrawal must be
                    // turned away by it - that is precisely what this test exercises.
                    DynamicDepositServiceLocator.earlyWithdrawalChargeService().recordIfApplicable(this.account, withdrawal);
                    this.withdrawals.add(withdrawal);
                    return withdrawal;
                });
    }

    private DepositAccountInterestCharge pendingRow(final SavingsAccountCharge accountCharge, final BigDecimal percentage) {
        final SavingsAccountTransaction earlierWithdrawal = new SavingsAccountTransactionTestBuilder()
                .withId(500L + this.existingRows.size()).withSavingsAccount(this.account).withType(SavingsAccountTransactionType.WITHDRAWAL)
                .withDate(ACTIVATION_DATE.plusDays(5)).withAmount(BigDecimal.ZERO).build();
        final DepositAccountInterestCharge row = DepositAccountInterestCharge.createNew(this.account, earlierWithdrawal, accountCharge,
                accountCharge.getCharge(), ACTIVATION_DATE, ACTIVATION_DATE.plusDays(5), BigDecimal.ZERO, percentage, BigDecimal.ZERO);
        this.existingRows.add(row);
        return row;
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
