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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.apache.fineract.infrastructure.core.domain.LocalDateInterval;
import org.junit.jupiter.api.Test;

class DynamicDepositInterestIntervalSplitterTest {

    @Test
    void returnsWholePeriodUnsplitWhenNoRateChangeFallsInsideIt() {
        final LocalDateInterval period = LocalDateInterval.create(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 31));
        final DepositAccountDynamicRateHistory activation = rateRow(LocalDate.of(2025, 12, 1), BigDecimal.valueOf(2));

        final List<DynamicDepositInterestIntervalSplitter.RatedInterval> result = DynamicDepositInterestIntervalSplitter
                .split(List.of(period), List.of(activation));

        assertThat(result).hasSize(1);
        assertThat(result.get(0).periodInterval().startDate()).isEqualTo(LocalDate.of(2026, 1, 1));
        assertThat(result.get(0).periodInterval().endDate()).isEqualTo(LocalDate.of(2026, 1, 31));
        assertThat(result.get(0).annualInterestRate()).isEqualByComparingTo("2");
    }

    @Test
    void splitsPeriodAtARateChangeThatFallsInsideIt() {
        final LocalDateInterval period = LocalDateInterval.create(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 31));
        final DepositAccountDynamicRateHistory activation = rateRow(LocalDate.of(2025, 12, 1), BigDecimal.valueOf(2));
        final DepositAccountDynamicRateHistory topUp = rateRow(LocalDate.of(2026, 1, 15), BigDecimal.valueOf(3));

        final List<DynamicDepositInterestIntervalSplitter.RatedInterval> result = DynamicDepositInterestIntervalSplitter
                .split(List.of(period), List.of(activation, topUp));

        assertThat(result).hasSize(2);
        assertThat(result.get(0).periodInterval().startDate()).isEqualTo(LocalDate.of(2026, 1, 1));
        assertThat(result.get(0).periodInterval().endDate()).isEqualTo(LocalDate.of(2026, 1, 14));
        assertThat(result.get(0).annualInterestRate()).isEqualByComparingTo("2");
        assertThat(result.get(1).periodInterval().startDate()).isEqualTo(LocalDate.of(2026, 1, 15));
        assertThat(result.get(1).periodInterval().endDate()).isEqualTo(LocalDate.of(2026, 1, 31));
        assertThat(result.get(1).annualInterestRate()).isEqualByComparingTo("3");
    }

    @Test
    void splitsAcrossTwoRateChangesInsideTheSamePeriod() {
        final LocalDateInterval period = LocalDateInterval.create(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 31));
        final DepositAccountDynamicRateHistory activation = rateRow(LocalDate.of(2025, 12, 1), BigDecimal.valueOf(2));
        final DepositAccountDynamicRateHistory topUp1 = rateRow(LocalDate.of(2026, 1, 10), BigDecimal.valueOf(3));
        final DepositAccountDynamicRateHistory topUp2 = rateRow(LocalDate.of(2026, 1, 20), BigDecimal.valueOf(4));

        final List<DynamicDepositInterestIntervalSplitter.RatedInterval> result = DynamicDepositInterestIntervalSplitter
                .split(List.of(period), List.of(activation, topUp1, topUp2));

        assertThat(result).hasSize(3);
        assertThat(result.get(0).annualInterestRate()).isEqualByComparingTo("2");
        assertThat(result.get(1).annualInterestRate()).isEqualByComparingTo("3");
        assertThat(result.get(2).annualInterestRate()).isEqualByComparingTo("4");
        assertThat(result.get(2).periodInterval().endDate()).isEqualTo(LocalDate.of(2026, 1, 31));
    }

    @Test
    void ignoresRateChangesOutsideThePeriodBeingSplit() {
        final LocalDateInterval period = LocalDateInterval.create(LocalDate.of(2026, 2, 1), LocalDate.of(2026, 2, 28));
        final DepositAccountDynamicRateHistory activation = rateRow(LocalDate.of(2025, 12, 1), BigDecimal.valueOf(2));
        final DepositAccountDynamicRateHistory earlierTopUp = rateRow(LocalDate.of(2026, 1, 15), BigDecimal.valueOf(3));

        final List<DynamicDepositInterestIntervalSplitter.RatedInterval> result = DynamicDepositInterestIntervalSplitter
                .split(List.of(period), List.of(activation, earlierTopUp));

        assertThat(result).hasSize(1);
        assertThat(result.get(0).annualInterestRate()).isEqualByComparingTo("3");
    }

    @Test
    void throwsWhenNoRateHistoryRowPrecedesThePeriod() {
        final LocalDateInterval period = LocalDateInterval.create(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 31));
        final DepositAccountDynamicRateHistory laterRow = rateRow(LocalDate.of(2026, 2, 1), BigDecimal.valueOf(2));

        assertThatThrownBy(() -> DynamicDepositInterestIntervalSplitter.split(List.of(period), List.of(laterRow)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("No dynamic deposit rate history found");
    }

    private DepositAccountDynamicRateHistory rateRow(final LocalDate transactionDate, final BigDecimal resolvedRate) {
        return DepositAccountDynamicRateHistory.createNew(null, null, transactionDate, DynamicDepositRateHistoryEventType.DEPOSIT,
                BigDecimal.ZERO, 12, null, null, null, resolvedRate, resolvedRate, DynamicDepositRateSource.ACCOUNT_NOMINAL);
    }
}
