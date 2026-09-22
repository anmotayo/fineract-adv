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
 * {@code maturityDate}/{@code interestAtMaturity}/{@code maturityAmount} are {@code null} for plain Savings accounts,
 * which have no maturity concept. {@code interestAtMaturity} and {@code maturityAmount} project forward from the amount
 * actually invested to date (including any simulated top-up/withdrawal) - not from the account's originally approved
 * schedule - so they correctly reflect principal shifts from real top-ups/withdrawals over the account's life (Dynamic
 * Deposit and Recurring Deposit can both add/remove principal after opening).
 *
 * {@code postedInterestCharges}/{@code totalInterestChargeDerived} report the applied total from the charge-application
 * ledger.
 *
 * {@code forfeitedAmount} is the posted interest-charge total under the business name the cumulative forfeiture API
 * consumers expect.
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

    private final BigDecimal interestAsAtToday;
    private final BigDecimal interestAtMaturity;
    private final BigDecimal maturityAmount;

    private final BigDecimal postedInterestCharges;
    private final BigDecimal totalInterestChargeDerived;
    private final BigDecimal forfeitedAmount;

    // all-time derived totals, straight off the account's own summary
    private final BigDecimal totalDeposits;
    private final BigDecimal totalWithdrawals;
    private final BigDecimal totalWithdrawalFees;
    private final BigDecimal totalAnnualFees;
    private final BigDecimal totalInterestEarned;
    private final BigDecimal totalInterestPosted;
    private final BigDecimal accountBalance;
    private final BigDecimal totalFeeCharge;
    private final BigDecimal totalPenaltyCharge;
    private final BigDecimal totalOverdraftInterestDerived;
    private final BigDecimal totalWithholdTax;
    private final LocalDate interestPostedTillDate;

    private final Collection<InterestCalculationTransactionData> transactions;
    private final Collection<PostingPeriodData> postingPeriods;

    // echoes back what the caller asked to simulate, so the response is unambiguous when both are null
    private final BigDecimal simulatedTopUpAmount;
    private final BigDecimal simulatedWithdrawalAmount;

    public InterestCalculationData(final Long accountId, final String accountNo, final String externalId, final Long clientId,
            final Long groupId, final Long productId, final SavingsAccountStatusEnumData status, final CurrencyData currency,
            final LocalDate maturityDate, final BigDecimal interestAsAtToday, final BigDecimal interestAtMaturity,
            final BigDecimal maturityAmount, final BigDecimal postedInterestCharges, final BigDecimal totalInterestChargeDerived,
            final BigDecimal forfeitedAmount, final BigDecimal totalDeposits, final BigDecimal totalWithdrawals,
            final BigDecimal totalWithdrawalFees, final BigDecimal totalAnnualFees, final BigDecimal totalInterestEarned,
            final BigDecimal totalInterestPosted, final BigDecimal accountBalance, final BigDecimal totalFeeCharge,
            final BigDecimal totalPenaltyCharge, final BigDecimal totalOverdraftInterestDerived, final BigDecimal totalWithholdTax,
            final LocalDate interestPostedTillDate, final Collection<InterestCalculationTransactionData> transactions,
            final Collection<PostingPeriodData> postingPeriods, final BigDecimal simulatedTopUpAmount,
            final BigDecimal simulatedWithdrawalAmount) {
        this.accountId = accountId;
        this.accountNo = accountNo;
        this.externalId = externalId;
        this.clientId = clientId;
        this.groupId = groupId;
        this.productId = productId;
        this.status = status;
        this.currency = currency;
        this.maturityDate = maturityDate;
        this.interestAsAtToday = interestAsAtToday;
        this.interestAtMaturity = interestAtMaturity;
        this.maturityAmount = maturityAmount;
        this.postedInterestCharges = postedInterestCharges;
        this.totalInterestChargeDerived = totalInterestChargeDerived;
        this.forfeitedAmount = forfeitedAmount;
        this.totalDeposits = totalDeposits;
        this.totalWithdrawals = totalWithdrawals;
        this.totalWithdrawalFees = totalWithdrawalFees;
        this.totalAnnualFees = totalAnnualFees;
        this.totalInterestEarned = totalInterestEarned;
        this.totalInterestPosted = totalInterestPosted;
        this.accountBalance = accountBalance;
        this.totalFeeCharge = totalFeeCharge;
        this.totalPenaltyCharge = totalPenaltyCharge;
        this.totalOverdraftInterestDerived = totalOverdraftInterestDerived;
        this.totalWithholdTax = totalWithholdTax;
        this.interestPostedTillDate = interestPostedTillDate;
        this.transactions = transactions;
        this.postingPeriods = postingPeriods;
        this.simulatedTopUpAmount = simulatedTopUpAmount;
        this.simulatedWithdrawalAmount = simulatedWithdrawalAmount;
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

    public BigDecimal interestAsAtToday() {
        return this.interestAsAtToday;
    }

    public BigDecimal interestAtMaturity() {
        return this.interestAtMaturity;
    }

    public BigDecimal maturityAmount() {
        return this.maturityAmount;
    }

    public BigDecimal postedInterestCharges() {
        return this.postedInterestCharges;
    }

    public BigDecimal totalInterestChargeDerived() {
        return this.totalInterestChargeDerived;
    }

    public BigDecimal forfeitedAmount() {
        return this.forfeitedAmount;
    }

    public BigDecimal accountBalance() {
        return this.accountBalance;
    }

    public BigDecimal totalInterestPosted() {
        return this.totalInterestPosted;
    }

    public BigDecimal totalWithholdTax() {
        return this.totalWithholdTax;
    }

    public Collection<InterestCalculationTransactionData> transactions() {
        return this.transactions;
    }

    public Collection<PostingPeriodData> postingPeriods() {
        return this.postingPeriods;
    }
}
