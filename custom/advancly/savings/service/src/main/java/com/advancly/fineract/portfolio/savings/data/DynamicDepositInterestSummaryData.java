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

import com.advancly.fineract.portfolio.savings.domain.DynamicDepositRateSource;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Read-only interest summary for a Dynamic Deposit account. {@code interestWithdrawn} and
 * {@code interestTransferredToSavings} are always zero for now.
 */
public class DynamicDepositInterestSummaryData {

    private final BigDecimal grossInterestEarnedAsAtToday;
    private final BigDecimal interestPosted;
    private final BigDecimal totalInterestForPeriod;
    private final BigDecimal interestWithdrawn;
    private final BigDecimal withholdingTax;
    private final BigDecimal interestCharges;
    private final BigDecimal netInterest;
    private final BigDecimal interestTransferredToSavings;
    private final List<RateIntervalData> effectiveRateIntervals;

    public DynamicDepositInterestSummaryData(final BigDecimal grossInterestEarnedAsAtToday, final BigDecimal interestPosted,
            final BigDecimal totalInterestForPeriod, final BigDecimal interestWithdrawn, final BigDecimal withholdingTax,
            final BigDecimal interestCharges, final BigDecimal netInterest, final BigDecimal interestTransferredToSavings,
            final List<RateIntervalData> effectiveRateIntervals) {
        this.grossInterestEarnedAsAtToday = grossInterestEarnedAsAtToday;
        this.interestPosted = interestPosted;
        this.totalInterestForPeriod = totalInterestForPeriod;
        this.interestWithdrawn = interestWithdrawn;
        this.withholdingTax = withholdingTax;
        this.interestCharges = interestCharges;
        this.netInterest = netInterest;
        this.interestTransferredToSavings = interestTransferredToSavings;
        this.effectiveRateIntervals = effectiveRateIntervals;
    }

    public BigDecimal grossInterestEarnedAsAtToday() {
        return this.grossInterestEarnedAsAtToday;
    }

    public BigDecimal interestPosted() {
        return this.interestPosted;
    }

    public BigDecimal totalInterestForPeriod() {
        return this.totalInterestForPeriod;
    }

    public BigDecimal interestWithdrawn() {
        return this.interestWithdrawn;
    }

    public BigDecimal withholdingTax() {
        return this.withholdingTax;
    }

    public BigDecimal interestCharges() {
        return this.interestCharges;
    }

    public BigDecimal netInterest() {
        return this.netInterest;
    }

    public BigDecimal interestTransferredToSavings() {
        return this.interestTransferredToSavings;
    }

    public List<RateIntervalData> effectiveRateIntervals() {
        return this.effectiveRateIntervals;
    }

    public record RateIntervalData(LocalDate transactionDate, BigDecimal investedAmountAfterTransaction,
            BigDecimal resolvedAnnualInterestRate, DynamicDepositRateSource rateSource) {
    }
}
