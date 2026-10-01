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

import com.advancly.fineract.portfolio.savings.domain.DepositAccountDynamicDetail;
import com.advancly.fineract.portfolio.savings.domain.DepositAccountDynamicRateHistoryRepository;
import com.advancly.fineract.portfolio.savings.domain.DynamicDepositAccount;
import com.advancly.fineract.portfolio.savings.domain.DynamicDepositAccountRepository;
import com.advancly.fineract.portfolio.savings.domain.DynamicDepositRateHistoryEventType;
import com.advancly.fineract.portfolio.savings.domain.DynamicDepositRateSource;
import com.advancly.fineract.portfolio.savings.service.DynamicDepositRatePreviewService.RatePreview;
import com.advancly.fineract.portfolio.savings.testutil.DepositAccountInterestRateChartTestBuilder;
import com.advancly.fineract.portfolio.savings.testutil.MoneyHelperInitializer;
import com.advancly.fineract.portfolio.savings.testutil.SavingsAccountTransactionTestBuilder;
import java.lang.reflect.Constructor;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Optional;
import org.apache.fineract.organisation.monetary.domain.MonetaryCurrency;
import org.apache.fineract.portfolio.savings.SavingsAccountTransactionType;
import org.apache.fineract.portfolio.savings.SavingsPeriodFrequencyType;
import org.apache.fineract.portfolio.savings.domain.DepositAccountInterestRateChart;
import org.apache.fineract.portfolio.savings.domain.DepositAccountTermAndPreClosure;
import org.apache.fineract.portfolio.savings.domain.DepositPreClosureDetail;
import org.apache.fineract.portfolio.savings.domain.DepositTermDetail;
import org.apache.fineract.portfolio.savings.domain.SavingsAccountTransaction;
import org.apache.fineract.portfolio.savings.domain.SavingsProduct;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * The preview must resolve exactly what a real principal change would, without persisting anything: a top-up that
 * crosses a slab boundary changes the rate, a fixed-rate account keeps its rate, and nothing is ever saved.
 */
class DynamicDepositRatePreviewServiceTest {

    private static final MonetaryCurrency CURRENCY = new MonetaryCurrency("USD", 2, null);
    private static final LocalDate TODAY = LocalDate.of(2026, 6, 10);

    private DepositAccountDynamicRateHistoryRepository historyRepository;
    private DynamicDepositAccountRepository accountRepository;
    private DynamicDepositRatePreviewService service;
    private DynamicDepositAccount account;

    @BeforeEach
    void setUp() {
        MoneyHelperInitializer.initialize();
        this.historyRepository = mock(DepositAccountDynamicRateHistoryRepository.class);
        lenient().when(this.historyRepository.countByAccountId(anyLong())).thenReturn(1L);
        this.accountRepository = mock(DynamicDepositAccountRepository.class);
        this.service = new DynamicDepositRatePreviewService(this.accountRepository, this.historyRepository,
                new DynamicDepositRateHistoryService(this.historyRepository, new DynamicDepositRateResolutionService()));
    }

    private DepositAccountInterestRateChart twoSlabChart() {
        return new DepositAccountInterestRateChartTestBuilder().withSlab(BigDecimal.ZERO, BigDecimal.valueOf(9999), BigDecimal.valueOf(5))
                .withSlab(BigDecimal.valueOf(10000), BigDecimal.valueOf(99999), BigDecimal.valueOf(8)).build();
    }

    private void accountWithInvested(final boolean dynamicRateEnabled, final BigDecimal invested) {
        this.account = buildAccount(dynamicRateEnabled, twoSlabChart());
        final SavingsAccountTransaction activation = new SavingsAccountTransactionTestBuilder().withId(1L).withSavingsAccount(this.account)
                .withType(SavingsAccountTransactionType.DEPOSIT).withDate(LocalDate.of(2026, 5, 1)).withAmount(invested).build();
        this.account.getTransactions().add(activation);
        lenient().when(this.accountRepository.findById(1L)).thenReturn(Optional.of(this.account));
    }

    private SavingsAccountTransaction simulated(final SavingsAccountTransactionType type, final BigDecimal amount) {
        final SavingsAccountTransaction transaction = new SavingsAccountTransactionTestBuilder().withId(null)
                .withSavingsAccount(this.account).withType(type).withDate(TODAY).withAmount(amount).build();
        this.account.getTransactions().add(transaction);
        return transaction;
    }

