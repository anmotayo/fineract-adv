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
import org.apache.fineract.portfolio.savings.data.SavingsAccountTransactionEnumData;

/**
 * Lightweight transaction row for the interest-calculation preview response - deliberately not the full
 * {@code SavingsAccountTransactionData} (charge-paid-by breakdowns etc. aren't needed here), just enough to show what
 * happened and when. A simulated top-up/withdrawal (see {@code InterestCalculationReadPlatformServiceImpl}) is included
 * with a {@code null} id so callers can tell it apart from a real, persisted transaction.
 *
 * The {@code deposit}/{@code withdrawal}/etc. flags mirror what {@code SavingsAccountTransactionData} exposes nested
 * under {@code transactionType} for {@code associations=all} - flattened here so callers don't have to dig into the
 * nested enum object just to branch on transaction type.
 */
public class InterestCalculationTransactionData implements Serializable {

    private final Long id;
    private final SavingsAccountTransactionEnumData transactionType;
    private final LocalDate date;
    private final BigDecimal amount;
    private final BigDecimal runningBalance;
    private final boolean reversed;
    private final boolean simulated;
    private final boolean deposit;
    private final boolean withdrawal;
    private final boolean interestPosting;
    private final boolean chargeTransaction;
    private final boolean withHoldTax;
    private final boolean dividendPayout;
    private final boolean accrual;
    private final boolean interestCharge;

    public InterestCalculationTransactionData(final Long id, final SavingsAccountTransactionEnumData transactionType, final LocalDate date,
            final BigDecimal amount, final BigDecimal runningBalance, final boolean reversed, final boolean simulated) {
        this.id = id;
        this.transactionType = transactionType;
        this.date = date;
        this.amount = amount;
        this.runningBalance = runningBalance;
        this.reversed = reversed;
        this.simulated = simulated;
        this.deposit = transactionType.isDeposit();
        this.withdrawal = transactionType.isWithdrawal();
        this.interestPosting = transactionType.isInterestPosting() || transactionType.isOverDraftInterestPosting();
        this.chargeTransaction = transactionType.isChargeTransaction();
        this.withHoldTax = transactionType.isWithholdTax();
        this.dividendPayout = transactionType.isDividendPayout();
        this.accrual = transactionType.isAccrual();
        this.interestCharge = transactionType.isInterestCharge();
    }

    public Long id() {
        return this.id;
    }

    public SavingsAccountTransactionEnumData transactionType() {
        return this.transactionType;
    }

    public LocalDate date() {
        return this.date;
    }

    public BigDecimal amount() {
        return this.amount;
    }

    public BigDecimal runningBalance() {
        return this.runningBalance;
    }

    public boolean isReversed() {
        return this.reversed;
    }

    public boolean isSimulated() {
        return this.simulated;
    }

    public boolean isDeposit() {
        return this.deposit;
    }

    public boolean isWithdrawal() {
        return this.withdrawal;
    }

    public boolean isInterestPosting() {
        return this.interestPosting;
    }

    public boolean isChargeTransaction() {
        return this.chargeTransaction;
    }

    public boolean isWithHoldTax() {
        return this.withHoldTax;
    }

    public boolean isDividendPayout() {
        return this.dividendPayout;
    }

    public boolean isAccrual() {
        return this.accrual;
    }

    public boolean isInterestCharge() {
        return this.interestCharge;
    }
}
