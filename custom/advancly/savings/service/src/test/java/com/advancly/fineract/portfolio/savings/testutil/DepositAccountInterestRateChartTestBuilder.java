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
package com.advancly.fineract.portfolio.savings.testutil;

import java.lang.reflect.Constructor;
import java.math.BigDecimal;
import java.util.HashSet;
import java.util.Set;
import org.apache.fineract.portfolio.interestratechart.domain.InterestRateChartSlabFields;
import org.apache.fineract.portfolio.savings.domain.DepositAccountInterestRateChart;
import org.apache.fineract.portfolio.savings.domain.DepositAccountInterestRateChartSlabs;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Builds a {@link DepositAccountInterestRateChart} with amount-range-only slabs (no period gating) directly via
 * reflection, bypassing the product-side {@code InterestRateChart}/{@code InterestRateChartSlab} construction that
 * {@code DepositAccountInterestRateChart#from(...)} normally clones from.
 */
public class DepositAccountInterestRateChartTestBuilder {

    private final Set<DepositAccountInterestRateChartSlabs> slabs = new HashSet<>();

    public DepositAccountInterestRateChartTestBuilder withSlab(final BigDecimal amountRangeFrom, final BigDecimal amountRangeTo,
            final BigDecimal annualInterestRate) {
        final InterestRateChartSlabFields slabFields = InterestRateChartSlabFields.createNew(null, null, null, null, amountRangeFrom,
                amountRangeTo, annualInterestRate, "USD");
        final DepositAccountInterestRateChartSlabs slab = createInstance(DepositAccountInterestRateChartSlabs.class);
        ReflectionTestUtils.setField(slab, "slabFields", slabFields);
        this.slabs.add(slab);
        return this;
    }

    public DepositAccountInterestRateChart build() {
        final DepositAccountInterestRateChart chart = createInstance(DepositAccountInterestRateChart.class);
        ReflectionTestUtils.setField(chart, "chartSlabs", this.slabs);
        for (final DepositAccountInterestRateChartSlabs slab : this.slabs) {
            slab.updateChartReference(chart);
        }
        return chart;
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
