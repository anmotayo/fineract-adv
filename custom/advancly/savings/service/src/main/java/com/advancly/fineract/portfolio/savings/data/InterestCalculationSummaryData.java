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
import org.apache.fineract.portfolio.savings.domain.SavingsAccountSummary;

/**
 * A snapshot of the account's summary taken right after one {@code calculateInterestUsing(...)} run of the
 * interest-calculation preview - either the run bounded by today ({@code asAtTodaySummary}) or the run projected
 * through maturity ({@code asAtMaturitySummary}). The engine rewrites the one in-memory summary on every run, so each
 * run's figures must be copied out before the next run overwrites them.
 */
public class InterestCalculationSummaryData implements Serializable {

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

    public InterestCalculationSummaryData(final BigDecimal totalDeposits, final BigDecimal totalWithdrawals,
            final BigDecimal totalWithdrawalFees, final BigDecimal totalAnnualFees, final BigDecimal totalInterestEarned,
            final BigDecimal totalInterestPosted, final BigDecimal accountBalance, final BigDecimal totalFeeCharge,
            final BigDecimal totalPenaltyCharge, final BigDecimal totalOverdraftInterestDerived, final BigDecimal totalWithholdTax,
            final LocalDate interestPostedTillDate) {
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
    }

    public static InterestCalculationSummaryData from(final SavingsAccountSummary summary) {
        return new InterestCalculationSummaryData(summary.getTotalDeposits(), summary.getTotalWithdrawals(),
                summary.getTotalWithdrawalFees(), summary.getTotalAnnualFees(), summary.getTotalInterestEarned(),
                summary.getTotalInterestPosted(), summary.getAccountBalance(), summary.getTotalFeeCharge(), summary.getTotalPenaltyCharge(),
                summary.getTotalOverdraftInterestDerived(), summary.getTotalWithholdTax(), summary.getInterestPostedTillDate());
    }

    public BigDecimal totalDeposits() {
        return this.totalDeposits;
    }

    public BigDecimal totalWithdrawals() {
        return this.totalWithdrawals;
    }

    public BigDecimal totalWithdrawalFees() {
        return this.totalWithdrawalFees;
    }

    public BigDecimal totalAnnualFees() {
        return this.totalAnnualFees;
    }

    public BigDecimal totalInterestEarned() {
        return this.totalInterestEarned;
    }

    public BigDecimal totalInterestPosted() {
        return this.totalInterestPosted;
    }

    public BigDecimal accountBalance() {
        return this.accountBalance;
    }

    public BigDecimal totalFeeCharge() {
        return this.totalFeeCharge;
    }

    public BigDecimal totalPenaltyCharge() {
        return this.totalPenaltyCharge;
    }

    public BigDecimal totalOverdraftInterestDerived() {
        return this.totalOverdraftInterestDerived;
    }

    public BigDecimal totalWithholdTax() {
        return this.totalWithholdTax;
    }

    public LocalDate interestPostedTillDate() {
        return this.interestPostedTillDate;
    }
}
