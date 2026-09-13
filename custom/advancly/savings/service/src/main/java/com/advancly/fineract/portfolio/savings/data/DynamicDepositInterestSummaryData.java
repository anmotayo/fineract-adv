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
 * Read-side reporting summary for a Dynamic Deposit account's interest position (implementation plan Section 9). Purely
 * a reporting surface over data already produced by the interest-calculation engine ({@code account.getSummary()}), the
 * FIFO interest-withdrawal marker table ({@code DepositAccountInterestWithdrawal}) and the rate-history table
 * ({@code DepositAccountDynamicRateHistory}) - it adds no new business logic of its own.
 *
 * {@code interestBasedCharges} and {@code interestTransferredToSavings} are always {@link BigDecimal#ZERO} this phase;
 * Phase 4 of the implementation plan is responsible for populating them.
 */
public class DynamicDepositInterestSummaryData {

    private final BigDecimal grossInterestEarnedAsAtToday;
    private final BigDecimal interestPosted;
    private final BigDecimal totalInterestForPeriod;
    private final BigDecimal interestWithdrawn;
    private final BigDecimal withholdingTax;
    private final BigDecimal interestBasedCharges;
    private final BigDecimal netInterest;
    private final BigDecimal interestTransferredToSavings;
    private final List<RateIntervalData> effectiveRateIntervals;

    public DynamicDepositInterestSummaryData(final BigDecimal grossInterestEarnedAsAtToday, final BigDecimal interestPosted,
            final BigDecimal totalInterestForPeriod, final BigDecimal interestWithdrawn, final BigDecimal withholdingTax,
            final BigDecimal interestBasedCharges, final BigDecimal netInterest, final BigDecimal interestTransferredToSavings,
            final List<RateIntervalData> effectiveRateIntervals) {
        this.grossInterestEarnedAsAtToday = grossInterestEarnedAsAtToday;
        this.interestPosted = interestPosted;
        this.totalInterestForPeriod = totalInterestForPeriod;
        this.interestWithdrawn = interestWithdrawn;
        this.withholdingTax = withholdingTax;
        this.interestBasedCharges = interestBasedCharges;
        this.netInterest = netInterest;
        this.interestTransferredToSavings = interestTransferredToSavings;
        this.effectiveRateIntervals = effectiveRateIntervals;
    }

    public record RateIntervalData(LocalDate transactionDate, BigDecimal investedAmountAfterTransaction,
            BigDecimal resolvedAnnualInterestRate, DynamicDepositRateSource rateSource) {
    }
}
