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
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.advancly.fineract.portfolio.savings.domain.DepositAccountDynamicRateHistory;
import com.advancly.fineract.portfolio.savings.domain.DepositAccountDynamicRateHistoryRepository;
import com.advancly.fineract.portfolio.savings.domain.DepositAccountInterestCharge;
import com.advancly.fineract.portfolio.savings.domain.DepositAccountInterestChargeRepository;
import com.advancly.fineract.portfolio.savings.domain.DynamicDepositAccount;
import com.advancly.fineract.portfolio.savings.domain.DynamicDepositRateHistoryEventType;
import com.advancly.fineract.portfolio.savings.domain.DynamicDepositRateSource;
import com.advancly.fineract.portfolio.savings.testutil.MoneyHelperInitializer;
import com.advancly.fineract.portfolio.savings.testutil.SavingsAccountSummaryTestBuilder;
import com.advancly.fineract.portfolio.savings.testutil.SavingsAccountTransactionTestBuilder;
import java.lang.reflect.Constructor;
import java.math.BigDecimal;
import java.math.MathContext;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.apache.fineract.organisation.monetary.domain.MonetaryCurrency;
import org.apache.fineract.organisation.monetary.domain.MoneyHelper;
import org.apache.fineract.organisation.office.domain.Office;
import org.apache.fineract.portfolio.account.service.AccountTransfersReadPlatformService;
import org.apache.fineract.portfolio.charge.domain.Charge;
import org.apache.fineract.portfolio.client.domain.Client;
import org.apache.fineract.portfolio.savings.SavingsAccountTransactionType;
import org.apache.fineract.portfolio.savings.domain.DepositAccountTermAndPreClosure;
import org.apache.fineract.portfolio.savings.domain.SavingsAccountCharge;
import org.apache.fineract.portfolio.savings.domain.SavingsAccountSummary;
import org.apache.fineract.portfolio.savings.domain.SavingsAccountTransaction;
import org.apache.fineract.portfolio.savings.domain.SavingsAccountTransactionSummaryWrapper;
import org.apache.fineract.portfolio.savings.domain.SavingsHelper;
import org.apache.fineract.portfolio.savings.service.SavingsInterestReadPlatformService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationContext;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Verifies {@link DynamicDepositAccount#calculateInterestUsing} varies the interest rate per rate-history interval
 * within a single core posting period, while still producing exactly one {@code INTEREST_POSTING} transaction per
 * posting-period boundary (matching {@code SavingsAccount.postInterest}'s own invariant for every other account type).
 */
class DynamicDepositAccountInterestTest {

    private static final MonetaryCurrency CURRENCY = new MonetaryCurrency("USD", 2, null);

    private DynamicDepositAccount account;
    private DepositAccountDynamicRateHistoryRepository rateHistoryRepository;
    private DepositAccountInterestChargeRepository interestChargeRepository;
    private DynamicDepositRateHistoryService rateHistoryService;

    @BeforeEach
    void setUp() {
        MoneyHelperInitializer.initialize();

        this.rateHistoryRepository = mock(DepositAccountDynamicRateHistoryRepository.class);
        this.interestChargeRepository = mock(DepositAccountInterestChargeRepository.class);
        this.rateHistoryService = mock(DynamicDepositRateHistoryService.class);
        // Phase 4: postInterest now resolves the interest-charge repository through the locator on every run, so it
        // must be stubbed for every test in this class. Defaults to "nothing pending", which is the pre-Phase-4
        // behaviour the existing tests assert.
        lenient().when(this.interestChargeRepository.findPendingByAccountIdUpTo(anyLong(), any())).thenReturn(List.of());
        final ApplicationContext applicationContext = mock(ApplicationContext.class);
        lenient().when(applicationContext.getBean(DepositAccountDynamicRateHistoryRepository.class)).thenReturn(this.rateHistoryRepository);
        lenient().when(applicationContext.getBean(DepositAccountInterestChargeRepository.class)).thenReturn(this.interestChargeRepository);
        // Only exercised by undoTransaction(...) - DynamicDepositAccount#undoTransaction resolves the rate-history
        // service through the locator to reverse the invested-amount rate-history row for the undone transaction.
        lenient().when(applicationContext.getBean(DynamicDepositRateHistoryService.class)).thenReturn(this.rateHistoryService);
        ReflectionTestUtils.setField(DynamicDepositServiceLocator.class, "applicationContext", applicationContext);
    }

    @Test
    void interestIsSplitAcrossTwoRatesWithinOnePostingPeriodAndPostedAsOneTransaction() {
        this.account = buildAccount();

        final SavingsAccountTransaction openingDeposit = transaction(1L, LocalDate.of(2026, 1, 1), BigDecimal.valueOf(1000));
        final SavingsAccountTransaction topUp = transaction(2L, LocalDate.of(2026, 1, 15), BigDecimal.valueOf(1000));

        final List<DepositAccountDynamicRateHistory> rateHistory = List.of(
                DepositAccountDynamicRateHistory.createNew(this.account, openingDeposit, LocalDate.of(2026, 1, 1),
                        DynamicDepositRateHistoryEventType.ACCOUNT_ACTIVATION, BigDecimal.valueOf(1000), 12, 2, null, null,
                        BigDecimal.valueOf(2), BigDecimal.valueOf(2), DynamicDepositRateSource.INTEREST_RATE_CHART),
                DepositAccountDynamicRateHistory.createNew(this.account, topUp, LocalDate.of(2026, 1, 15),
                        DynamicDepositRateHistoryEventType.DEPOSIT, BigDecimal.valueOf(2000), 12, 2, null, null, BigDecimal.valueOf(3),
                        BigDecimal.valueOf(3), DynamicDepositRateSource.INTEREST_RATE_CHART));
        lenient().when(this.rateHistoryRepository.findByAccountIdOrderByTransactionDateAscIdAsc(this.account.getId()))
                .thenReturn(rateHistory);

        final MathContext mc = MoneyHelper.getMathContext();
        // A monthly posting job runs on Feb 1st to post January's interest - the posting-period boundary
        // (dateOfPostingTransaction) for a period ending Jan 31 is Feb 1, so upToInterestCalculationDate must reach
        // at least Feb 1 for the transaction to actually be created (see SavingsAccount.postInterest's date filter).
        this.account.postInterest(mc, LocalDate.of(2026, 2, 1), false, false, 1, null, false, true);

        final List<SavingsAccountTransaction> interestPostings = this.account.getTransactions().stream()
                .filter(SavingsAccountTransaction::isInterestPostingAndNotReversed).toList();

        assertThat(interestPostings).hasSize(1);

        // Hand-computed expected amount: plain (non-compounding) daily-balance interest, 365 days/year -
        // 14 days at 1000 @ 2%, then 17 days at 2000 @ 3% (top-up lands on day 15, splitting the Jan 1-31 core
        // posting period at the rate-history transaction date).
        final BigDecimal dailyFractionAt2Pct = BigDecimal.valueOf(2).divide(BigDecimal.valueOf(100), mc).divide(BigDecimal.valueOf(365),
                mc);
        final BigDecimal dailyFractionAt3Pct = BigDecimal.valueOf(3).divide(BigDecimal.valueOf(100), mc).divide(BigDecimal.valueOf(365),
                mc);
        final BigDecimal expectedInterest = BigDecimal.valueOf(1000).multiply(dailyFractionAt2Pct).multiply(BigDecimal.valueOf(14))
                .add(BigDecimal.valueOf(2000).multiply(dailyFractionAt3Pct).multiply(BigDecimal.valueOf(17)))
                .setScale(2, MoneyHelper.getRoundingMode());

        assertThat(interestPostings.get(0).getAmount()).isEqualByComparingTo(expectedInterest);
    }

    @Test
    void postingTwiceWithIdenticalArgumentsIsANoOpNotAReverseAndRecreate() {
        this.account = buildAccount();

        final SavingsAccountTransaction openingDeposit = transaction(1L, LocalDate.of(2026, 1, 1), BigDecimal.valueOf(1000));
        final SavingsAccountTransaction topUp = transaction(2L, LocalDate.of(2026, 1, 15), BigDecimal.valueOf(1000));

        final List<DepositAccountDynamicRateHistory> rateHistory = List.of(
                DepositAccountDynamicRateHistory.createNew(this.account, openingDeposit, LocalDate.of(2026, 1, 1),
                        DynamicDepositRateHistoryEventType.ACCOUNT_ACTIVATION, BigDecimal.valueOf(1000), 12, 2, null, null,
                        BigDecimal.valueOf(2), BigDecimal.valueOf(2), DynamicDepositRateSource.INTEREST_RATE_CHART),
                DepositAccountDynamicRateHistory.createNew(this.account, topUp, LocalDate.of(2026, 1, 15),
                        DynamicDepositRateHistoryEventType.DEPOSIT, BigDecimal.valueOf(2000), 12, 2, null, null, BigDecimal.valueOf(3),
                        BigDecimal.valueOf(3), DynamicDepositRateSource.INTEREST_RATE_CHART));
        lenient().when(this.rateHistoryRepository.findByAccountIdOrderByTransactionDateAscIdAsc(this.account.getId()))
                .thenReturn(rateHistory);

        final MathContext mc = MoneyHelper.getMathContext();

        this.account.postInterest(mc, LocalDate.of(2026, 2, 1), false, false, 1, null, false, true);
        final List<SavingsAccountTransaction> afterFirstCall = this.account.getTransactions().stream()
                .filter(SavingsAccountTransaction::isInterestPostingAndNotReversed).toList();
        assertThat(afterFirstCall).hasSize(1);
        final BigDecimal amountAfterFirstCall = afterFirstCall.get(0).getAmount();

        // Re-run with identical arguments - PostingPeriod.createFrom recomputes the same amount for the same
        // (unchanged) transaction history, so postingTransaction.hasNotAmount(...) must be false and no
        // reversal/recreation should happen.
        this.account.postInterest(mc, LocalDate.of(2026, 2, 1), false, false, 1, null, false, true);
        final List<SavingsAccountTransaction> afterSecondCall = this.account.getTransactions().stream()
                .filter(SavingsAccountTransaction::isInterestPostingAndNotReversed).toList();

        assertThat(afterSecondCall).hasSize(1);
        assertThat(afterSecondCall.get(0).getAmount()).isEqualByComparingTo(amountAfterFirstCall);
        // The no-op path must not reverse the original transaction - it should be the very same live transaction,
        // not a reversal followed by a freshly-created replacement.
        assertThat(afterSecondCall.get(0)).isSameAs(afterFirstCall.get(0));
        assertThat(this.account.getTransactions().stream().filter(SavingsAccountTransaction::isReversed).toList()).isEmpty();
    }

    @Test
    void groupsRateVaryingSubPeriodsByCoreBoundaryAcrossTwoPostingPeriods() {
        this.account = buildAccount();

        final SavingsAccountTransaction openingDeposit = transaction(1L, LocalDate.of(2026, 1, 1), BigDecimal.valueOf(1000));
        final SavingsAccountTransaction topUp = transaction(2L, LocalDate.of(2026, 1, 15), BigDecimal.valueOf(1000));

        final List<DepositAccountDynamicRateHistory> rateHistory = List.of(
                DepositAccountDynamicRateHistory.createNew(this.account, openingDeposit, LocalDate.of(2026, 1, 1),
                        DynamicDepositRateHistoryEventType.ACCOUNT_ACTIVATION, BigDecimal.valueOf(1000), 12, 2, null, null,
                        BigDecimal.valueOf(2), BigDecimal.valueOf(2), DynamicDepositRateSource.INTEREST_RATE_CHART),
                DepositAccountDynamicRateHistory.createNew(this.account, topUp, LocalDate.of(2026, 1, 15),
                        DynamicDepositRateHistoryEventType.DEPOSIT, BigDecimal.valueOf(2000), 12, 2, null, null, BigDecimal.valueOf(3),
                        BigDecimal.valueOf(3), DynamicDepositRateSource.INTEREST_RATE_CHART));
        lenient().when(this.rateHistoryRepository.findByAccountIdOrderByTransactionDateAscIdAsc(this.account.getId()))
                .thenReturn(rateHistory);

        final MathContext mc = MoneyHelper.getMathContext();
        // Post through March 1st so both the January and February core posting-period boundaries have their
        // dateOfPostingTransaction (Feb 1 and Mar 1 respectively) within range - 2026 is not a leap year, so
        // February has 28 days.
        this.account.postInterest(mc, LocalDate.of(2026, 3, 1), false, false, 1, null, false, true);

        final List<SavingsAccountTransaction> interestPostings = this.account.getTransactions().stream()
                .filter(SavingsAccountTransaction::isInterestPostingAndNotReversed).toList();
        assertThat(interestPostings).hasSize(2);

        final SavingsAccountTransaction januaryPosting = interestPostings.stream()
                .filter(t -> t.getTransactionDate().equals(LocalDate.of(2026, 2, 1))).findFirst()
                .orElseThrow(() -> new AssertionError("No interest posting transaction dated 2026-02-01 (January boundary)"));
        final SavingsAccountTransaction februaryPosting = interestPostings.stream()
                .filter(t -> t.getTransactionDate().equals(LocalDate.of(2026, 3, 1))).findFirst()
                .orElseThrow(() -> new AssertionError("No interest posting transaction dated 2026-03-01 (February boundary)"));

        final BigDecimal dailyFractionAt2Pct = BigDecimal.valueOf(2).divide(BigDecimal.valueOf(100), mc).divide(BigDecimal.valueOf(365),
                mc);
        final BigDecimal dailyFractionAt3Pct = BigDecimal.valueOf(3).divide(BigDecimal.valueOf(100), mc).divide(BigDecimal.valueOf(365),
                mc);

        // January boundary: 14 days at 1000 @ 2%, then 17 days at 2000 @ 3% - identical to the single-boundary test.
        final BigDecimal expectedJanuary = BigDecimal.valueOf(1000).multiply(dailyFractionAt2Pct).multiply(BigDecimal.valueOf(14))
                .add(BigDecimal.valueOf(2000).multiply(dailyFractionAt3Pct).multiply(BigDecimal.valueOf(17)))
                .setScale(2, MoneyHelper.getRoundingMode());
        // February boundary: whole month at 2000 @ 3% (28 days, no rate change and no transactions inside February).
        final BigDecimal expectedFebruary = BigDecimal.valueOf(2000).multiply(dailyFractionAt3Pct).multiply(BigDecimal.valueOf(28))
                .setScale(2, MoneyHelper.getRoundingMode());

        assertThat(januaryPosting.getAmount()).isEqualByComparingTo(expectedJanuary);
        assertThat(februaryPosting.getAmount()).isEqualByComparingTo(expectedFebruary);
    }

    @Test
    void postingWritesNoInterestBasedChargeTransactionWhenNothingIsPending() {
        this.account = buildAccount();
        stubSingleRateHistoryForJanuary();

        this.account.postInterest(MoneyHelper.getMathContext(), LocalDate.of(2026, 2, 1), false, false, 1, null, false, true);

        assertThat(this.account.getTransactions()).noneMatch(SavingsAccountTransaction::isPayCharge);
        assertThat(this.account.interestBasedChargePostedDerived()).isEqualByComparingTo(BigDecimal.ZERO);
        verify(this.interestChargeRepository, never()).saveAll(any());
    }

    @Test
    void postingRecomputesTheChargeFromTheStoredPercentageAndIgnoresTheProvisionalAmount() {
        this.account = buildAccount();
        stubSingleRateHistoryForJanuary();
        // Provisional amount deliberately nonsense (99.99): it must have no influence whatsoever on what is charged.
        final DepositAccountInterestCharge pendingRow = stubOnePendingRow(new BigDecimal("10"), new BigDecimal("99.99"));

        this.account.postInterest(MoneyHelper.getMathContext(), LocalDate.of(2026, 2, 1), false, false, 1, null, false, true);

        final List<SavingsAccountTransaction> chargeTransactions = this.account.getTransactions().stream()
                .filter(SavingsAccountTransaction::isPayCharge).toList();
        assertThat(chargeTransactions).hasSize(1);
        assertThat(chargeTransactions.get(0).getAmount()).isEqualByComparingTo(expectedChargeAt(new BigDecimal("10")));
        assertThat(chargeTransactions.get(0).getTransactionDate()).isEqualTo(LocalDate.of(2026, 2, 1));
        assertThat(chargeTransactions.get(0).getSavingsAccountChargesPaid()).hasSize(1);
        assertThat(pendingRow.isPending()).isFalse();
        assertThat(pendingRow.interestChargeTransaction()).isSameAs(chargeTransactions.get(0));
        assertThat(pendingRow.interestPostingTransaction()).isNotNull();
        // The row is rewritten with what actually happened, not with the provisional snapshot.
        assertThat(pendingRow.chargeAmount()).isEqualByComparingTo(chargeTransactions.get(0).getAmount());
        assertThat(pendingRow.interestAmountBasis()).isEqualByComparingTo(grossInterestPosted());
        assertThat(this.account.interestBasedChargeDerived()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(this.account.interestBasedChargePostedDerived()).isEqualByComparingTo(chargeTransactions.get(0).getAmount());
        verify(this.interestChargeRepository).saveAll(List.of(pendingRow));
    }

    @Test
    void aWithdrawalRecordedWithAStaleZeroBasisStillChargesCorrectlyAtPosting() {
        this.account = buildAccount();
        stubSingleRateHistoryForJanuary();
        // Exactly what Task 7 writes when no interest calculation has run since the last posting: the authoritative
        // percentage, a zero basis and a zero provisional amount.
        final DepositAccountInterestCharge pendingRow = stubOnePendingRow(new BigDecimal("10"), BigDecimal.ZERO);

        this.account.postInterest(MoneyHelper.getMathContext(), LocalDate.of(2026, 2, 1), false, false, 1, null, false, true);

        final List<SavingsAccountTransaction> chargeTransactions = this.account.getTransactions().stream()
                .filter(SavingsAccountTransaction::isPayCharge).toList();
        assertThat(chargeTransactions).hasSize(1);
        assertThat(chargeTransactions.get(0).getAmount()).isEqualByComparingTo(expectedChargeAt(new BigDecimal("10")));
        assertThat(chargeTransactions.get(0).getAmount()).isGreaterThan(BigDecimal.ZERO);
        assertThat(pendingRow.chargeAmount()).isEqualByComparingTo(chargeTransactions.get(0).getAmount());
    }

    @Test
    void proRataDistributionAcrossMultipleRowsNeverProducesANegativeRowEvenWhenNaiveRoundingWouldRoundUp() {
        this.account = buildAccount();
        stubSingleRateHistoryForJanuary();
        // Five equal-percentage rows whose recomputed amounts, against the account's real gross interest for January
        // (~3.56), do not divide evenly into the currency's 2 decimal places, forcing the pro-rata write-back to
        // round. Rounding each non-last row to the NEAREST value (the tenant's default rounding mode, which can round
        // up) pushes the non-last rows' running sum above the transaction's real applied total, driving the last
        // row's remainder negative - e.g. 0.01, 0.01, 0.01, 0.01, -0.01 for a 0.03 transaction. Truncating each
        // non-last row DOWN instead is the fix under test here.
        final List<DepositAccountInterestCharge> pendingRows = stubPendingRows(5, new BigDecimal("0.17"));

        this.account.postInterest(MoneyHelper.getMathContext(), LocalDate.of(2026, 2, 1), false, false, 1, null, false, true);

        final List<SavingsAccountTransaction> chargeTransactions = this.account.getTransactions().stream()
                .filter(SavingsAccountTransaction::isPayCharge).toList();
        assertThat(chargeTransactions).hasSize(1);
        final BigDecimal appliedTotal = chargeTransactions.get(0).getAmount();

        // The invariant the bug violated: every row's applied amount must be non-negative - not merely that the rows
        // sum to the right total, which a negative row can hide behind (0.01, 0.01, 0.01, 0.01, -0.01 still sums to
        // 0.03).
        assertThat(pendingRows).allSatisfy(row -> assertThat(row.chargeAmount()).isGreaterThanOrEqualTo(BigDecimal.ZERO));
        final BigDecimal sumOfRows = pendingRows.stream().map(DepositAccountInterestCharge::chargeAmount).reduce(BigDecimal.ZERO,
                BigDecimal::add);
        assertThat(sumOfRows).isEqualByComparingTo(appliedTotal);
    }

    @Test
    void undoingATransactionRefreshesTheStaleDerivedInterestBasedChargeColumns() {
        this.account = buildAccount();
        final SavingsAccountTransaction withdrawal = transaction(1L, LocalDate.of(2026, 1, 20), BigDecimal.valueOf(100));

        // Simulates the exact staleness Task 7's review found: an earlier early-withdrawal charge posting left these
        // fast-read columns non-zero, and the withdrawal that produced them (or the charge posting itself) is now
        // being undone. m_deposit_account_interest_charge itself is already correct post-undo - its queries exclude
        // rows linked to a reversed transaction - the stubs below simulate what it now reports.
        this.account.updateInterestBasedChargeDerived(BigDecimal.valueOf(12));
        this.account.updateInterestBasedChargePostedDerived(BigDecimal.valueOf(30));
        lenient().when(this.interestChargeRepository.sumPendingChargeAmount(1L)).thenReturn(BigDecimal.ZERO);
        lenient().when(this.interestChargeRepository.sumPostedChargeAmount(1L)).thenReturn(BigDecimal.ZERO);

        this.account.undoTransaction(withdrawal.getId());

        assertThat(this.account.interestBasedChargeDerived()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(this.account.interestBasedChargePostedDerived()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    private DepositAccountInterestCharge stubOnePendingRow(final BigDecimal percentage, final BigDecimal provisionalAmount) {
        final DepositAccountInterestCharge pendingRow = DepositAccountInterestCharge.createNew(this.account,
                mock(SavingsAccountTransaction.class), mock(SavingsAccountCharge.class), mock(Charge.class), LocalDate.of(2026, 1, 1),
                LocalDate.of(2026, 1, 20), provisionalAmount, percentage, provisionalAmount);
        lenient().when(this.interestChargeRepository.findPendingByAccountIdUpTo(anyLong(), any()))
                .thenReturn(new ArrayList<>(List.of(pendingRow)));
        lenient().when(this.interestChargeRepository.sumPendingChargeAmount(anyLong())).thenReturn(BigDecimal.ZERO);
        // Answer, not a fixed value: proves the row really was rewritten with the applied amount.
        lenient().when(this.interestChargeRepository.sumPostedChargeAmount(anyLong())).thenAnswer(invocation -> pendingRow.chargeAmount());
        return pendingRow;
    }

    /** {@code count} pending rows, each with the same {@code percentageEach} and a zero provisional basis/amount. */
    private List<DepositAccountInterestCharge> stubPendingRows(final int count, final BigDecimal percentageEach) {
        final List<DepositAccountInterestCharge> rows = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            rows.add(DepositAccountInterestCharge.createNew(this.account, mock(SavingsAccountTransaction.class),
                    mock(SavingsAccountCharge.class), mock(Charge.class), LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 20),
                    BigDecimal.ZERO, percentageEach, BigDecimal.ZERO));
        }
        lenient().when(this.interestChargeRepository.findPendingByAccountIdUpTo(anyLong(), any())).thenReturn(new ArrayList<>(rows));
        lenient().when(this.interestChargeRepository.sumPendingChargeAmount(anyLong())).thenReturn(BigDecimal.ZERO);
        lenient().when(this.interestChargeRepository.sumPostedChargeAmount(anyLong())).thenAnswer(
                invocation -> rows.stream().map(DepositAccountInterestCharge::chargeAmount).reduce(BigDecimal.ZERO, BigDecimal::add));
        return rows;
    }

    /** The gross interest the posting transaction was actually written with. */
    private BigDecimal grossInterestPosted() {
        return this.account.getTransactions().stream().filter(SavingsAccountTransaction::isInterestPostingAndNotReversed).findFirst()
                .orElseThrow().getAmount();
    }

    /** percentage% of the real posted gross interest, rounded the way Money rounds it for this currency. */
    private BigDecimal expectedChargeAt(final BigDecimal percentage) {
        return grossInterestPosted().multiply(percentage)
                .divide(BigDecimal.valueOf(100L), new MathContext(8, MoneyHelper.getRoundingMode()))
                .setScale(2, MoneyHelper.getRoundingMode());
    }

    /**
     * The same January scenario the existing rate-splitting test uses: 1000 opening on Jan 1 at 2%, topped up to 2000
     * on Jan 15 at 3%, giving roughly 3.56 of gross interest for the period.
     */
    private void stubSingleRateHistoryForJanuary() {
        final SavingsAccountTransaction openingDeposit = transaction(1L, LocalDate.of(2026, 1, 1), BigDecimal.valueOf(1000));
        final SavingsAccountTransaction topUp = transaction(2L, LocalDate.of(2026, 1, 15), BigDecimal.valueOf(1000));
        final List<DepositAccountDynamicRateHistory> rateHistory = List.of(
                DepositAccountDynamicRateHistory.createNew(this.account, openingDeposit, LocalDate.of(2026, 1, 1),
                        DynamicDepositRateHistoryEventType.ACCOUNT_ACTIVATION, BigDecimal.valueOf(1000), 12, 2, null, null,
                        BigDecimal.valueOf(2), BigDecimal.valueOf(2), DynamicDepositRateSource.INTEREST_RATE_CHART),
                DepositAccountDynamicRateHistory.createNew(this.account, topUp, LocalDate.of(2026, 1, 15),
                        DynamicDepositRateHistoryEventType.DEPOSIT, BigDecimal.valueOf(2000), 12, 2, null, null, BigDecimal.valueOf(3),
                        BigDecimal.valueOf(3), DynamicDepositRateSource.INTEREST_RATE_CHART));
        lenient().when(this.rateHistoryRepository.findByAccountIdOrderByTransactionDateAscIdAsc(this.account.getId()))
                .thenReturn(rateHistory);
    }

    private SavingsAccountTransaction transaction(final Long id, final LocalDate date, final BigDecimal amount) {
        final SavingsAccountTransaction transaction = new SavingsAccountTransactionTestBuilder().withId(id).withSavingsAccount(this.account)
                .withType(SavingsAccountTransactionType.DEPOSIT).withDate(date).withAmount(amount).build();
        this.account.getTransactions().add(transaction);
        return transaction;
    }

    private DynamicDepositAccount buildAccount() {
        final DynamicDepositAccount newAccount = createInstance(DynamicDepositAccount.class);
        ReflectionTestUtils.setField(newAccount, "id", 1L);
        ReflectionTestUtils.setField(newAccount, "currency", CURRENCY);
        ReflectionTestUtils.setField(newAccount, "activatedOnDate", LocalDate.of(2026, 1, 1));
        ReflectionTestUtils.setField(newAccount, "nominalAnnualInterestRate", BigDecimal.valueOf(2));
        ReflectionTestUtils.setField(newAccount, "interestCompoundingPeriodType", 8); // NO_COMPOUNDING_SIMPLE_INTEREST
        ReflectionTestUtils.setField(newAccount, "interestPostingPeriodType", 4); // MONTHLY
        ReflectionTestUtils.setField(newAccount, "interestCalculationType", 1); // DAILY_BALANCE
        ReflectionTestUtils.setField(newAccount, "interestCalculationDaysInYearType", 365);
        ReflectionTestUtils.setField(newAccount, "savingsAccountTransactions", new ArrayList<SavingsAccountTransaction>());
        // DynamicDepositAccount#withHoldTaxPostingType() dereferences accountTermAndPreClosure unconditionally
        // (mirroring FixedDepositAccount/RecurringDepositAccount) - postInterest calls it regardless of whether
        // withholding tax is actually configured, so it must never be null, even when these tests aren't exercising
        // WHT themselves.
        ReflectionTestUtils.setField(newAccount, "accountTermAndPreClosure",
                DepositAccountTermAndPreClosure.createNew(null, null, null, null, null, null, null, null, null, null, false, null, null));

        final SavingsAccountSummary summary = new SavingsAccountSummaryTestBuilder().build();
        ReflectionTestUtils.setField(newAccount, "summary", summary);
        ReflectionTestUtils.setField(newAccount, "savingsAccountTransactionSummaryWrapper", new SavingsAccountTransactionSummaryWrapper());

        final AccountTransfersReadPlatformService accountTransfersReadPlatformService = mock(AccountTransfersReadPlatformService.class);
        lenient().when(accountTransfersReadPlatformService.fetchPostInterestTransactionIds(anyLong())).thenReturn(List.of());
        final SavingsInterestReadPlatformService savingsInterestReadPlatformService = mock(SavingsInterestReadPlatformService.class);
        final SavingsHelper savingsHelper = new SavingsHelper(accountTransfersReadPlatformService, savingsInterestReadPlatformService);
        ReflectionTestUtils.setField(newAccount, "savingsHelper", savingsHelper);

        final Office office = mock(Office.class);
        lenient().when(office.getId()).thenReturn(1L);
        final Client client = mock(Client.class);
        lenient().when(client.getOffice()).thenReturn(office);
        lenient().when(client.officeId()).thenReturn(1L);
        lenient().when(client.getId()).thenReturn(1L);
        ReflectionTestUtils.setField(newAccount, "client", client);

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
