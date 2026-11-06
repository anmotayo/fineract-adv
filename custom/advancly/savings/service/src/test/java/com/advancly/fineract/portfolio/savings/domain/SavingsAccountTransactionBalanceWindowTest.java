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
package com.advancly.fineract.portfolio.savings.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.advancly.fineract.portfolio.savings.testutil.MoneyHelperInitializer;
import com.advancly.fineract.portfolio.savings.testutil.SavingsAccountTransactionTestBuilder;
import java.math.BigDecimal;
import java.time.LocalDate;
import org.apache.fineract.organisation.monetary.domain.MonetaryCurrency;
import org.apache.fineract.portfolio.savings.SavingsAccountTransactionType;
import org.apache.fineract.portfolio.savings.domain.SavingsAccountTransaction;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * A balance window holds the balance for a number of days that is never negative. When the date the window is closed at
 * is before the transaction it belongs to - the business date, or a deposit's maturity, being earlier than a
 * transaction already on the account - the window holds the balance for no days at all.
 */
class SavingsAccountTransactionBalanceWindowTest {

    private static final MonetaryCurrency CURRENCY = new MonetaryCurrency("USD", 2, null);

    @BeforeEach
    void setUp() {
        MoneyHelperInitializer.initialize();
    }

    @Test
    void aWindowClosedBeforeItsTransactionHoldsTheBalanceForNoDaysNotANegativeNumber() {
        // The deposit of 2026-10-20 on an account whose interest was posted up to the business date 2026-09-29.
        final SavingsAccountTransaction deposit = transaction(LocalDate.of(2026, 10, 20), "106465");

        deposit.updateCumulativeBalanceAndDates(CURRENCY, LocalDate.of(2026, 9, 29));

        assertThat(deposit.getBalanceNumberOfDays()).isZero();
        assertThat(deposit.getBalanceEndDate()).isEqualTo(LocalDate.of(2026, 10, 20));
        assertThat(deposit.getCumulativeBalance()).isEqualByComparingTo("0");
    }

    @Test
    void transactionsOnTheSameDayStillGetExactlyZeroDays() {
        final SavingsAccountTransaction earlier = transaction(LocalDate.of(2026, 9, 30), "6061250");

        // The next transaction is on 2026-09-30 too, so this window is closed the day before it began.
        earlier.updateCumulativeBalanceAndDates(CURRENCY, LocalDate.of(2026, 9, 29));

        assertThat(earlier.getBalanceNumberOfDays()).isZero();
        assertThat(earlier.getBalanceEndDate()).isEqualTo(LocalDate.of(2026, 9, 30));
    }

    @Test
    void anOrdinaryWindowIsUnchanged() {
        final SavingsAccountTransaction withdrawal = transaction(LocalDate.of(2026, 9, 30), "6058750");

        withdrawal.updateCumulativeBalanceAndDates(CURRENCY, LocalDate.of(2026, 10, 6));

        assertThat(withdrawal.getBalanceNumberOfDays()).isEqualTo(7);
        assertThat(withdrawal.getBalanceEndDate()).isEqualTo(LocalDate.of(2026, 10, 6));
        assertThat(withdrawal.getCumulativeBalance()).isEqualByComparingTo("42411250");
    }

    @Test
    void aWindowThatBeginsAndEndsOnTheSameDayHoldsTheBalanceForOneDay() {
        final SavingsAccountTransaction deposit = transaction(LocalDate.of(2026, 6, 5), "100000");

        deposit.updateCumulativeBalanceAndDates(CURRENCY, LocalDate.of(2026, 6, 5));

        assertThat(deposit.getBalanceNumberOfDays()).isEqualTo(1);
        assertThat(deposit.getCumulativeBalance()).isEqualByComparingTo("100000");
    }

    private static SavingsAccountTransaction transaction(final LocalDate date, final String runningBalance) {
        return new SavingsAccountTransactionTestBuilder().withId(1L).withType(SavingsAccountTransactionType.DEPOSIT).withDate(date)
                .withAmount(new BigDecimal("2000")).withRunningBalance(new BigDecimal(runningBalance)).build();
    }
}
