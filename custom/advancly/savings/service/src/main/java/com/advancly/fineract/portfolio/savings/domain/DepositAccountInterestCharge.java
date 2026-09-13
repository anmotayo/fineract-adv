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
package com.advancly.fineract.portfolio.savings.domain;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDate;
import org.apache.fineract.infrastructure.core.domain.AbstractAuditableWithUTCDateTimeCustom;
import org.apache.fineract.portfolio.charge.domain.Charge;
import org.apache.fineract.portfolio.savings.domain.SavingsAccount;
import org.apache.fineract.portfolio.savings.domain.SavingsAccountCharge;
import org.apache.fineract.portfolio.savings.domain.SavingsAccountTransaction;

/**
 * One early-withdrawal interest-based charge (implementation plan Section 11). Created as a PENDING row the moment an
 * early withdrawal occurs - carrying the interest basis it was computed from, the percentage applied and the resulting
 * amount - and linked to the interest posting transaction and the single interest-based charge transaction when the
 * next interest posting runs (Section 10 steps 7-8).
 *
 * Per implementation plan Section 8 rule 5, this table has NO {@code is_reversed} column of its own: every row is keyed
 * to a withdrawal transaction that already owns reversal state, and once applied, to a charge transaction that does
 * too. Duplicating the flag would risk drifting out of sync whenever a reversal path forgets to update it.
 *
 * The source of truth for posting, reversal and accounting is this table plus the linked transactions - the derived
 * columns on {@code m_savings_account} are read-side conveniences only (Section 5).
 */
@Entity
@Table(name = "m_deposit_account_interest_charge")
public class DepositAccountInterestCharge extends AbstractAuditableWithUTCDateTimeCustom<Long> {

    @ManyToOne(optional = false)
    @JoinColumn(name = "savings_account_id", nullable = false)
    private SavingsAccount account;

    // cascade = PERSIST for the same reason as DepositAccountInterestWithdrawal: the withdrawal transaction that
    // triggers this row may not have been flushed (and so may have no generated id) at the point the row is built.
    // Cascading persist is safe and idempotent even when the transaction is also reachable through the account's own
    // transaction collection, since it is the same managed Java instance within the current persistence context.
    @ManyToOne(optional = false, cascade = CascadeType.PERSIST)
    @JoinColumn(name = "withdrawal_transaction_id", nullable = false)
    private SavingsAccountTransaction withdrawalTransaction;

    @ManyToOne(optional = false)
    @JoinColumn(name = "savings_account_charge_id", nullable = false)
    private SavingsAccountCharge savingsAccountCharge;

    @ManyToOne(optional = false)
    @JoinColumn(name = "charge_id", nullable = false)
    private Charge charge;

    /** Null until the interest posting that consumes this row runs. */
    @ManyToOne(cascade = CascadeType.PERSIST)
    @JoinColumn(name = "interest_posting_transaction_id")
    private SavingsAccountTransaction interestPostingTransaction;

    /** Null until the interest posting that consumes this row runs. */
    @ManyToOne(cascade = CascadeType.PERSIST)
    @JoinColumn(name = "interest_charge_transaction_id")
    private SavingsAccountTransaction interestChargeTransaction;

    @Column(name = "interest_period_start_date", nullable = false)
    private LocalDate interestPeriodStartDate;

    @Column(name = "interest_period_end_date", nullable = false)
    private LocalDate interestPeriodEndDate;

    @Column(name = "interest_amount_basis", nullable = false, scale = 6, precision = 19)
    private BigDecimal interestAmountBasis;

    @Column(name = "charge_percentage", nullable = false, scale = 6, precision = 19)
    private BigDecimal chargePercentage;

    @Column(name = "charge_amount", nullable = false, scale = 6, precision = 19)
    private BigDecimal chargeAmount;

    protected DepositAccountInterestCharge() {
        //
    }

