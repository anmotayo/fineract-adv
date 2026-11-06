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
 * Response for {@code GET /interestcalculation/{savingsAccountId}} - a read-only interest-calculation preview that
 * works across plain Savings, Fixed Deposit, Recurring Deposit and Dynamic Deposit accounts. Runs the same
 * {@code calculateInterestUsing(...)} engine each account type already uses for its own interest posting, but never
 * persists anything - the loaded account is detached before (and remains detached throughout) the calculation, so this
 * is safe to call as often as needed, including with a simulated top-up or withdrawal.
 *
 * {@code maturityDate}/{@code maturityAmount}/{@code asAtMaturitySummary} are {@code null} for plain Savings accounts,
 * which have no maturity concept. {@code asAtMaturitySummary} and {@code maturityAmount} project forward from the
 * amount actually invested to date (including any simulated top-up/withdrawal) - not from the account's originally
 * approved schedule - so they correctly reflect principal shifts from real top-ups/withdrawals over the account's life
 * (Dynamic Deposit and Recurring Deposit can both add/remove principal after opening).
 *
 * {@code asAtTodaySummary} and {@code asAtMaturitySummary} carry the account's whole summary as it stands after each of
 * those two calculation runs; the latter is {@code null} when there is no maturity date.
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

    // the account's current nominal annual rate (%), populated for every account type, not just Dynamic Deposit
    private final BigDecimal nominalAnnualInterestRate;

    private final BigDecimal maturityAmount;

    // the account's whole summary after each calculation run; asAtMaturitySummary is null for accounts with no maturity
    // date
    private final InterestCalculationSummaryData asAtTodaySummary;
    private final InterestCalculationSummaryData asAtMaturitySummary;

    private final Collection<InterestCalculationTransactionData> transactions;
    private final Collection<PostingPeriodData> postingPeriods;

    // echoes back what the caller asked to simulate, so the response is unambiguous when both are null
    private final BigDecimal simulatedTopUpAmount;
    private final BigDecimal simulatedWithdrawalAmount;

    // Dynamic Deposit only, and only when a simulation was requested: the rate the simulated net amount resolves to
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
