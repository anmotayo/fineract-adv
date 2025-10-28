package org.apache.fineract.portfolio.savings.domain.interest;

import org.apache.fineract.infrastructure.core.domain.LocalDateInterval;
import org.apache.fineract.organisation.monetary.domain.MoneyHelper;
import org.apache.fineract.portfolio.savings.SavingsCompoundingInterestPeriodType;
import org.apache.fineract.portfolio.savings.SavingsInterestCalculationType;

import java.math.BigDecimal;
import java.math.MathContext;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

public final class NoCompoundingPeriod implements CompoundingPeriod {
    private final LocalDateInterval periodInterval;
    private final List<EndOfDayBalance> endOfDayBalances;
    public static NoCompoundingPeriod create(final LocalDateInterval postingPeriodInterval, final List<EndOfDayBalance> allEndOfDayBalances,
             final LocalDate upToInterestCalculationDate) {

        final List<EndOfDayBalance> endOfDayBalancesWithinPeriod = endOfDayBalancesWithinPeriodInterval(postingPeriodInterval, allEndOfDayBalances,
                upToInterestCalculationDate);
        return new NoCompoundingPeriod(postingPeriodInterval, endOfDayBalancesWithinPeriod);
    }

    @Override
    public BigDecimal calculateInterest(final SavingsCompoundingInterestPeriodType compoundingInterestPeriodType,
                final SavingsInterestCalculationType interestCalculationType, final BigDecimal interestToCompound,
                final BigDecimal interestRateAsFraction, final long daysInYear, final BigDecimal minBalanceForInterestCalculation,
                final BigDecimal overdraftInterestRateAsFraction, final BigDecimal minOverdraftForInterestCalculation) {
        BigDecimal interestEarned = BigDecimal.ZERO;

        switch (interestCalculationType) {
            case DAILY_BALANCE:
                interestEarned = calculateUsingDailyBalanceMethod(compoundingInterestPeriodType, interestToCompound, interestRateAsFraction,
                        daysInYear, minBalanceForInterestCalculation, overdraftInterestRateAsFraction, minOverdraftForInterestCalculation);
                break;
            case AVERAGE_DAILY_BALANCE:
                interestEarned = calculateUsingAverageDailyBalanceMethod(interestToCompound, interestRateAsFraction, daysInYear,
                        minBalanceForInterestCalculation, overdraftInterestRateAsFraction, minOverdraftForInterestCalculation);
                break;
            case INVALID:
                break;
        }

        return interestEarned;
    }

    private BigDecimal calculateUsingAverageDailyBalanceMethod(final BigDecimal interestToCompound, final BigDecimal interestRateAsFraction,
               final long daysInYear, final BigDecimal minBalanceForInterestCalculation, final BigDecimal overdraftInterestRateAsFraction,
               final BigDecimal minOverdraftForInterestCalculation) {

        BigDecimal cumulativeBalance = BigDecimal.ZERO;
        Integer numberOfDays = Integer.valueOf(0);

        for (final EndOfDayBalance balance : this.endOfDayBalances) {
            final BigDecimal endOfDayCumulativeBalance = balance.cumulativeBalance(interestToCompound);
            cumulativeBalance = cumulativeBalance.add(endOfDayCumulativeBalance);

            final Integer balanceExistsForNumberOfDays = balance.getNumberOfDays();
            numberOfDays = numberOfDays + balanceExistsForNumberOfDays;
        }

        BigDecimal interestEarned = BigDecimal.ZERO;
        if (cumulativeBalance.compareTo(BigDecimal.ZERO) != 0 && numberOfDays > 0) {
            final BigDecimal averageDailyBalance = cumulativeBalance.divide(BigDecimal.valueOf(numberOfDays), MathContext.DECIMAL64)
                    .setScale(9, MoneyHelper.getRoundingMode());

            if (averageDailyBalance.compareTo(BigDecimal.ZERO) >= 0) {
                if (averageDailyBalance.compareTo(minBalanceForInterestCalculation) >= 0) {
                    final BigDecimal multiplicand = BigDecimal.ONE.divide(BigDecimal.valueOf(daysInYear), MathContext.DECIMAL64);
                    final BigDecimal dailyInterestRate = interestRateAsFraction.multiply(multiplicand, MathContext.DECIMAL64);
                    final BigDecimal periodicInterestRate = dailyInterestRate.multiply(BigDecimal.valueOf(numberOfDays),
                            MathContext.DECIMAL64);
                    interestEarned = averageDailyBalance.multiply(periodicInterestRate, MathContext.DECIMAL64).setScale(9,
                            MoneyHelper.getRoundingMode());
                }
            } else {
                if (averageDailyBalance.compareTo(minOverdraftForInterestCalculation.negate()) < 0) {
                    final BigDecimal multiplicand = BigDecimal.ONE.divide(BigDecimal.valueOf(daysInYear), MathContext.DECIMAL64);
                    final BigDecimal dailyInterestRate = overdraftInterestRateAsFraction.multiply(multiplicand, MathContext.DECIMAL64);
                    final BigDecimal periodicInterestRate = dailyInterestRate.multiply(BigDecimal.valueOf(numberOfDays),
                            MathContext.DECIMAL64);
                    interestEarned = averageDailyBalance.multiply(periodicInterestRate, MathContext.DECIMAL64).setScale(9,
                            MoneyHelper.getRoundingMode());
                }
            }
        }

        return interestEarned;
    }

