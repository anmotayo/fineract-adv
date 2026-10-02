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
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.advancly.fineract.portfolio.savings.data.InterestCalculationData;
import com.advancly.fineract.portfolio.savings.data.SimulatedRateData;
import com.advancly.fineract.portfolio.savings.domain.DepositAccountDynamicRateHistory;
import com.advancly.fineract.portfolio.savings.domain.DynamicDepositAccount;
import com.advancly.fineract.portfolio.savings.domain.DynamicDepositRateHistoryEventType;
import com.advancly.fineract.portfolio.savings.service.DynamicDepositRatePreviewService.RatePreview;
import com.advancly.fineract.portfolio.savings.testutil.MoneyHelperInitializer;
import com.advancly.fineract.portfolio.savings.testutil.SavingsAccountSummaryTestBuilder;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.apache.fineract.infrastructure.configuration.domain.ConfigurationDomainService;
import org.apache.fineract.infrastructure.core.domain.LocalDateInterval;
import org.apache.fineract.infrastructure.security.service.PlatformSecurityContext;
import org.apache.fineract.organisation.monetary.domain.MonetaryCurrency;
import org.apache.fineract.organisation.monetary.domain.Money;
import org.apache.fineract.organisation.office.domain.Office;
import org.apache.fineract.portfolio.savings.DepositAccountType;
import org.apache.fineract.portfolio.savings.domain.FixedDepositAccount;
import org.apache.fineract.portfolio.savings.domain.SavingsAccount;
import org.apache.fineract.portfolio.savings.domain.SavingsAccountAssembler;
import org.apache.fineract.portfolio.savings.domain.SavingsAccountRepositoryWrapper;
import org.apache.fineract.portfolio.savings.domain.SavingsAccountStatusType;
import org.apache.fineract.portfolio.savings.domain.SavingsAccountSummary;
import org.apache.fineract.portfolio.savings.domain.SavingsAccountTransaction;
import org.apache.fineract.portfolio.savings.domain.interest.PostingPeriod;
import org.apache.fineract.useradministration.domain.AppUser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class InterestCalculationReadPlatformServiceImplTest {

    private static final Long ACCOUNT_ID = 1L;
    private static final MonetaryCurrency CURRENCY = new MonetaryCurrency("USD", 2, null);

    private PlatformSecurityContext context;
    private SavingsAccountRepositoryWrapper savingsAccountRepositoryWrapper;
    private SavingsAccountAssembler savingsAccountAssembler;
    private ConfigurationDomainService configurationDomainService;
    private DynamicDepositRatePreviewService ratePreviewService;
    private InterestCalculationReadPlatformServiceImpl service;

    @BeforeEach
    void setUp() {
        MoneyHelperInitializer.initialize();
        this.context = mock(PlatformSecurityContext.class);
        final AppUser appUser = mock(AppUser.class);
        lenient().when(this.context.authenticatedUser()).thenReturn(appUser);

        this.savingsAccountRepositoryWrapper = mock(SavingsAccountRepositoryWrapper.class);
        this.savingsAccountAssembler = mock(SavingsAccountAssembler.class);
        this.configurationDomainService = mock(ConfigurationDomainService.class);
        this.ratePreviewService = mock(DynamicDepositRatePreviewService.class);
        this.service = new InterestCalculationReadPlatformServiceImpl(this.context, this.savingsAccountRepositoryWrapper,
                this.savingsAccountAssembler, this.configurationDomainService, this.ratePreviewService);
    }

    private SavingsAccount plainSavingsAccount() {
        final SavingsAccount account = mock(SavingsAccount.class);
        commonStubs(account, DepositAccountType.SAVINGS_DEPOSIT);
        return account;
    }

    private SavingsAccountSummary summaryOf(final SavingsAccount account) {
        return account.getSummary();
    }

    /**
     * A spy, not a plain built object: {@code calculateInterestUsing(...)} is entirely mocked out in these tests (no
     * real interest math runs), so {@code getTotalInterestEarned()} needs per-test sequential stubbing to simulate what
     * each of the two real {@code calculateInterestUsing} calls (today-bounded, then maturity-bounded) would have left
     * behind - a spy lets tests override just that one method while every other summary getter keeps its normal,
     * builder-set behaviour.
     */
    private void commonStubs(final SavingsAccount account, final DepositAccountType depositAccountType) {
        final SavingsAccountSummary summary = Mockito
                .spy(new SavingsAccountSummaryTestBuilder().withAccountBalance(BigDecimal.valueOf(1000)).build());
        lenient().when(account.getId()).thenReturn(ACCOUNT_ID);
        lenient().when(account.getAccountNumber()).thenReturn("SA0001");
        lenient().when(account.getExternalId()).thenReturn(null);
        lenient().when(account.clientId()).thenReturn(10L);
        lenient().when(account.groupId()).thenReturn(null);
        lenient().when(account.productId()).thenReturn(20L);
        lenient().when(account.getStatus()).thenReturn(SavingsAccountStatusType.ACTIVE);
        lenient().when(account.getCurrency()).thenReturn(CURRENCY);
        lenient().when(account.getSummary()).thenReturn(summary);
        lenient().when(account.depositAccountType()).thenReturn(depositAccountType);
        lenient().when(account.office()).thenReturn(mock(Office.class));
        lenient().when(account.getTransactions()).thenReturn(List.of());
        lenient().when(account.calculateInterestUsing(any(), any(), anyBoolean(), anyBoolean(), any(), any(), anyBoolean(), anyBoolean()))
                .thenReturn(List.of());
        lenient().when(this.savingsAccountRepositoryWrapper.findOneWithNotFoundDetection(ACCOUNT_ID)).thenReturn(account);
    }

    private PostingPeriod postingPeriodWithInterest(final LocalDate from, final LocalDate to, final BigDecimal interest,
            final BigDecimal closingBalance) {
        final PostingPeriod period = mock(PostingPeriod.class);
        lenient().when(period.getPeriodInterval()).thenReturn(LocalDateInterval.create(from, to));
        lenient().when(period.interest()).thenReturn(Money.of(CURRENCY, interest));
        lenient().when(period.closingBalance()).thenReturn(Money.of(CURRENCY, closingBalance));
        return period;
    }

    @Test
    void plainSavingsHasNoMaturityFieldsAndReadsInterestAsAtTodayOffTheSummary() {
        final SavingsAccount account = plainSavingsAccount();
        final LocalDate today = org.apache.fineract.infrastructure.core.service.DateUtils.getBusinessLocalDate();
        // Simulates a mid-month, not-yet-posted partial period: its interval's own end date is month-end (in the
        // future), which is exactly why interestAsAtToday must come from the summary rather than from filtering
        // periods by their interval end date - see the regression this guards against in the class javadoc.
        final PostingPeriod currentPartialPeriod = postingPeriodWithInterest(today.withDayOfMonth(1), today.plusDays(10),
                new BigDecimal("5.00"), new BigDecimal("1005.00"));
        when(account.calculateInterestUsing(any(), eq(today.minusDays(1)), anyBoolean(), anyBoolean(), any(), any(), anyBoolean(),
                anyBoolean())).thenReturn(List.of(currentPartialPeriod));
        when(summaryOf(account).getTotalInterestEarned()).thenReturn(new BigDecimal("5.00"));

        final InterestCalculationData result = this.service.calculate(ACCOUNT_ID, null, null);

        assertThat(result.maturityDate()).isNull();
        assertThat(result.interestAtMaturity()).isNull();
        assertThat(result.maturityAmount()).isNull();
        assertThat(result.interestAsAtToday()).isEqualByComparingTo("5.00");
        assertThat(result.postingPeriods()).hasSize(1);
        // Only one calculateInterestUsing call for an account with no maturity date.
        verify(account, org.mockito.Mockito.times(1)).calculateInterestUsing(any(), any(), anyBoolean(), anyBoolean(), any(), any(),
                anyBoolean(), anyBoolean());
    }

    @Test
    void fixedDepositRunsTwoCalculationsAndReportsInterestAsAtTodaySeparatelyFromInterestAtMaturity() {
        final FixedDepositAccount account = mock(FixedDepositAccount.class);
        commonStubs(account, DepositAccountType.FIXED_DEPOSIT);
        final LocalDate maturityDate = LocalDate.now().plusMonths(6);
        when(account.maturityDate()).thenReturn(maturityDate);
        when(this.savingsAccountRepositoryWrapper.findOneWithNotFoundDetection(ACCOUNT_ID)).thenReturn(account);

        final LocalDate today = org.apache.fineract.infrastructure.core.service.DateUtils.getBusinessLocalDate();
        final PostingPeriod partialToday = postingPeriodWithInterest(today.withDayOfMonth(1), today.plusDays(10), new BigDecimal("2.00"),
                new BigDecimal("1002.00"));
        final PostingPeriod fullPeriodProjectedToMaturity = postingPeriodWithInterest(today.withDayOfMonth(1), maturityDate,
                new BigDecimal("32.00"), new BigDecimal("1032.00"));
        when(account.calculateInterestUsing(any(), eq(today.minusDays(1)), anyBoolean(), anyBoolean(), any(), any(), anyBoolean(),
                anyBoolean())).thenReturn(List.of(partialToday));
        when(account.calculateInterestUsing(any(), eq(maturityDate), anyBoolean(), anyBoolean(), any(), any(), anyBoolean(), anyBoolean()))
                .thenReturn(List.of(fullPeriodProjectedToMaturity));
        // First call (today-bounded) leaves totalInterestEarned=2.00; second call (maturity-bounded) overwrites it to
        // 32.00 - exactly what two real, sequential calculateInterestUsing invocations would do.
        when(summaryOf(account).getTotalInterestEarned()).thenReturn(new BigDecimal("2.00"), new BigDecimal("32.00"));

        final InterestCalculationData result = this.service.calculate(ACCOUNT_ID, null, null);

        assertThat(result.maturityDate()).isEqualTo(maturityDate);
        assertThat(result.interestAsAtToday()).isEqualByComparingTo("2.00");
        assertThat(result.interestAtMaturity()).isEqualByComparingTo("32.00");
        assertThat(result.maturityAmount()).isEqualByComparingTo("1032.00");
        // The posting-period breakdown reflects the maturity-bounded (second) run, not the today-bounded one.
        assertThat(result.postingPeriods()).hasSize(1);
        assertThat(result.postingPeriods().iterator().next().toDate()).isEqualTo(maturityDate);
        verify(account, org.mockito.Mockito.times(2)).calculateInterestUsing(any(), any(), anyBoolean(), anyBoolean(), any(), any(),
                anyBoolean(), anyBoolean());
    }

    @Test
    void simulatedTopUpAndWithdrawalAreAddedAsTransientTransactionsBeforeCalculating() {
        final SavingsAccount account = plainSavingsAccount();

        this.service.calculate(ACCOUNT_ID, new BigDecimal("100"), new BigDecimal("40"));

        verify(account).addTransaction(org.mockito.ArgumentMatchers.argThat(
                txn -> txn.getTransactionType().isDeposit() && txn.getAmount(CURRENCY).getAmount().compareTo(new BigDecimal("100")) == 0));
        verify(account).addTransaction(org.mockito.ArgumentMatchers.argThat(txn -> txn.getTransactionType().isWithdrawal()
                && txn.getAmount(CURRENCY).getAmount().compareTo(new BigDecimal("40")) == 0));
    }

    @Test
    void noSimulatedTransactionIsAddedWhenNeitherAmountIsSupplied() {
        final SavingsAccount account = plainSavingsAccount();

        this.service.calculate(ACCOUNT_ID, null, null);

        verify(account, never()).addTransaction(any(SavingsAccountTransaction.class));
    }

    @Test
    void totalPenaltyChargeIsReadFromSummaryAndIncludesInterestCharges() {
        final DynamicDepositAccount account = mock(DynamicDepositAccount.class);
        commonStubs(account, DepositAccountType.DYNAMIC_DEPOSIT);
        when(this.savingsAccountRepositoryWrapper.findOneWithNotFoundDetection(ACCOUNT_ID)).thenReturn(account);
        final SavingsAccountSummary summary = account.getSummary();
        Mockito.doReturn(new BigDecimal("34")).when(summary).getTotalPenaltyCharge();

        final InterestCalculationData result = this.service.calculate(ACCOUNT_ID, null, null);

        assertThat(result.totalPenaltyCharge()).isEqualByComparingTo("34");
        assertThat(result.accountBalance()).isEqualByComparingTo("1000");
        assertThat(result.totalWithholdTax()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    private DynamicDepositAccount dynamicDepositAccount() {
        final DynamicDepositAccount account = mock(DynamicDepositAccount.class);
        commonStubs(account, DepositAccountType.DYNAMIC_DEPOSIT);
        when(this.savingsAccountRepositoryWrapper.findOneWithNotFoundDetection(ACCOUNT_ID)).thenReturn(account);
        return account;
    }

    private RatePreview ratePreview(final boolean rateChanged) {
        return new RatePreview(mock(DepositAccountDynamicRateHistory.class), new SimulatedRateData(new BigDecimal("8"), new BigDecimal("8"),
                "INTEREST_RATE_CHART", 1L, 2L, LocalDate.now(), new BigDecimal("11000"), new BigDecimal("5"), rateChanged));
    }

    @Test
    void dynamicDepositSimulationInjectsThePreviewRowBeforeCalculatingAndReturnsTheSimulatedRate() {
        final DynamicDepositAccount account = dynamicDepositAccount();
        final RatePreview preview = ratePreview(true);
        when(this.ratePreviewService.preview(eq(account), any(SavingsAccountTransaction.class),
                eq(DynamicDepositRateHistoryEventType.DEPOSIT))).thenReturn(preview);

        final InterestCalculationData result = this.service.calculate(ACCOUNT_ID, new BigDecimal("3000"), null);

        final org.mockito.InOrder inOrder = Mockito.inOrder(account);
        inOrder.verify(account).setSimulatedRateHistory(List.of(preview.row()));
        inOrder.verify(account, org.mockito.Mockito.atLeastOnce()).calculateInterestUsing(any(), any(), anyBoolean(), anyBoolean(), any(),
                any(), anyBoolean(), anyBoolean());
        assertThat(result.simulatedRate()).isSameAs(preview.data());
        assertThat(result.simulatedRate().rateChanged()).isTrue();
    }

    @Test
    void netWithdrawalPreviewsAWithdrawalEvent() {
        final DynamicDepositAccount account = dynamicDepositAccount();
        when(this.ratePreviewService.preview(eq(account), any(SavingsAccountTransaction.class),
                eq(DynamicDepositRateHistoryEventType.WITHDRAWAL))).thenReturn(ratePreview(true));

        final InterestCalculationData result = this.service.calculate(ACCOUNT_ID, new BigDecimal("100"), new BigDecimal("400"));

        assertThat(result.simulatedRate()).isNotNull();
    }

    @Test
    void netZeroSimulationSkipsTheRatePreview() {
        dynamicDepositAccount();

        final InterestCalculationData result = this.service.calculate(ACCOUNT_ID, new BigDecimal("250"), new BigDecimal("250"));

        verify(this.ratePreviewService, never()).preview(any(), any(), any());
        assertThat(result.simulatedRate()).isNull();
    }

    @Test
    void noRatePreviewWithoutASimulationOrForNonDynamicAccounts() {
        dynamicDepositAccount();
        assertThat(this.service.calculate(ACCOUNT_ID, null, null).simulatedRate()).isNull();

        plainSavingsAccount();
        assertThat(this.service.calculate(ACCOUNT_ID, new BigDecimal("50"), null).simulatedRate()).isNull();

        verify(this.ratePreviewService, never()).preview(any(), any(), any());
    }

    @Test
    void neverSavesTheAccountBackToTheRepository() {
        final SavingsAccount account = plainSavingsAccount();

        this.service.calculate(ACCOUNT_ID, new BigDecimal("50"), null);

        org.mockito.Mockito.verifyNoMoreInteractions(org.mockito.Mockito.ignoreStubs(this.savingsAccountRepositoryWrapper));
    }
}
