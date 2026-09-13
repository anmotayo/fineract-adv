package com.advancly.fineract.portfolio.savings.domain;

import static org.assertj.core.api.Assertions.assertThat;

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

    private DepositAccountDynamicRateHistory rateRow(final LocalDate transactionDate, final BigDecimal resolvedRate) {
        return DepositAccountDynamicRateHistory.createNew(null, null, transactionDate, DynamicDepositRateHistoryEventType.DEPOSIT,
                BigDecimal.ZERO, 12, null, null, null, resolvedRate, resolvedRate, DynamicDepositRateSource.ACCOUNT_NOMINAL);
    }
}