    private DepositAccountInterestCharge(final SavingsAccount account, final SavingsAccountTransaction withdrawalTransaction,
            final SavingsAccountCharge savingsAccountCharge, final Charge charge, final LocalDate interestPeriodStartDate,
            final LocalDate interestPeriodEndDate, final BigDecimal interestAmountBasis, final BigDecimal chargePercentage,
            final BigDecimal chargeAmount) {
        this.account = account;
        this.withdrawalTransaction = withdrawalTransaction;
        this.savingsAccountCharge = savingsAccountCharge;
        this.charge = charge;
        this.interestPeriodStartDate = interestPeriodStartDate;
        this.interestPeriodEndDate = interestPeriodEndDate;
        this.interestAmountBasis = interestAmountBasis;
        this.chargePercentage = chargePercentage;
        this.chargeAmount = chargeAmount;
    }

    public static DepositAccountInterestCharge createNew(final SavingsAccount account,
            final SavingsAccountTransaction withdrawalTransaction, final SavingsAccountCharge savingsAccountCharge, final Charge charge,
            final LocalDate interestPeriodStartDate, final LocalDate interestPeriodEndDate, final BigDecimal interestAmountBasis,
            final BigDecimal chargePercentage, final BigDecimal chargeAmount) {
        return new DepositAccountInterestCharge(account, withdrawalTransaction, savingsAccountCharge, charge, interestPeriodStartDate,
                interestPeriodEndDate, interestAmountBasis, chargePercentage, chargeAmount);
    }

    /**
     * Implementation plan Section 10 step 8: link this row to the interest posting transaction and to the single
     * interest-based charge transaction written for the period.
     */
    public void linkToPosting(final SavingsAccountTransaction interestPostingTransaction,
            final SavingsAccountTransaction interestChargeTransaction) {
        this.interestPostingTransaction = interestPostingTransaction;
        this.interestChargeTransaction = interestChargeTransaction;
    }

    /**
     * Records what interest posting ACTUALLY applied for this row, replacing the provisional basis and amount the
     * early-withdrawal path snapshotted (implementation plan Section 10 steps 7-8). The amount is recomputed at posting
     * time from {@link #chargePercentage()} against the period's real gross interest and then pro-rated if the
     * period-level cap bit, so without this overwrite {@code sumPostedChargeAmount(...)} - which backs
     * {@code interest_based_charge_posted_derived} and the API's {@code interestBasedCharges} - would report figures
     * that never matched the money that moved.
     */
    public void applyAtPosting(final BigDecimal actualInterestAmountBasis, final BigDecimal actualChargeAmount,
            final SavingsAccountTransaction interestPostingTransaction, final SavingsAccountTransaction interestChargeTransaction) {
        this.interestAmountBasis = actualInterestAmountBasis;
        this.chargeAmount = actualChargeAmount;
        linkToPosting(interestPostingTransaction, interestChargeTransaction);
    }

    /** Not yet consumed by an interest posting. */
    public boolean isPending() {
        return this.interestChargeTransaction == null;
    }

    /**
     * The withdrawal that created this row has since been reversed, so the charge must not be applied (and, if it
     * already was, must not be counted again). Keyed off the transaction's own reversal state - this table carries none
     * of its own.
     */
    public boolean isVoidedByReversal() {
        return this.withdrawalTransaction.isReversed();
    }

    public SavingsAccountTransaction withdrawalTransaction() {
        return this.withdrawalTransaction;
    }

    public SavingsAccountCharge savingsAccountCharge() {
        return this.savingsAccountCharge;
    }

    public Charge charge() {
        return this.charge;
    }

    public SavingsAccountTransaction interestPostingTransaction() {
        return this.interestPostingTransaction;
    }

    public SavingsAccountTransaction interestChargeTransaction() {
        return this.interestChargeTransaction;
    }

    public LocalDate interestPeriodStartDate() {
        return this.interestPeriodStartDate;
    }

    public LocalDate interestPeriodEndDate() {
        return this.interestPeriodEndDate;
    }

    public BigDecimal interestAmountBasis() {
        return this.interestAmountBasis;
    }

    public BigDecimal chargePercentage() {
        return this.chargePercentage;
    }

    public BigDecimal chargeAmount() {
        return this.chargeAmount;
    }
}
