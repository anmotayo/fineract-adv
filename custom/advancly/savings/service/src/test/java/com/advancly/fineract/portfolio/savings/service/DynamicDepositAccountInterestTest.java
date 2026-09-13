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

    @BeforeEach
    void setUp() {
        MoneyHelperInitializer.initialize();

        this.rateHistoryRepository = mock(DepositAccountDynamicRateHistoryRepository.class);
        final ApplicationContext applicationContext = mock(ApplicationContext.class);
        lenient().when(applicationContext.getBean(DepositAccountDynamicRateHistoryRepository.class)).thenReturn(this.rateHistoryRepository);
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
