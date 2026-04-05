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
package com.advancly.fineract.portfolio.savings.helper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.advancly.fineract.portfolio.savings.testutil.MoneyHelperInitializer;
import com.advancly.fineract.portfolio.savings.testutil.SavingsAccountSummaryTestBuilder;
import com.advancly.fineract.portfolio.savings.testutil.SavingsAccountTestBuilder;
import com.advancly.fineract.portfolio.savings.testutil.SavingsAccountTransactionTestBuilder;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.apache.fineract.organisation.monetary.domain.MonetaryCurrency;
import org.apache.fineract.organisation.monetary.domain.Money;
import org.apache.fineract.portfolio.savings.SavingsAccountTransactionType;
import org.apache.fineract.portfolio.savings.domain.SavingsAccount;
import org.apache.fineract.portfolio.savings.domain.SavingsAccountSummary;
import org.apache.fineract.portfolio.savings.domain.SavingsAccountTransaction;
import org.apache.fineract.portfolio.savings.domain.SavingsAccountTransactionSummaryWrapper;
import org.apache.fineract.portfolio.savings.exception.InsufficientAccountBalanceException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SavingsAccountTransactionHelperTest {

    private SavingsAccountTransactionHelper helper;
    private MonetaryCurrency currency;

    @BeforeEach
    void setUp() {
        MoneyHelperInitializer.initialize();
        helper = new SavingsAccountTransactionHelper(new SavingsAccountTransactionSummaryWrapper());
        currency = new MonetaryCurrency("USD", 2, null);
    }

    @Test
    void testSetRunningBalanceForAppendPath_deposit() {
        SavingsAccountTransaction deposit = new SavingsAccountTransactionTestBuilder().withType(SavingsAccountTransactionType.DEPOSIT)
                .withAmount(BigDecimal.valueOf(500)).build();

        Money lastBalance = Money.of(currency, BigDecimal.valueOf(1000));
        helper.setRunningBalanceForAppendPath(deposit, lastBalance, currency);

        assertThat(deposit.getRunningBalance(currency).getAmount()).isEqualByComparingTo(BigDecimal.valueOf(1500));
    }

    @Test
    void testSetRunningBalanceForAppendPath_withdrawal() {
        SavingsAccountTransaction withdrawal = new SavingsAccountTransactionTestBuilder().withType(SavingsAccountTransactionType.WITHDRAWAL)
                .withAmount(BigDecimal.valueOf(300)).build();

        Money lastBalance = Money.of(currency, BigDecimal.valueOf(1000));
        helper.setRunningBalanceForAppendPath(withdrawal, lastBalance, currency);

        assertThat(withdrawal.getRunningBalance(currency).getAmount()).isEqualByComparingTo(BigDecimal.valueOf(700));
    }

    @Test
    void testValidateBalanceForAppendPath_sufficientBalance() {
        SavingsAccountSummary summary = new SavingsAccountSummaryTestBuilder().withAccountBalance(BigDecimal.valueOf(1000)).build();
        SavingsAccount account = new SavingsAccountTestBuilder().withSummary(summary).build();

        // Should not throw
        helper.validateBalanceForAppendPath(account, BigDecimal.valueOf(500), currency);
    }

    @Test
    void testValidateBalanceForAppendPath_insufficientBalance() {
        SavingsAccountSummary summary = new SavingsAccountSummaryTestBuilder().withAccountBalance(BigDecimal.valueOf(100)).build();
        SavingsAccount account = new SavingsAccountTestBuilder().withSummary(summary).build();

        assertThatThrownBy(() -> helper.validateBalanceForAppendPath(account, BigDecimal.valueOf(500), currency))
                .isInstanceOf(InsufficientAccountBalanceException.class);
    }

    @Test
    void testValidateBalanceForAppendPath_withMinRequiredBalance() {
        SavingsAccountSummary summary = new SavingsAccountSummaryTestBuilder().withAccountBalance(BigDecimal.valueOf(1000)).build();
        SavingsAccount account = new SavingsAccountTestBuilder().withSummary(summary).withMinRequiredBalance(BigDecimal.valueOf(200))
                .build();

        // 1000 - 900 = 100 < 200 min required -> should throw
        assertThatThrownBy(() -> helper.validateBalanceForAppendPath(account, BigDecimal.valueOf(900), currency))
                .isInstanceOf(InsufficientAccountBalanceException.class);
    }

    @Test
    void testValidateBalanceForAppendPath_withOverdraft() {
        SavingsAccountSummary summary = new SavingsAccountSummaryTestBuilder().withAccountBalance(BigDecimal.valueOf(100)).build();
        SavingsAccount account = new SavingsAccountTestBuilder().withSummary(summary).withOverdraft(BigDecimal.valueOf(500)).build();

        // 100 + 500 overdraft = 600 available -> 200 withdrawal should succeed
        helper.validateBalanceForAppendPath(account, BigDecimal.valueOf(200), currency);
    }

    @Test
    void testRecalculateDailyBalancesFromDate() {
        List<SavingsAccountTransaction> transactions = new ArrayList<>();
        transactions.add(new SavingsAccountTransactionTestBuilder().withId(1L).withType(SavingsAccountTransactionType.DEPOSIT)
                .withDate(LocalDate.of(2025, 7, 1)).withAmount(BigDecimal.valueOf(500)).build());
        transactions.add(new SavingsAccountTransactionTestBuilder().withId(2L).withType(SavingsAccountTransactionType.WITHDRAWAL)
                .withDate(LocalDate.of(2025, 7, 2)).withAmount(BigDecimal.valueOf(200)).build());
        transactions.add(new SavingsAccountTransactionTestBuilder().withId(3L).withType(SavingsAccountTransactionType.DEPOSIT)
                .withDate(LocalDate.of(2025, 7, 3)).withAmount(BigDecimal.valueOf(100)).build());

        Money openingBalance = Money.of(currency, BigDecimal.valueOf(1000));
        helper.recalculateDailyBalancesFromDate(transactions, openingBalance, currency);

        assertThat(transactions.get(0).getRunningBalance(currency).getAmount()).isEqualByComparingTo(BigDecimal.valueOf(1500));
        assertThat(transactions.get(1).getRunningBalance(currency).getAmount()).isEqualByComparingTo(BigDecimal.valueOf(1300));
        assertThat(transactions.get(2).getRunningBalance(currency).getAmount()).isEqualByComparingTo(BigDecimal.valueOf(1400));
    }

    @Test
    void testRecalculateDailyBalancesFromDate_skipsReversed() {
        List<SavingsAccountTransaction> transactions = new ArrayList<>();
        transactions.add(new SavingsAccountTransactionTestBuilder().withId(1L).withType(SavingsAccountTransactionType.DEPOSIT)
                .withDate(LocalDate.of(2025, 7, 1)).withAmount(BigDecimal.valueOf(500)).build());
        transactions.add(new SavingsAccountTransactionTestBuilder().withId(2L).withType(SavingsAccountTransactionType.DEPOSIT)
                .withDate(LocalDate.of(2025, 7, 2)).withAmount(BigDecimal.valueOf(200)).reversed().build());
        transactions.add(new SavingsAccountTransactionTestBuilder().withId(3L).withType(SavingsAccountTransactionType.DEPOSIT)
                .withDate(LocalDate.of(2025, 7, 3)).withAmount(BigDecimal.valueOf(100)).build());

        Money openingBalance = Money.of(currency, BigDecimal.valueOf(1000));
        helper.recalculateDailyBalancesFromDate(transactions, openingBalance, currency);

        assertThat(transactions.get(0).getRunningBalance(currency).getAmount()).isEqualByComparingTo(BigDecimal.valueOf(1500));
        // Reversed transaction should have zeroed balance
        assertThat(transactions.get(2).getRunningBalance(currency).getAmount()).isEqualByComparingTo(BigDecimal.valueOf(1600));
    }

    @Test
    void testValidateBalanceDoesNotBecomeNegative_valid() {
        List<SavingsAccountTransaction> transactions = new ArrayList<>();
        transactions.add(new SavingsAccountTransactionTestBuilder().withType(SavingsAccountTransactionType.DEPOSIT)
                .withDate(LocalDate.of(2025, 7, 1)).withAmount(BigDecimal.valueOf(500)).build());

        SavingsAccount account = new SavingsAccountTestBuilder().build();
        Money openingBalance = Money.of(currency, BigDecimal.valueOf(1000));

        // Should not throw
        helper.validateBalanceDoesNotBecomeNegative(account, transactions, openingBalance, currency);
    }

    @Test
    void testValidateBalanceDoesNotBecomeNegative_invalid() {
        List<SavingsAccountTransaction> transactions = new ArrayList<>();
        transactions.add(new SavingsAccountTransactionTestBuilder().withType(SavingsAccountTransactionType.WITHDRAWAL)
                .withDate(LocalDate.of(2025, 7, 1)).withAmount(BigDecimal.valueOf(1500)).build());

        SavingsAccount account = new SavingsAccountTestBuilder().build();
        Money openingBalance = Money.of(currency, BigDecimal.valueOf(1000));

        assertThatThrownBy(() -> helper.validateBalanceDoesNotBecomeNegative(account, transactions, openingBalance, currency))
                .isInstanceOf(InsufficientAccountBalanceException.class);
    }

    @Test
    void testIsBeforeLastPostingPeriod_true() {
        List<SavingsAccountTransaction> interestTransactions = new ArrayList<>();
        interestTransactions.add(new SavingsAccountTransactionTestBuilder().withType(SavingsAccountTransactionType.INTEREST_POSTING)
                .withDate(LocalDate.of(2025, 6, 30)).withAmount(BigDecimal.valueOf(10)).build());

        boolean result = helper.isBeforeLastPostingPeriod(LocalDate.of(2025, 6, 15), interestTransactions);

        assertThat(result).isTrue();
    }

    @Test
    void testIsBeforeLastPostingPeriod_false() {
        List<SavingsAccountTransaction> interestTransactions = new ArrayList<>();
        interestTransactions.add(new SavingsAccountTransactionTestBuilder().withType(SavingsAccountTransactionType.INTEREST_POSTING)
                .withDate(LocalDate.of(2025, 6, 30)).withAmount(BigDecimal.valueOf(10)).build());

        boolean result = helper.isBeforeLastPostingPeriod(LocalDate.of(2025, 7, 15), interestTransactions);

        assertThat(result).isFalse();
    }

    @Test
    void testIsBeforeLastPostingPeriod_noPostings() {
        List<SavingsAccountTransaction> emptyList = new ArrayList<>();

        boolean result = helper.isBeforeLastPostingPeriod(LocalDate.of(2025, 7, 15), emptyList);

        assertThat(result).isFalse();
    }

    @Test
    void testCalculateAndUpdateSummaryInSinglePass_depositsAndWithdrawals() {
        SavingsAccountSummary summary = new SavingsAccountSummaryTestBuilder().build();
        SavingsAccount account = new SavingsAccountTestBuilder().withSummary(summary).build();

        List<SavingsAccountTransaction> transactions = new ArrayList<>();
        transactions.add(new SavingsAccountTransactionTestBuilder().withId(1L).withType(SavingsAccountTransactionType.DEPOSIT)
                .withDate(LocalDate.of(2025, 7, 1)).withAmount(BigDecimal.valueOf(1000)).build());
        transactions.add(new SavingsAccountTransactionTestBuilder().withId(2L).withType(SavingsAccountTransactionType.WITHDRAWAL)
                .withDate(LocalDate.of(2025, 7, 2)).withAmount(BigDecimal.valueOf(300)).build());
        transactions.add(new SavingsAccountTransactionTestBuilder().withId(3L).withType(SavingsAccountTransactionType.DEPOSIT)
                .withDate(LocalDate.of(2025, 7, 3)).withAmount(BigDecimal.valueOf(500)).build());

        helper.calculateAndUpdateSummaryInSinglePass(account, transactions, currency);

        assertThat(account.getSummary().getTotalDeposits()).isEqualByComparingTo(BigDecimal.valueOf(1500));
        assertThat(account.getSummary().getTotalWithdrawals()).isEqualByComparingTo(BigDecimal.valueOf(300));
        assertThat(account.getSummary().getAccountBalance()).isEqualByComparingTo(BigDecimal.valueOf(1200));
    }

    @Test
    void testCalculateAndUpdateSummaryInSinglePass_skipsReversedTransactions() {
        SavingsAccountSummary summary = new SavingsAccountSummaryTestBuilder().build();
        SavingsAccount account = new SavingsAccountTestBuilder().withSummary(summary).build();

        List<SavingsAccountTransaction> transactions = new ArrayList<>();
        transactions.add(new SavingsAccountTransactionTestBuilder().withId(1L).withType(SavingsAccountTransactionType.DEPOSIT)
                .withDate(LocalDate.of(2025, 7, 1)).withAmount(BigDecimal.valueOf(1000)).build());
        transactions.add(new SavingsAccountTransactionTestBuilder().withId(2L).withType(SavingsAccountTransactionType.DEPOSIT)
                .withDate(LocalDate.of(2025, 7, 2)).withAmount(BigDecimal.valueOf(500)).reversed().build());

        helper.calculateAndUpdateSummaryInSinglePass(account, transactions, currency);

        assertThat(account.getSummary().getTotalDeposits()).isEqualByComparingTo(BigDecimal.valueOf(1000));
        assertThat(account.getSummary().getAccountBalance()).isEqualByComparingTo(BigDecimal.valueOf(1000));
    }

    @Test
    void testCalculateAndUpdateSummaryInSinglePass_emptyTransactions() {
        SavingsAccountSummary summary = new SavingsAccountSummaryTestBuilder().build();
        SavingsAccount account = new SavingsAccountTestBuilder().withSummary(summary).build();

        helper.calculateAndUpdateSummaryInSinglePass(account, new ArrayList<>(), currency);

        assertThat(account.getSummary().getTotalDeposits()).isNull();
        assertThat(account.getSummary().getTotalWithdrawals()).isNull();
        assertThat(account.getSummary().getAccountBalance()).isEqualByComparingTo(BigDecimal.ZERO);
    }
}
