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
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

import com.advancly.fineract.portfolio.savings.domain.DepositAccountDynamicRateHistory;
import com.advancly.fineract.portfolio.savings.domain.DepositAccountDynamicRateHistoryRepository;
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
import org.apache.fineract.portfolio.client.domain.Client;
import org.apache.fineract.portfolio.savings.SavingsAccountTransactionType;
import org.apache.fineract.portfolio.savings.domain.DepositAccountTermAndPreClosure;
import org.apache.fineract.portfolio.savings.domain.SavingsAccountSummary;
import org.apache.fineract.portfolio.savings.domain.SavingsAccountTransaction;
import org.apache.fineract.portfolio.savings.domain.SavingsAccountTransactionSummaryWrapper;
import org.apache.fineract.portfolio.savings.domain.SavingsHelper;
import org.apache.fineract.portfolio.savings.service.SavingsInterestReadPlatformService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationContext;
import org.springframework.test.util.ReflectionTestUtils;

/** Rate varies per rate-history interval inside a posting period, still one INTEREST_POSTING per boundary. */
class DynamicDepositAccountInterestTest {

    private static final MonetaryCurrency CURRENCY = new MonetaryCurrency("USD", 2, null);

    private DynamicDepositAccount account;
    private DepositAccountDynamicRateHistoryRepository rateHistoryRepository;
    private DynamicDepositRateHistoryService rateHistoryService;

    @BeforeEach
    void setUp() {
        MoneyHelperInitializer.initialize();

        this.rateHistoryRepository = mock(DepositAccountDynamicRateHistoryRepository.class);
        this.rateHistoryService = mock(DynamicDepositRateHistoryService.class);
        final ApplicationContext applicationContext = mock(ApplicationContext.class);
        lenient().when(applicationContext.getBean(DepositAccountDynamicRateHistoryRepository.class)).thenReturn(this.rateHistoryRepository);
        // Used only by undoTransaction(...).
        lenient().when(applicationContext.getBean(DynamicDepositRateHistoryService.class)).thenReturn(this.rateHistoryService);
        lenient().when(applicationContext.getBean(CumulativeInterestForfeitureService.class))
                .thenReturn(mock(CumulativeInterestForfeitureService.class));
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
        // January's posting is dated Feb 1, so the bound must reach Feb 1.
        this.account.postInterest(mc, LocalDate.of(2026, 2, 1), false, false, 1, null, false, true);

        final List<SavingsAccountTransaction> interestPostings = this.account.getTransactions().stream()
                .filter(SavingsAccountTransaction::isInterestPostingAndNotReversed).toList();

        assertThat(interestPostings).hasSize(1);

        // 14 days at 1000 @ 2%, then 17 days at 2000 @ 3% (simple interest, 365-day year).
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

        this.account.postInterest(mc, LocalDate.of(2026, 2, 1), false, false, 1, null, false, true);
        final List<SavingsAccountTransaction> afterSecondCall = this.account.getTransactions().stream()
                .filter(SavingsAccountTransaction::isInterestPostingAndNotReversed).toList();

        assertThat(afterSecondCall).hasSize(1);
        assertThat(afterSecondCall.get(0).getAmount()).isEqualByComparingTo(amountAfterFirstCall);
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
        // Through Mar 1 so both the Feb 1 and Mar 1 postings are created.
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

        final BigDecimal expectedJanuary = BigDecimal.valueOf(1000).multiply(dailyFractionAt2Pct).multiply(BigDecimal.valueOf(14))
                .add(BigDecimal.valueOf(2000).multiply(dailyFractionAt3Pct).multiply(BigDecimal.valueOf(17)))
                .setScale(2, MoneyHelper.getRoundingMode());
        final BigDecimal expectedFebruary = BigDecimal.valueOf(2000).multiply(dailyFractionAt3Pct).multiply(BigDecimal.valueOf(28))
                .setScale(2, MoneyHelper.getRoundingMode());

        assertThat(januaryPosting.getAmount()).isEqualByComparingTo(expectedJanuary);
        assertThat(februaryPosting.getAmount()).isEqualByComparingTo(expectedFebruary);
    }