    @Test
    void topUpCrossingASlabBoundaryResolvesTheHigherRateAndFlagsTheChange() {
        accountWithInvested(true, BigDecimal.valueOf(8000));

        final RatePreview preview = this.service.preview(this.account,
                simulated(SavingsAccountTransactionType.DEPOSIT, BigDecimal.valueOf(3000)), DynamicDepositRateHistoryEventType.DEPOSIT);

        assertThat(preview.data().annualInterestRate()).isEqualByComparingTo("8");
        assertThat(preview.data().previousAnnualInterestRate()).isEqualByComparingTo("5");
        assertThat(preview.data().rateChanged()).isTrue();
        assertThat(preview.data().investedAmountAfter()).isEqualByComparingTo("11000");
        assertThat(preview.data().effectiveFrom()).isEqualTo(TODAY);
        assertThat(preview.data().source()).isEqualTo(DynamicDepositRateSource.INTEREST_RATE_CHART.name());
        assertThat(preview.row().resolvedAnnualInterestRate()).isEqualByComparingTo("8");
        assertThat(preview.row().transactionDate()).isEqualTo(TODAY);
        org.mockito.Mockito.verify(this.historyRepository, org.mockito.Mockito.never()).save(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void withdrawalDroppingBelowTheBoundaryResolvesTheLowerRate() {
        accountWithInvested(true, BigDecimal.valueOf(12000));
        ReflectionTestUtils.setField(this.account, "nominalAnnualInterestRate", BigDecimal.valueOf(8));

        final RatePreview preview = this.service.preview(this.account,
                simulated(SavingsAccountTransactionType.WITHDRAWAL, BigDecimal.valueOf(5000)),
                DynamicDepositRateHistoryEventType.WITHDRAWAL);

        assertThat(preview.data().annualInterestRate()).isEqualByComparingTo("5");
        assertThat(preview.data().investedAmountAfter()).isEqualByComparingTo("7000");
        assertThat(preview.data().rateChanged()).isTrue();
    }

    @Test
    void sameSlabKeepsTheRateAndReportsNoChange() {
        accountWithInvested(true, BigDecimal.valueOf(12000));
        ReflectionTestUtils.setField(this.account, "nominalAnnualInterestRate", BigDecimal.valueOf(8));

        final RatePreview preview = this.service.preview(this.account,
                simulated(SavingsAccountTransactionType.DEPOSIT, BigDecimal.valueOf(500)), DynamicDepositRateHistoryEventType.DEPOSIT);

        assertThat(preview.data().annualInterestRate()).isEqualByComparingTo("8");
        assertThat(preview.data().rateChanged()).isFalse();
    }

    @Test
    void fixedRateAccountCarriesTheLastRateForwardEvenWhenTheAmountCrossesASlab() {
        accountWithInvested(false, BigDecimal.valueOf(8000));
        final com.advancly.fineract.portfolio.savings.domain.DepositAccountDynamicRateHistory lastRow = com.advancly.fineract.portfolio.savings.domain.DepositAccountDynamicRateHistory
                .createNew(this.account, this.account.getTransactions().get(0), LocalDate.of(2026, 5, 1),
                        DynamicDepositRateHistoryEventType.ACCOUNT_ACTIVATION, BigDecimal.valueOf(8000), 12, 2, null, null,
                        BigDecimal.valueOf(5), BigDecimal.valueOf(5), DynamicDepositRateSource.INTEREST_RATE_CHART);
        lenient().when(this.historyRepository.findFirstByAccountIdOrderByTransactionDateDescIdDesc(1L)).thenReturn(lastRow);

        final RatePreview preview = this.service.preview(this.account,
                simulated(SavingsAccountTransactionType.DEPOSIT, BigDecimal.valueOf(3000)), DynamicDepositRateHistoryEventType.DEPOSIT);

        assertThat(preview.data().annualInterestRate()).isEqualByComparingTo("5");
        assertThat(preview.data().rateChanged()).isFalse();
        assertThat(preview.data().investedAmountAfter()).isEqualByComparingTo("11000");
    }

    private DynamicDepositAccount buildAccount(final boolean dynamicRateEnabled, final DepositAccountInterestRateChart chart) {
        final DynamicDepositAccount newAccount = createInstance(DynamicDepositAccount.class);
        ReflectionTestUtils.setField(newAccount, "id", 1L);
        ReflectionTestUtils.setField(newAccount, "currency", CURRENCY);
        ReflectionTestUtils.setField(newAccount, "savingsAccountTransactions", new ArrayList<SavingsAccountTransaction>());
        ReflectionTestUtils.setField(newAccount, "nominalAnnualInterestRate", BigDecimal.valueOf(5));

        final SavingsProduct product = mock(SavingsProduct.class);
        lenient().when(product.nominalAnnualInterestRate()).thenReturn(BigDecimal.valueOf(5));
        ReflectionTestUtils.setField(newAccount, "product", product);
        ReflectionTestUtils.setField(newAccount, "chart", chart);
        chart.updateDepositAccountReference(newAccount);

        final DepositPreClosureDetail preClosureDetail = DepositPreClosureDetail.createFrom(false, null, null);
        final DepositTermDetail depositTermDetail = DepositTermDetail.createFrom(12, 12, SavingsPeriodFrequencyType.MONTHS,
                SavingsPeriodFrequencyType.MONTHS, null, null);
        final DepositAccountTermAndPreClosure accountTermAndPreClosure = DepositAccountTermAndPreClosure.createNew(preClosureDetail,
                depositTermDetail, null, BigDecimal.ZERO, null, null, 12, SavingsPeriodFrequencyType.MONTHS, null, null, false, null, null);
        accountTermAndPreClosure.updateAccountReference(newAccount);
        ReflectionTestUtils.setField(newAccount, "accountTermAndPreClosure", accountTermAndPreClosure);

        newAccount.setDynamicDetail(DepositAccountDynamicDetail.createNew(newAccount, true, dynamicRateEnabled));
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
