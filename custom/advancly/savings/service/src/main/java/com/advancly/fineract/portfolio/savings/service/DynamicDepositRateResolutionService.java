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

import com.advancly.fineract.portfolio.savings.domain.DynamicDepositRateSource;
import java.math.BigDecimal;
import java.time.LocalDate;
import org.apache.fineract.portfolio.savings.SavingsPeriodFrequencyType;
import org.apache.fineract.portfolio.savings.domain.DepositAccountInterestRateChart;
import org.apache.fineract.portfolio.savings.domain.DepositAccountInterestRateChartSlabs;
import org.springframework.stereotype.Component;

/**
 * Resolves the interest rate applicable to a Dynamic Deposit account for a given invested amount and period, or falls
 * back to the account's flat nominal rate - shared by the initial-rate resolution (business rule 6) and every
 * subsequent rate re-resolution on a principal-changing transaction (business rule 7).
 *
 * Resolves against the account's OWN interest rate chart snapshot ({@link DepositAccountInterestRateChart}, captured
 * once at submission time by {@code DynamicDepositAccountAssembler} - mirroring how core
 * {@code FixedDepositAccount}/{@code RecurringDepositAccount} snapshot their product's chart) rather than the product's
 * chart directly, so a later edit to the product's chart never retroactively changes the rate an already-open account
 * resolves against. Also reports which chart/slab matched and whether the result came from the chart or the nominal
 * fallback, since Section 4 of the implementation plan requires that provenance to be captured on every
 * {@code DepositAccountDynamicRateHistory} row.
 */
@Component
public class DynamicDepositRateResolutionService {

    /**
     * @param accountChart
     *            the account's own interest rate chart snapshot (nullable - no chart configured falls back to
     *            {@code nominalAnnualInterestRate}).
     * @param nominalAnnualInterestRate
     *            the flat rate to fall back to when no chart is configured or no slab matches.
     * @param investedAmount
     *            the invested amount to resolve a slab against.
     * @param periodStartDate
     *            start of the period the resolved rate applies from (typically the transaction date).
     * @param depositPeriod
     *            the account's fixed tenor length (business rule 1: tenor never changes).
     * @param depositPeriodFrequencyType
     *            the fixed tenor's frequency unit.
     */
    public ResolvedRate resolve(final DepositAccountInterestRateChart accountChart, final BigDecimal nominalAnnualInterestRate,
            final BigDecimal investedAmount, final LocalDate periodStartDate, final Integer depositPeriod,
            final SavingsPeriodFrequencyType depositPeriodFrequencyType) {
        if (accountChart == null) {
            return nominalFallback(nominalAnnualInterestRate);
        }

        final LocalDate periodEndDate = addPeriod(periodStartDate, depositPeriod, depositPeriodFrequencyType);
        for (final DepositAccountInterestRateChartSlabs slab : accountChart.setOfChartSlabs()) {
            if (slab.slabFields().isBetweenPeriod(periodStartDate, periodEndDate) && slab.slabFields().isAmountBetween(investedAmount)) {
                final BigDecimal baseRate = slab.slabFields().annualInterestRate();
                final BigDecimal resolvedRate = accountChart.getApplicableInterestRate(investedAmount, periodStartDate, periodEndDate,
                        null);
                if (resolvedRate == null || resolvedRate.compareTo(BigDecimal.ZERO) == 0) {
                    return nominalFallback(nominalAnnualInterestRate);
                }
                return new ResolvedRate(resolvedRate, baseRate, DynamicDepositRateSource.INTEREST_RATE_CHART, accountChart.getId(),
                        slab.getId());
            }
        }
        return nominalFallback(nominalAnnualInterestRate);
    }

    private ResolvedRate nominalFallback(final BigDecimal nominalAnnualInterestRate) {
        return new ResolvedRate(nominalAnnualInterestRate, nominalAnnualInterestRate, DynamicDepositRateSource.ACCOUNT_NOMINAL, null, null);
    }

    private LocalDate addPeriod(final LocalDate from, final Integer period, final SavingsPeriodFrequencyType frequencyType) {
        if (from == null || period == null || frequencyType == null) {
            return from;
        }
        return switch (frequencyType) {
            case DAYS -> from.plusDays(period);
            case WEEKS -> from.plusWeeks(period);
            case MONTHS -> from.plusMonths(period);
            case YEARS -> from.plusYears(period);
            default -> from;
        };
    }

    public record ResolvedRate(BigDecimal annualInterestRate, BigDecimal baseAnnualInterestRate, DynamicDepositRateSource source,
            Long interestRateChartId, Long interestRateSlabId) {
    }
}
