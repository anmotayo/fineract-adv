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

import com.advancly.fineract.portfolio.savings.domain.DynamicDepositRateSource;
import com.advancly.fineract.portfolio.savings.service.DynamicDepositRateResolutionService.ResolvedRate;
import com.advancly.fineract.portfolio.savings.testutil.DepositAccountInterestRateChartTestBuilder;
import com.advancly.fineract.portfolio.savings.testutil.MoneyHelperInitializer;
import java.math.BigDecimal;
import java.time.LocalDate;
import org.apache.fineract.portfolio.savings.SavingsPeriodFrequencyType;
import org.apache.fineract.portfolio.savings.domain.DepositAccountInterestRateChart;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Verifies rate resolution against an account's own {@link DepositAccountInterestRateChart} snapshot (this correction)
 * - slab matching by invested amount, and the nominal-rate fallback when no chart/slab applies.
 */
class DynamicDepositRateResolutionServiceTest {

    private final DynamicDepositRateResolutionService service = new DynamicDepositRateResolutionService();

    @BeforeEach
    void setUp() {
        MoneyHelperInitializer.initialize();
    }

    @Test
    void resolvesLowerSlabForSmallInvestedAmount() {
        final DepositAccountInterestRateChart chart = new DepositAccountInterestRateChartTestBuilder()
                .withSlab(BigDecimal.ZERO, BigDecimal.valueOf(999.99), BigDecimal.valueOf(2))
                .withSlab(BigDecimal.valueOf(1000), null, BigDecimal.valueOf(3)).build();

        final ResolvedRate resolved = service.resolve(chart, BigDecimal.valueOf(1), BigDecimal.valueOf(500), LocalDate.of(2026, 1, 1), 12,
                SavingsPeriodFrequencyType.MONTHS);

        assertThat(resolved.annualInterestRate()).isEqualByComparingTo("2");
        assertThat(resolved.source()).isEqualTo(DynamicDepositRateSource.INTEREST_RATE_CHART);
    }

    @Test
    void resolvesHigherSlabWhenInvestedAmountCrossesThreshold() {
        final DepositAccountInterestRateChart chart = new DepositAccountInterestRateChartTestBuilder()
                .withSlab(BigDecimal.ZERO, BigDecimal.valueOf(999.99), BigDecimal.valueOf(2))
                .withSlab(BigDecimal.valueOf(1000), null, BigDecimal.valueOf(3)).build();

        final ResolvedRate resolved = service.resolve(chart, BigDecimal.valueOf(1), BigDecimal.valueOf(1500), LocalDate.of(2026, 1, 1), 12,
                SavingsPeriodFrequencyType.MONTHS);

        assertThat(resolved.annualInterestRate()).isEqualByComparingTo("3");
        assertThat(resolved.source()).isEqualTo(DynamicDepositRateSource.INTEREST_RATE_CHART);
    }

    @Test
    void fallsBackToNominalRateWhenNoChartConfigured() {
        final ResolvedRate resolved = service.resolve(null, BigDecimal.valueOf(1.5), BigDecimal.valueOf(500), LocalDate.of(2026, 1, 1), 12,
                SavingsPeriodFrequencyType.MONTHS);

        assertThat(resolved.annualInterestRate()).isEqualByComparingTo("1.5");
        assertThat(resolved.source()).isEqualTo(DynamicDepositRateSource.ACCOUNT_NOMINAL);
    }

    @Test
    void fallsBackToNominalRateWhenNoSlabMatchesAmount() {
        final DepositAccountInterestRateChart chart = new DepositAccountInterestRateChartTestBuilder()
                .withSlab(BigDecimal.valueOf(1000), null, BigDecimal.valueOf(3)).build();

        final ResolvedRate resolved = service.resolve(chart, BigDecimal.valueOf(1.5), BigDecimal.valueOf(500), LocalDate.of(2026, 1, 1), 12,
                SavingsPeriodFrequencyType.MONTHS);

        assertThat(resolved.annualInterestRate()).isEqualByComparingTo("1.5");
        assertThat(resolved.source()).isEqualTo(DynamicDepositRateSource.ACCOUNT_NOMINAL);
    }
}