    private BigDecimal calculateUsingDailyBalanceMethod(final SavingsCompoundingInterestPeriodType compoundingInterestPeriodType,
                final BigDecimal interestToCompound, final BigDecimal interestRateAsFraction, final long daysInYear,
                final BigDecimal minBalanceForInterestCalculation, final BigDecimal overdraftInterestRateAsFraction,
                final BigDecimal minOverdraftForInterestCalculation) {

        BigDecimal interestEarned = BigDecimal.ZERO;
        BigDecimal interestOnBalanceUnrounded = BigDecimal.ZERO;
        for (final EndOfDayBalance balance : this.endOfDayBalances) {

            switch (compoundingInterestPeriodType) {
                case DAILY:
                    interestOnBalanceUnrounded = balance.calculateInterestOnBalanceAndInterest(interestToCompound, interestRateAsFraction,
                            daysInYear, minBalanceForInterestCalculation, overdraftInterestRateAsFraction,
                            minOverdraftForInterestCalculation);
                    break;
                case MONTHLY:
                    interestOnBalanceUnrounded = balance.calculateInterestOnBalance(interestToCompound, interestRateAsFraction, daysInYear,
                            minBalanceForInterestCalculation, overdraftInterestRateAsFraction, minOverdraftForInterestCalculation);
                    break;
                case QUATERLY:
                    interestOnBalanceUnrounded = balance.calculateInterestOnBalance(interestToCompound, interestRateAsFraction, daysInYear,
                            minBalanceForInterestCalculation, overdraftInterestRateAsFraction, minOverdraftForInterestCalculation);
                    break;

                case BI_ANNUAL:
                    interestOnBalanceUnrounded = balance.calculateInterestOnBalance(interestToCompound, interestRateAsFraction, daysInYear,
                            minBalanceForInterestCalculation, overdraftInterestRateAsFraction, minOverdraftForInterestCalculation);
                    break;
                case ANNUAL:
                    interestOnBalanceUnrounded = balance.calculateInterestOnBalance(interestToCompound, interestRateAsFraction, daysInYear,
                            minBalanceForInterestCalculation, overdraftInterestRateAsFraction, minOverdraftForInterestCalculation);
                    break;
                case NO_COMPOUNDING_SIMPLE_INTEREST:
                    interestOnBalanceUnrounded = balance.calculateInterestOnBalance(interestToCompound, interestRateAsFraction, daysInYear,
                            minBalanceForInterestCalculation, overdraftInterestRateAsFraction, minOverdraftForInterestCalculation);
                    break;
                case INVALID:
                    break;
            }

            interestEarned = interestEarned.add(interestOnBalanceUnrounded);
        }
        return interestEarned;
    }

    @Override
    public LocalDateInterval getPeriodInterval() {
        return this.periodInterval;
    }

    private static List<EndOfDayBalance> endOfDayBalancesWithinPeriodInterval(final LocalDateInterval compoundingPeriodInterval,
              final List<EndOfDayBalance> allEndOfDayBalances, final LocalDate upToInterestCalculationDate) {

        final List<EndOfDayBalance> endOfDayBalancesForPeriodInterval = new ArrayList<>();

        for (final EndOfDayBalance endOfDayBalance : allEndOfDayBalances) {

            if (compoundingPeriodInterval.contains(endOfDayBalance.date())) {
                final EndOfDayBalance cappedToPeriodEndDate = endOfDayBalance.upTo(compoundingPeriodInterval, upToInterestCalculationDate);
                endOfDayBalancesForPeriodInterval.add(cappedToPeriodEndDate);
            } else if (endOfDayBalance.contains(compoundingPeriodInterval)) {
                final EndOfDayBalance cappedToPeriodEndDate = endOfDayBalance.upTo(compoundingPeriodInterval, upToInterestCalculationDate);
                endOfDayBalancesForPeriodInterval.add(cappedToPeriodEndDate);
            }
        }

        return endOfDayBalancesForPeriodInterval;
    }

    private NoCompoundingPeriod(final LocalDateInterval postingPeriodInterval, final List<EndOfDayBalance> endOfDayBalances) {
        this.periodInterval = postingPeriodInterval;
        this.endOfDayBalances = endOfDayBalances;
    }
}
