package com.advancly.fineract.portfolio.savings.domain;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.apache.fineract.infrastructure.core.domain.LocalDateInterval;
import org.apache.fineract.infrastructure.core.service.DateUtils;

/**
 * Splits each core interest-posting-period interval (from {@code SavingsHelper.determineInterestPostingPeriods}) at
 * every {@code DepositAccountDynamicRateHistory} transaction date that falls strictly inside it, since a rate change
 * mid-period (e.g. a top-up crossing a chart slab) means part of that period earned one rate and the rest earned
 * another. Pure function - no JPA/Spring dependency - so it is unit-testable in isolation from
 * {@code DynamicDepositAccount#calculateInterestUsing}, which is the only caller.
 */
public final class DynamicDepositInterestIntervalSplitter {

    private DynamicDepositInterestIntervalSplitter() {
        //
    }

    public static List<RatedInterval> split(final List<LocalDateInterval> corePostingPeriods,
            final List<DepositAccountDynamicRateHistory> rateHistoryAscending) {
        final List<RatedInterval> result = new ArrayList<>();
        for (final LocalDateInterval corePeriod : corePostingPeriods) {
            LocalDate subIntervalStart = corePeriod.startDate();
            BigDecimal currentRate = resolveRateAsOf(rateHistoryAscending, subIntervalStart);
            for (final DepositAccountDynamicRateHistory row : rateHistoryAscending) {
                final LocalDate changeDate = row.transactionDate();
                final boolean fallsStrictlyInsideRemainderOfPeriod = DateUtils.isAfter(changeDate, subIntervalStart)
                        && !DateUtils.isAfter(changeDate, corePeriod.endDate());
                if (fallsStrictlyInsideRemainderOfPeriod) {
                    result.add(new RatedInterval(LocalDateInterval.create(subIntervalStart, changeDate.minusDays(1)), currentRate));
                    subIntervalStart = changeDate;
                    currentRate = row.resolvedAnnualInterestRate();
                }
            }
            result.add(new RatedInterval(LocalDateInterval.create(subIntervalStart, corePeriod.endDate()), currentRate));
        }
        return result;
    }

    private static BigDecimal resolveRateAsOf(final List<DepositAccountDynamicRateHistory> rateHistoryAscending, final LocalDate date) {
        BigDecimal rate = BigDecimal.ZERO;
        for (final DepositAccountDynamicRateHistory row : rateHistoryAscending) {
            if (!DateUtils.isAfter(row.transactionDate(), date)) {
                rate = row.resolvedAnnualInterestRate();
            } else {
                break;
            }
        }
        return rate;
    }

    public record RatedInterval(LocalDateInterval periodInterval, BigDecimal annualInterestRate) {
    }
}
