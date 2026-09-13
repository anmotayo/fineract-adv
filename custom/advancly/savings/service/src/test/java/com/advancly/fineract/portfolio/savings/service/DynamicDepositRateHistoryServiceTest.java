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
import com.advancly.fineract.portfolio.savings.domain.DepositAccountDynamicRateHistory;
import com.advancly.fineract.portfolio.savings.domain.DepositAccountDynamicRateHistoryRepository;
import com.advancly.fineract.portfolio.savings.domain.DynamicDepositAccount;
import com.advancly.fineract.portfolio.savings.domain.DynamicDepositRateHistoryEventType;
import com.advancly.fineract.portfolio.savings.testutil.MoneyHelperInitializer;
import com.advancly.fineract.portfolio.savings.testutil.SavingsAccountTransactionTestBuilder;
import java.lang.reflect.Constructor;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import org.apache.fineract.organisation.monetary.domain.MonetaryCurrency;
import org.apache.fineract.portfolio.savings.SavingsAccountTransactionType;
import org.apache.fineract.portfolio.savings.SavingsPeriodFrequencyType;
import org.apache.fineract.portfolio.savings.domain.DepositAccountTermAndPreClosure;
import org.apache.fineract.portfolio.savings.domain.DepositPreClosureDetail;
import org.apache.fineract.portfolio.savings.domain.DepositTermDetail;
import org.apache.fineract.portfolio.savings.domain.SavingsAccountTransaction;
import org.apache.fineract.portfolio.savings.domain.SavingsAccountTransactionComparator;
import org.apache.fineract.portfolio.savings.domain.SavingsProduct;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Verifies this correction: invested amount is derived by summing the account's own transactions (never an
 * incrementally-mutated cache), and a backdated transaction retroactively corrects every already-written
 * {@link DepositAccountDynamicRateHistory} row dated after it - since backdating can change what was true for the
 * interval each of those rows represents.
 */
class DynamicDepositRateHistoryServiceTest {

    private static final MonetaryCurrency CURRENCY = new MonetaryCurrency("USD", 2, null);
    private static final SavingsAccountTransactionComparator TRANSACTION_COMPARATOR = new SavingsAccountTransactionComparator();

    private final DynamicDepositRateResolutionService rateResolutionService = new DynamicDepositRateResolutionService();
    private final List<DepositAccountDynamicRateHistory> savedRows = new ArrayList<>();
    private DepositAccountDynamicRateHistoryRepository repository;
    private DynamicDepositRateHistoryService service;
    private DynamicDepositAccount account;

    @BeforeEach
    void setUp() {
        MoneyHelperInitializer.initialize();
        this.repository = mock(DepositAccountDynamicRateHistoryRepository.class);
        lenient().when(this.repository.countByAccountId(anyLong())).thenAnswer(invocation -> (long) this.savedRows.size());
        lenient().when(this.repository.save(org.mockito.ArgumentMatchers.any())).thenAnswer(invocation -> {
            final DepositAccountDynamicRateHistory row = invocation.getArgument(0);
            this.savedRows.add(row);
            return row;
        });
        lenient().when(this.repository.findByAccountIdOrderByTransactionDateAscIdAsc(anyLong())).thenAnswer(invocation -> sortedRows());
        lenient().when(this.repository.findFirstByAccountIdOrderByTransactionDateDescIdDesc(anyLong())).thenAnswer(invocation -> {
            final List<DepositAccountDynamicRateHistory> sorted = sortedRows();
            return sorted.isEmpty() ? null : sorted.get(sorted.size() - 1);
        });
        this.service = new DynamicDepositRateHistoryService(this.repository, this.rateResolutionService);
    }

