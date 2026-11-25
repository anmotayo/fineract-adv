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
import java.util.Collection;
import org.apache.fineract.organisation.monetary.data.CurrencyData;
import org.apache.fineract.portfolio.savings.data.SavingsAccountStatusEnumData;

/**
 * Response for {@code GET /interestcalculation/{savingsAccountId}}: a never-persisted interest preview for Savings, FD,
 * RD and DD accounts. Maturity fields are {@code null} for plain Savings and project from the amount actually invested
 * to date (including any simulated top-up/withdrawal).
 */
public class InterestCalculationData implements Serializable {

    private final Long accountId;
    private final String accountNo;
    private final String externalId;
    private final Long clientId;
    private final Long groupId;
    private final Long productId;
    private final SavingsAccountStatusEnumData status;
    private final CurrencyData currency;
    private final LocalDate maturityDate;

    private final BigDecimal nominalAnnualInterestRate;

    private final BigDecimal maturityAmount;

    private final InterestCalculationSummaryData asAtTodaySummary;
    private final InterestCalculationSummaryData asAtMaturitySummary;

    private final Collection<InterestCalculationTransactionData> transactions;
    private final Collection<PostingPeriodData> postingPeriods;

    private final BigDecimal simulatedTopUpAmount;
    private final BigDecimal simulatedWithdrawalAmount;

    // Dynamic Deposit only, when a simulation was requested
    private final SimulatedRateData simulatedRate;

    public InterestCalculationData(final Long accountId, final String accountNo, final String externalId, final Long clientId,
            final Long groupId, final Long productId, final SavingsAccountStatusEnumData status, final CurrencyData currency,
            final LocalDate maturityDate, final BigDecimal nominalAnnualInterestRate, final BigDecimal maturityAmount,
            final InterestCalculationSummaryData asAtTodaySummary, final InterestCalculationSummaryData asAtMaturitySummary,
            final Collection<InterestCalculationTransactionData> transactions, final Collection<PostingPeriodData> postingPeriods,
            final BigDecimal simulatedTopUpAmount, final BigDecimal simulatedWithdrawalAmount, final SimulatedRateData simulatedRate) {
        this.accountId = accountId;
        this.accountNo = accountNo;
        this.externalId = externalId;
        this.clientId = clientId;
        this.groupId = groupId;
        this.productId = productId;
        this.status = status;
        this.currency = currency;
        this.maturityDate = maturityDate;
        this.nominalAnnualInterestRate = nominalAnnualInterestRate;
        this.maturityAmount = maturityAmount;
        this.asAtTodaySummary = asAtTodaySummary;
        this.asAtMaturitySummary = asAtMaturitySummary;
        this.transactions = transactions;
        this.postingPeriods = postingPeriods;
        this.simulatedTopUpAmount = simulatedTopUpAmount;
        this.simulatedWithdrawalAmount = simulatedWithdrawalAmount;
        this.simulatedRate = simulatedRate;
    }

    public Long accountId() {
        return this.accountId;
    }

    public SavingsAccountStatusEnumData status() {
        return this.status;
    }

    public LocalDate maturityDate() {
        return this.maturityDate;
    }

    public BigDecimal nominalAnnualInterestRate() {
        return this.nominalAnnualInterestRate;
    }

    public BigDecimal maturityAmount() {
        return this.maturityAmount;
    }

    public InterestCalculationSummaryData asAtTodaySummary() {
        return this.asAtTodaySummary;
    }

    public InterestCalculationSummaryData asAtMaturitySummary() {
        return this.asAtMaturitySummary;
    }

    public Collection<InterestCalculationTransactionData> transactions() {
        return this.transactions;
    }

    public Collection<PostingPeriodData> postingPeriods() {
        return this.postingPeriods;
    }

    public SimulatedRateData simulatedRate() {
        return this.simulatedRate;
    }
}
