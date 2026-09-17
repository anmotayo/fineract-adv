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

import com.advancly.fineract.portfolio.savings.domain.DepositAccountInterestWithdrawal;
import com.advancly.fineract.portfolio.savings.domain.DepositAccountInterestWithdrawalRepository;
import com.advancly.fineract.portfolio.savings.domain.DynamicDepositAccount;
import com.advancly.fineract.portfolio.savings.domain.EarlyWithdrawalChargeMode;
import com.advancly.fineract.portfolio.savings.domain.SavingsAccountInterestChargeRepository;
import com.advancly.fineract.portfolio.savings.domain.SavingsProductEarlyWithdrawalCharge;
import com.advancly.fineract.portfolio.savings.domain.SavingsProductEarlyWithdrawalChargeRepository;
import com.advancly.fineract.portfolio.savings.testutil.MoneyHelperInitializer;
import com.advancly.fineract.portfolio.savings.testutil.SavingsAccountTransactionTestBuilder;
import java.lang.reflect.Constructor;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.fineract.organisation.monetary.domain.MonetaryCurrency;
import org.apache.fineract.portfolio.savings.SavingsAccountTransactionType;
import org.apache.fineract.portfolio.savings.domain.SavingsAccountTransaction;
import org.apache.fineract.portfolio.savings.domain.SavingsProduct;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class DynamicDepositInterestWithdrawalServiceTest {

    private static final MonetaryCurrency CURRENCY = new MonetaryCurrency("USD", 2, null);
    private static final Long PRODUCT_ID = 42L;
    private static final Long CHARGE_ID = 7L;

    private final List<DepositAccountInterestWithdrawal> savedRows = new ArrayList<>();
    private final Map<Long, BigDecimal> alreadyWithdrawnByPostingTxnId = new HashMap<>();
    private DepositAccountInterestWithdrawalRepository repository;
    private SavingsAccountInterestChargeRepository interestChargeRepository;
    private SavingsProductEarlyWithdrawalChargeRepository productEarlyWithdrawalChargeRepository;
    private DynamicDepositInterestWithdrawalService service;
    private DynamicDepositAccount account;

    @BeforeEach
    void setUp() {
        MoneyHelperInitializer.initialize();
        this.repository = mock(DepositAccountInterestWithdrawalRepository.class);
        lenient().when(this.repository.save(org.mockito.ArgumentMatchers.any())).thenAnswer(invocation -> {
            final DepositAccountInterestWithdrawal row = invocation.getArgument(0);
            this.savedRows.add(row);
            this.alreadyWithdrawnByPostingTxnId.merge(row.interestPostingTransaction().getId(), row.withdrawnInterestAmount(),
                    BigDecimal::add);
            return row;
        });
        lenient().when(this.repository.sumWithdrawnInterestForPostingTransaction(anyLong()))
                .thenAnswer(invocation -> this.alreadyWithdrawnByPostingTxnId.getOrDefault(invocation.getArgument(0), BigDecimal.ZERO));

        this.interestChargeRepository = mock(SavingsAccountInterestChargeRepository.class);
        lenient().when(this.interestChargeRepository.sumPostedChargeAmount(anyLong())).thenReturn(BigDecimal.ZERO);
        this.productEarlyWithdrawalChargeRepository = mock(SavingsProductEarlyWithdrawalChargeRepository.class);
        // No early-withdrawal charge selection at all by default, so every pre-existing scenario keeps its exact
        // behaviour; the cumulative scenarios below opt in explicitly.
        lenient().when(this.productEarlyWithdrawalChargeRepository.findBySavingsProductId(anyLong())).thenReturn(List.of());

        this.service = new DynamicDepositInterestWithdrawalService(this.repository, this.interestChargeRepository,
                this.productEarlyWithdrawalChargeRepository);
    }

    @Test
    void interestAlreadyForfeitedUnderCumulativeModeIsNeverAlsoReportedAsWithdrawn() {
        // The withdrawal that triggers a 100% forfeiture: the forfeiture has already run at the write-platform layer
        // by the time this hook sees the withdrawal, so the whole 50 of posted interest is gone from the balance and
        // this 20 can only be principal.
        this.account = mockAccountWithTransactions();
        cumulativeProductWithForfeitedInterest(BigDecimal.valueOf(50));
        addTransaction(interestPosting(10L, LocalDate.of(2026, 1, 31), BigDecimal.valueOf(50)));
        final SavingsAccountTransaction withdrawal = withdrawal(11L, LocalDate.of(2026, 2, 1), BigDecimal.valueOf(20));

        this.service.recordIfApplicable(this.account, withdrawal);

        assertThat(this.savedRows).isEmpty();
    }

    @Test
    void onlyTheInterestThatSurvivedAPartialForfeitureStaysAttributableToAWithdrawal() {
        // 80 posted across two periods, 50 of it forfeited - so only 30 can ever be attributed, and the forfeiture is
        // consumed oldest-first, exactly as attribution itself is.
        this.account = mockAccountWithTransactions();
        cumulativeProductWithForfeitedInterest(BigDecimal.valueOf(50));
        addTransaction(interestPosting(10L, LocalDate.of(2026, 1, 31), BigDecimal.valueOf(30)));
        final SavingsAccountTransaction secondPosting = interestPosting(12L, LocalDate.of(2026, 2, 28), BigDecimal.valueOf(50));
        addTransaction(secondPosting);
        final SavingsAccountTransaction withdrawal = withdrawal(13L, LocalDate.of(2026, 3, 1), BigDecimal.valueOf(100));

        this.service.recordIfApplicable(this.account, withdrawal);

        assertThat(this.savedRows).hasSize(1);
        assertThat(this.savedRows.get(0).interestPostingTransaction()).isEqualTo(secondPosting);
        assertThat(this.savedRows.get(0).withdrawnInterestAmount()).isEqualByComparingTo("30");
    }

    @Test
    void aPerPeriodProductsAttributionIsUntouchedEvenWhenChargesHaveBeenPosted() {
        // sumPostedChargeAmount is non-zero here too, but a PER_PERIOD product must keep the behaviour it shipped
        // with: the deduction above is deliberately scoped to cumulative mode only.
        this.account = mockAccountWithTransactions();
        lenient().when(this.productEarlyWithdrawalChargeRepository.findBySavingsProductId(PRODUCT_ID)).thenReturn(
                List.of(SavingsProductEarlyWithdrawalCharge.createNew(PRODUCT_ID, CHARGE_ID, EarlyWithdrawalChargeMode.PER_PERIOD)));
        lenient().when(this.interestChargeRepository.sumPostedChargeAmount(anyLong())).thenReturn(BigDecimal.valueOf(50));
        final SavingsAccountTransaction posting = interestPosting(10L, LocalDate.of(2026, 1, 31), BigDecimal.valueOf(50));
        addTransaction(posting);
        final SavingsAccountTransaction withdrawal = withdrawal(11L, LocalDate.of(2026, 2, 1), BigDecimal.valueOf(20));

        this.service.recordIfApplicable(this.account, withdrawal);

        assertThat(this.savedRows).hasSize(1);
        assertThat(this.savedRows.get(0).withdrawnInterestAmount()).isEqualByComparingTo("20");
    }

    private void cumulativeProductWithForfeitedInterest(final BigDecimal forfeited) {
        lenient().when(this.productEarlyWithdrawalChargeRepository.findBySavingsProductId(PRODUCT_ID)).thenReturn(
                List.of(SavingsProductEarlyWithdrawalCharge.createNew(PRODUCT_ID, CHARGE_ID, EarlyWithdrawalChargeMode.CUMULATIVE)));
        lenient().when(this.interestChargeRepository.sumPostedChargeAmount(anyLong())).thenReturn(forfeited);
    }

    @Test
    void withdrawalSmallerThanAvailableInterestConsumesOnlyThatMuchFromOldestPosting() {
        this.account = mockAccountWithTransactions();
        final SavingsAccountTransaction interestPosting = interestPosting(10L, LocalDate.of(2026, 1, 31), BigDecimal.valueOf(50));
        addTransaction(interestPosting);
        final SavingsAccountTransaction withdrawal = withdrawal(11L, LocalDate.of(2026, 2, 1), BigDecimal.valueOf(20));

        this.service.recordIfApplicable(this.account, withdrawal);

        assertThat(this.savedRows).hasSize(1);
        assertThat(this.savedRows.get(0).interestPostingTransaction()).isEqualTo(interestPosting);
        assertThat(this.savedRows.get(0).withdrawnInterestAmount()).isEqualByComparingTo("20");
    }

    @Test
    void withdrawalLargerThanOnePostingSpillsFifoIntoTheNextOldestPosting() {
        this.account = mockAccountWithTransactions();
        final SavingsAccountTransaction firstPosting = interestPosting(10L, LocalDate.of(2026, 1, 31), BigDecimal.valueOf(30));
        final SavingsAccountTransaction secondPosting = interestPosting(12L, LocalDate.of(2026, 2, 28), BigDecimal.valueOf(50));
        addTransaction(firstPosting);
        addTransaction(secondPosting);
        final SavingsAccountTransaction withdrawal = withdrawal(13L, LocalDate.of(2026, 3, 1), BigDecimal.valueOf(60));

        this.service.recordIfApplicable(this.account, withdrawal);

        assertThat(this.savedRows).hasSize(2);
        assertThat(this.savedRows.get(0).interestPostingTransaction()).isEqualTo(firstPosting);
        assertThat(this.savedRows.get(0).withdrawnInterestAmount()).isEqualByComparingTo("30");
        assertThat(this.savedRows.get(1).interestPostingTransaction()).isEqualTo(secondPosting);
        assertThat(this.savedRows.get(1).withdrawnInterestAmount()).isEqualByComparingTo("30");
    }

    @Test
    void withdrawalDoesNothingWhenNoPostedInterestIsAvailable() {
        this.account = mockAccountWithTransactions();
        final SavingsAccountTransaction withdrawal = withdrawal(11L, LocalDate.of(2026, 2, 1), BigDecimal.valueOf(20));

        this.service.recordIfApplicable(this.account, withdrawal);

        assertThat(this.savedRows).isEmpty();
    }

    @Test
    void alreadyFullyWithdrawnPostingIsSkipped() {
        this.account = mockAccountWithTransactions();
        final SavingsAccountTransaction firstPosting = interestPosting(10L, LocalDate.of(2026, 1, 31), BigDecimal.valueOf(30));
        addTransaction(firstPosting);
        this.alreadyWithdrawnByPostingTxnId.put(10L, BigDecimal.valueOf(30));
        final SavingsAccountTransaction secondPosting = interestPosting(12L, LocalDate.of(2026, 2, 28), BigDecimal.valueOf(50));
        addTransaction(secondPosting);
        final SavingsAccountTransaction withdrawal = withdrawal(13L, LocalDate.of(2026, 3, 1), BigDecimal.valueOf(15));

        this.service.recordIfApplicable(this.account, withdrawal);

        assertThat(this.savedRows).hasSize(1);
        assertThat(this.savedRows.get(0).interestPostingTransaction()).isEqualTo(secondPosting);
        assertThat(this.savedRows.get(0).withdrawnInterestAmount()).isEqualByComparingTo("15");
    }

    private SavingsAccountTransaction interestPosting(final Long id, final LocalDate date, final BigDecimal amount) {
        return new SavingsAccountTransactionTestBuilder().withId(id).withSavingsAccount(this.account)
                .withType(SavingsAccountTransactionType.INTEREST_POSTING).withDate(date).withAmount(amount).build();
    }

    private SavingsAccountTransaction withdrawal(final Long id, final LocalDate date, final BigDecimal amount) {
        return new SavingsAccountTransactionTestBuilder().withId(id).withSavingsAccount(this.account)
                .withType(SavingsAccountTransactionType.WITHDRAWAL).withDate(date).withAmount(amount).build();
    }

    private void addTransaction(final SavingsAccountTransaction transaction) {
        this.account.getTransactions().add(transaction);
    }

    private DynamicDepositAccount mockAccountWithTransactions() {
        final DynamicDepositAccount newAccount = createInstance(DynamicDepositAccount.class);
        ReflectionTestUtils.setField(newAccount, "id", 1L);
        ReflectionTestUtils.setField(newAccount, "currency", CURRENCY);
        ReflectionTestUtils.setField(newAccount, "savingsAccountTransactions", new ArrayList<SavingsAccountTransaction>());
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