    @Test
    void backdatedDepositRetroactivelyCorrectsAnAlreadyWrittenLaterRow() {
        this.account = buildAccount(false, BigDecimal.valueOf(2));

        final SavingsAccountTransaction activation = transaction(1L, SavingsAccountTransactionType.DEPOSIT, LocalDate.of(2026, 1, 1),
                BigDecimal.valueOf(1000));
        this.service.recordPrincipalChangeEvent(this.account, activation, DynamicDepositRateHistoryEventType.ACCOUNT_ACTIVATION);

        final SavingsAccountTransaction laterDeposit = transaction(2L, SavingsAccountTransactionType.DEPOSIT, LocalDate.of(2026, 3, 1),
                BigDecimal.valueOf(500));
        this.service.recordPrincipalChangeEvent(this.account, laterDeposit, DynamicDepositRateHistoryEventType.DEPOSIT);

        assertThat(rowFor(laterDeposit).investedAmountAfterTransaction()).isEqualByComparingTo("1500");
        assertThat(this.account.accountTermAndPreClosure().depositAmount()).isEqualByComparingTo("1500");

        // backdated deposit, chronologically between activation and laterDeposit
        final SavingsAccountTransaction backdatedDeposit = transaction(3L, SavingsAccountTransactionType.DEPOSIT, LocalDate.of(2026, 2, 1),
                BigDecimal.valueOf(300));
        this.service.recordPrincipalChangeEvent(this.account, backdatedDeposit, DynamicDepositRateHistoryEventType.DEPOSIT);

        // the backdated event's own invested amount only reflects transactions up to its own date
        assertThat(rowFor(backdatedDeposit).investedAmountAfterTransaction()).isEqualByComparingTo("1300");
        // the already-written later row is retroactively corrected to include the backdated deposit
        assertThat(rowFor(laterDeposit).investedAmountAfterTransaction()).isEqualByComparingTo("1800");
        // the cache reflects the true, corrected, chronologically-latest invested amount
        assertThat(this.account.accountTermAndPreClosure().depositAmount()).isEqualByComparingTo("1800");
        // dynamic_rate_enabled = false: the rate never changes from what activation resolved (business rule 7)
        assertThat(rowFor(backdatedDeposit).resolvedAnnualInterestRate()).isEqualByComparingTo("2");
        assertThat(rowFor(laterDeposit).resolvedAnnualInterestRate()).isEqualByComparingTo("2");
    }

    @Test
    void undoingABackdatedDepositCorrectsTheLaterRowBackDown() {
        this.account = buildAccount(false, BigDecimal.valueOf(2));

        final SavingsAccountTransaction activation = transaction(1L, SavingsAccountTransactionType.DEPOSIT, LocalDate.of(2026, 1, 1),
                BigDecimal.valueOf(1000));
        this.service.recordPrincipalChangeEvent(this.account, activation, DynamicDepositRateHistoryEventType.ACCOUNT_ACTIVATION);

        final SavingsAccountTransaction laterDeposit = transaction(2L, SavingsAccountTransactionType.DEPOSIT, LocalDate.of(2026, 3, 1),
                BigDecimal.valueOf(500));
        this.service.recordPrincipalChangeEvent(this.account, laterDeposit, DynamicDepositRateHistoryEventType.DEPOSIT);

        final SavingsAccountTransaction backdatedDeposit = transaction(3L, SavingsAccountTransactionType.DEPOSIT, LocalDate.of(2026, 2, 1),
                BigDecimal.valueOf(300));
        this.service.recordPrincipalChangeEvent(this.account, backdatedDeposit, DynamicDepositRateHistoryEventType.DEPOSIT);
        assertThat(rowFor(laterDeposit).investedAmountAfterTransaction()).isEqualByComparingTo("1800");

        // simulate core SavingsAccount#undoTransaction already having marked it reversed before this hook runs
        ReflectionTestUtils.setField(backdatedDeposit, "reversed", true);
        this.service.reverseInvestedAmountForUndo(this.account, backdatedDeposit);

        assertThat(rowFor(laterDeposit).investedAmountAfterTransaction()).isEqualByComparingTo("1500");
        assertThat(this.account.accountTermAndPreClosure().depositAmount()).isEqualByComparingTo("1500");
    }

