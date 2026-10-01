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
package com.advancly.fineract.portfolio.savings.data;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * The rate a Dynamic Deposit account would resolve to if the simulated top-up/withdrawal were real, as projected by the
 * interest-calculation preview. Only present when a simulation was requested on a Dynamic Deposit account.
 * {@code rateChanged} is {@code false} (and the rates equal) when the account's rate is fixed
 * ({@code dynamic_rate_enabled = false}) or the new amount stays in the same slab.
 */
public class SimulatedRateData implements Serializable {

    private final BigDecimal annualInterestRate;
    private final BigDecimal baseAnnualInterestRate;
    private final String source;
    private final Long interestRateChartId;
    private final Long interestRateSlabId;
    private final LocalDate effectiveFrom;
    private final BigDecimal investedAmountAfter;
    private final BigDecimal previousAnnualInterestRate;
    private final boolean rateChanged;

    public SimulatedRateData(final BigDecimal annualInterestRate, final BigDecimal baseAnnualInterestRate, final String source,
            final Long interestRateChartId, final Long interestRateSlabId, final LocalDate effectiveFrom,
            final BigDecimal investedAmountAfter, final BigDecimal previousAnnualInterestRate, final boolean rateChanged) {
        this.annualInterestRate = annualInterestRate;
        this.baseAnnualInterestRate = baseAnnualInterestRate;
        this.source = source;
        this.interestRateChartId = interestRateChartId;
        this.interestRateSlabId = interestRateSlabId;
        this.effectiveFrom = effectiveFrom;
        this.investedAmountAfter = investedAmountAfter;
        this.previousAnnualInterestRate = previousAnnualInterestRate;
        this.rateChanged = rateChanged;
    }

    public BigDecimal annualInterestRate() {
        return this.annualInterestRate;
    }

    public BigDecimal baseAnnualInterestRate() {
        return this.baseAnnualInterestRate;
    }

    public String source() {
        return this.source;
    }

    public Long interestRateChartId() {
        return this.interestRateChartId;
    }

    public Long interestRateSlabId() {
        return this.interestRateSlabId;
    }

    public LocalDate effectiveFrom() {
        return this.effectiveFrom;
    }

    public BigDecimal investedAmountAfter() {
        return this.investedAmountAfter;
    }

    public BigDecimal previousAnnualInterestRate() {
        return this.previousAnnualInterestRate;
    }

    public boolean rateChanged() {
        return this.rateChanged;
    }
}