    @Test
    void postingWritesNoInterestChargeTransactionWhenNothingIsPending() {
        this.account = buildAccount();
        stubSingleRateHistoryForJanuary();

        this.account.postInterest(MoneyHelper.getMathContext(), LocalDate.of(2026, 2, 1), false, false, 1, null, false, true);

        assertThat(this.account.getTransactions()).noneMatch(SavingsAccountTransaction::isInterestCharge);
    }

    @Test
    void anInterestChargeDoesNotShrinkTheInterestBearingBalanceUnderNoCompounding() {
        final BigDecimal interestAfterInterestCharge = interestOverFebruary(true);
        final BigDecimal interestWithoutInterestCharge = interestOverFebruary(false);

        // Under no-compounding an interest charge does not reduce the interest-bearing balance.
        assertThat(interestAfterInterestCharge).isEqualByComparingTo(interestWithoutInterestCharge);
    }

    private BigDecimal interestOverFebruary(final boolean addInterestCharge) {
        this.account = buildAccount();

        final SavingsAccountTransaction openingDeposit = transaction(1L, LocalDate.of(2026, 1, 1), BigDecimal.valueOf(1000));
        lenient().when(this.rateHistoryRepository.findByAccountIdOrderByTransactionDateAscIdAsc(this.account.getId()))
                .thenReturn(List.of(DepositAccountDynamicRateHistory.createNew(this.account, openingDeposit, LocalDate.of(2026, 1, 1),
                        DynamicDepositRateHistoryEventType.ACCOUNT_ACTIVATION, BigDecimal.valueOf(1000), 12, 2, null, null,
                        BigDecimal.valueOf(2), BigDecimal.valueOf(2), DynamicDepositRateSource.INTEREST_RATE_CHART)));

        if (addInterestCharge) {
            this.account.addTransaction(new SavingsAccountTransactionTestBuilder().withId(2L).withSavingsAccount(this.account)
                    .withType(SavingsAccountTransactionType.INTEREST_CHARGE).withDate(LocalDate.of(2026, 2, 1))
                    .withAmount(BigDecimal.valueOf(500)).build());
        }

        this.account.postInterest(MoneyHelper.getMathContext(), LocalDate.of(2026, 3, 1), false, false, 1, null, false, true);

        return this.account.getTransactions().stream().filter(SavingsAccountTransaction::isInterestPostingAndNotReversed)
                .filter(posting -> posting.getTransactionDate().isAfter(LocalDate.of(2026, 2, 1))).findFirst()
                .orElseThrow(() -> new IllegalStateException("no February interest posting was created")).getAmount();
    }

    /** 1000 at 2% from Jan 1, topped up to 2000 at 3% on Jan 15. */
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