    @Test
    void withdrawalReducesInvestedAmountComputedFromTransactions() {
        this.account = buildAccount(false, BigDecimal.valueOf(2));

        final SavingsAccountTransaction activation = transaction(1L, SavingsAccountTransactionType.DEPOSIT, LocalDate.of(2026, 1, 1),
                BigDecimal.valueOf(1000));
        this.service.recordPrincipalChangeEvent(this.account, activation, DynamicDepositRateHistoryEventType.ACCOUNT_ACTIVATION);

        final SavingsAccountTransaction withdrawal = transaction(2L, SavingsAccountTransactionType.WITHDRAWAL, LocalDate.of(2026, 2, 1),
                BigDecimal.valueOf(400));
        this.service.recordPrincipalChangeEvent(this.account, withdrawal, DynamicDepositRateHistoryEventType.WITHDRAWAL);

        assertThat(rowFor(withdrawal).investedAmountAfterTransaction()).isEqualByComparingTo("600");
        assertThat(this.account.accountTermAndPreClosure().depositAmount()).isEqualByComparingTo("600");
    }

    private DepositAccountDynamicRateHistory rowFor(final SavingsAccountTransaction transaction) {
        return this.savedRows.stream().filter(row -> row.transaction() == transaction).findFirst()
                .orElseThrow(() -> new AssertionError("No rate history row recorded for transaction " + transaction.getId()));
    }

    private List<DepositAccountDynamicRateHistory> sortedRows() {
        return this.savedRows.stream().sorted((a, b) -> TRANSACTION_COMPARATOR.compare(a.transaction(), b.transaction()))
                .collect(Collectors.toList());
    }

    private SavingsAccountTransaction transaction(final Long id, final SavingsAccountTransactionType type, final LocalDate date,
            final BigDecimal amount) {
        final SavingsAccountTransaction transaction = new SavingsAccountTransactionTestBuilder().withId(id).withSavingsAccount(this.account)
                .withType(type).withDate(date).withAmount(amount).build();
        this.account.getTransactions().add(transaction);
        return transaction;
    }

    private DynamicDepositAccount buildAccount(final boolean dynamicRateEnabled, final BigDecimal nominalAnnualInterestRate) {
        final DynamicDepositAccount newAccount = createInstance(DynamicDepositAccount.class);
        ReflectionTestUtils.setField(newAccount, "id", 1L);
        ReflectionTestUtils.setField(newAccount, "currency", CURRENCY);
        ReflectionTestUtils.setField(newAccount, "savingsAccountTransactions", new ArrayList<SavingsAccountTransaction>());
        ReflectionTestUtils.setField(newAccount, "nominalAnnualInterestRate", nominalAnnualInterestRate);

        final SavingsProduct product = mock(SavingsProduct.class);
        lenient().when(product.nominalAnnualInterestRate()).thenReturn(nominalAnnualInterestRate);
        ReflectionTestUtils.setField(newAccount, "product", product);

        final DepositPreClosureDetail preClosureDetail = DepositPreClosureDetail.createFrom(false, null, null);
        final DepositTermDetail depositTermDetail = DepositTermDetail.createFrom(12, 12, SavingsPeriodFrequencyType.MONTHS,
                SavingsPeriodFrequencyType.MONTHS, null, null);
        final DepositAccountTermAndPreClosure accountTermAndPreClosure = DepositAccountTermAndPreClosure.createNew(preClosureDetail,
                depositTermDetail, null, BigDecimal.ZERO, null, null, 12, SavingsPeriodFrequencyType.MONTHS, null, null, false, null, null);
        accountTermAndPreClosure.updateAccountReference(newAccount);
        ReflectionTestUtils.setField(newAccount, "accountTermAndPreClosure", accountTermAndPreClosure);

        final DepositAccountDynamicDetail dynamicDetail = DepositAccountDynamicDetail.createNew(newAccount, true, dynamicRateEnabled);
        newAccount.setDynamicDetail(dynamicDetail);

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
