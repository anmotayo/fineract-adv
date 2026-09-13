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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class DynamicDepositInterestWithdrawalServiceTest {

    private static final MonetaryCurrency CURRENCY = new MonetaryCurrency("USD", 2, null);

    private final List<DepositAccountInterestWithdrawal> savedRows = new ArrayList<>();
    private final Map<Long, BigDecimal> alreadyWithdrawnByPostingTxnId = new HashMap<>();
    private DepositAccountInterestWithdrawalRepository repository;
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
        this.service = new DynamicDepositInterestWithdrawalService(this.repository);
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