    @Test
    void simulatedRateHistoryRowSplitsThePostingPeriodLikeAPersistedOneWithoutBeingSaved() {
        this.account = buildAccount();

        final SavingsAccountTransaction openingDeposit = transaction(1L, LocalDate.of(2026, 1, 1), BigDecimal.valueOf(1000));
        final SavingsAccountTransaction simulatedTopUp = transaction(null, LocalDate.of(2026, 1, 15), BigDecimal.valueOf(1000));

        lenient().when(this.rateHistoryRepository.findByAccountIdOrderByTransactionDateAscIdAsc(this.account.getId()))
                .thenReturn(List.of(DepositAccountDynamicRateHistory.createNew(this.account, openingDeposit, LocalDate.of(2026, 1, 1),
                        DynamicDepositRateHistoryEventType.ACCOUNT_ACTIVATION, BigDecimal.valueOf(1000), 12, 2, null, null,
                        BigDecimal.valueOf(2), BigDecimal.valueOf(2), DynamicDepositRateSource.INTEREST_RATE_CHART)));
        this.account.setSimulatedRateHistory(List.of(DepositAccountDynamicRateHistory.createNew(this.account, simulatedTopUp,
                LocalDate.of(2026, 1, 15), DynamicDepositRateHistoryEventType.DEPOSIT, BigDecimal.valueOf(2000), 12, 2, null, null,
                BigDecimal.valueOf(3), BigDecimal.valueOf(3), DynamicDepositRateSource.INTEREST_RATE_CHART)));

        final MathContext mc = MoneyHelper.getMathContext();
        this.account.postInterest(mc, LocalDate.of(2026, 2, 1), false, false, 1, null, false, true);

        final List<SavingsAccountTransaction> interestPostings = this.account.getTransactions().stream()
                .filter(SavingsAccountTransaction::isInterestPostingAndNotReversed).toList();
        assertThat(interestPostings).hasSize(1);

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
    void simulatedRowWinsASameDateTieAgainstAPersistedRow() {
        this.account = buildAccount();

        final SavingsAccountTransaction openingDeposit = transaction(1L, LocalDate.of(2026, 1, 1), BigDecimal.valueOf(1000));
        final SavingsAccountTransaction persistedSameDay = transaction(2L, LocalDate.of(2026, 1, 15), BigDecimal.valueOf(500));
        final SavingsAccountTransaction simulatedSameDay = transaction(null, LocalDate.of(2026, 1, 15), BigDecimal.valueOf(500));

        lenient().when(this.rateHistoryRepository.findByAccountIdOrderByTransactionDateAscIdAsc(this.account.getId()))
                .thenReturn(List.of(
                        DepositAccountDynamicRateHistory.createNew(this.account, openingDeposit, LocalDate.of(2026, 1, 1),
                                DynamicDepositRateHistoryEventType.ACCOUNT_ACTIVATION, BigDecimal.valueOf(1000), 12, 2, null, null,
                                BigDecimal.valueOf(2), BigDecimal.valueOf(2), DynamicDepositRateSource.INTEREST_RATE_CHART),
                        DepositAccountDynamicRateHistory.createNew(this.account, persistedSameDay, LocalDate.of(2026, 1, 15),
                                DynamicDepositRateHistoryEventType.DEPOSIT, BigDecimal.valueOf(1500), 12, 2, null, null,
                                BigDecimal.valueOf(3), BigDecimal.valueOf(3), DynamicDepositRateSource.INTEREST_RATE_CHART)));
        this.account.setSimulatedRateHistory(List.of(DepositAccountDynamicRateHistory.createNew(this.account, simulatedSameDay,
                LocalDate.of(2026, 1, 15), DynamicDepositRateHistoryEventType.DEPOSIT, BigDecimal.valueOf(2000), 12, 2, null, null,
                BigDecimal.valueOf(5), BigDecimal.valueOf(5), DynamicDepositRateSource.INTEREST_RATE_CHART)));

        final MathContext mc = MoneyHelper.getMathContext();
        this.account.postInterest(mc, LocalDate.of(2026, 2, 1), false, false, 1, null, false, true);

        final SavingsAccountTransaction posting = this.account.getTransactions().stream()
                .filter(SavingsAccountTransaction::isInterestPostingAndNotReversed).findFirst().orElseThrow();
        // The simulated 5% applies, not the persisted 3%; each rate segment is rounded separately.
        final BigDecimal segment1 = BigDecimal.valueOf(1000).multiply(BigDecimal.valueOf(0.02)).multiply(BigDecimal.valueOf(14))
                .divide(BigDecimal.valueOf(365), mc).setScale(2, MoneyHelper.getRoundingMode());
        final BigDecimal segment2 = BigDecimal.valueOf(2000).multiply(BigDecimal.valueOf(0.05)).multiply(BigDecimal.valueOf(17))
                .divide(BigDecimal.valueOf(365), mc).setScale(2, MoneyHelper.getRoundingMode());
        final BigDecimal expected = segment1.add(segment2);
        assertThat(posting.getAmount()).isEqualByComparingTo(expected);
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
        // postInterest always reads withHoldTaxPostingType(), which needs accountTermAndPreClosure.
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
