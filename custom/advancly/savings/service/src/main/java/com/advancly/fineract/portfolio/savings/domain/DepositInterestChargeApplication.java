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
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDate;
import org.apache.fineract.infrastructure.core.domain.AbstractAuditableWithUTCDateTimeCustom;
import org.apache.fineract.portfolio.charge.domain.Charge;
import org.apache.fineract.portfolio.savings.domain.SavingsAccount;
import org.apache.fineract.portfolio.savings.domain.SavingsAccountTransaction;

@Entity
@Table(name = "m_deposit_interest_charge_application")
public class DepositInterestChargeApplication extends AbstractAuditableWithUTCDateTimeCustom<Long> {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "savings_account_id", nullable = false)
    private SavingsAccount account;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "charge_id", nullable = false)
    private Charge charge;

    @ManyToOne(fetch = FetchType.LAZY, optional = false, cascade = CascadeType.PERSIST)
    @JoinColumn(name = "withdrawal_transaction_id", nullable = false)
    private SavingsAccountTransaction withdrawalTransaction;

    @ManyToOne(fetch = FetchType.LAZY, optional = false, cascade = CascadeType.PERSIST)
    @JoinColumn(name = "interest_charge_transaction_id", nullable = false)
    private SavingsAccountTransaction interestChargeTransaction;

    @Column(name = "transaction_date", nullable = false)
    private LocalDate transactionDate;

    @Column(name = "selected_from_date", nullable = false)
    private LocalDate selectedFromDate;

    @Column(name = "selected_to_date", nullable = false)
    private LocalDate selectedToDate;

    @Column(name = "interest_basis_mode_enum", nullable = false)
    private Integer interestBasisMode;

    @Column(name = "custom_period_reapply_policy_enum")
    private Integer customPeriodReapplyPolicy;

    @Column(name = "percentage", nullable = false, scale = 6, precision = 19)
    private BigDecimal percentage;

    @Column(name = "original_basis_amount", nullable = false, scale = 6, precision = 19)
    private BigDecimal originalBasisAmount;

    @Column(name = "previously_consumed_amount", nullable = false, scale = 6, precision = 19)
    private BigDecimal previouslyConsumedAmount;

    @Column(name = "applied_amount", nullable = false, scale = 6, precision = 19)
    private BigDecimal appliedAmount;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "correction_of_application_id")
    private DepositInterestChargeApplication correctionOfApplication;

    protected DepositInterestChargeApplication() {
        //
    }

    private DepositInterestChargeApplication(final SavingsAccount account, final Charge charge,
            final SavingsAccountTransaction withdrawalTransaction, final SavingsAccountTransaction interestChargeTransaction,
            final LocalDate transactionDate, final LocalDate selectedFromDate, final LocalDate selectedToDate,
            final InterestBasisMode interestBasisMode, final CustomPeriodReapplyPolicy customPeriodReapplyPolicy,
            final BigDecimal percentage, final BigDecimal originalBasisAmount, final BigDecimal previouslyConsumedAmount,
            final BigDecimal appliedAmount, final DepositInterestChargeApplication correctionOfApplication) {
        this.account = account;
        this.charge = charge;
        this.withdrawalTransaction = withdrawalTransaction;
        this.interestChargeTransaction = interestChargeTransaction;
        this.transactionDate = transactionDate;
        this.selectedFromDate = selectedFromDate;
        this.selectedToDate = selectedToDate;
        this.interestBasisMode = interestBasisMode.getValue();
        this.customPeriodReapplyPolicy = customPeriodReapplyPolicy == null ? null : customPeriodReapplyPolicy.getValue();
        this.percentage = percentage;
        this.originalBasisAmount = originalBasisAmount;
        this.previouslyConsumedAmount = previouslyConsumedAmount;
        this.appliedAmount = appliedAmount;
        this.correctionOfApplication = correctionOfApplication;
    }

    public static DepositInterestChargeApplication createNew(final SavingsAccount account, final Charge charge,
            final SavingsAccountTransaction withdrawalTransaction, final SavingsAccountTransaction interestChargeTransaction,
            final LocalDate transactionDate, final LocalDate selectedFromDate, final LocalDate selectedToDate,
            final InterestBasisMode interestBasisMode, final CustomPeriodReapplyPolicy customPeriodReapplyPolicy,
            final BigDecimal percentage, final BigDecimal originalBasisAmount, final BigDecimal previouslyConsumedAmount,
            final BigDecimal appliedAmount) {
        return new DepositInterestChargeApplication(account, charge, withdrawalTransaction, interestChargeTransaction, transactionDate,
                selectedFromDate, selectedToDate, interestBasisMode, customPeriodReapplyPolicy, percentage, originalBasisAmount,
                previouslyConsumedAmount, appliedAmount, null);
    }

    public DepositInterestChargeApplication correctionOf(final DepositInterestChargeApplication originalApplication) {
        this.correctionOfApplication = originalApplication;
        return this;
    }

    public SavingsAccount account() {
        return this.account;
    }

    public Charge charge() {
        return this.charge;
    }

    public SavingsAccountTransaction withdrawalTransaction() {
        return this.withdrawalTransaction;
    }

    public SavingsAccountTransaction interestChargeTransaction() {
        return this.interestChargeTransaction;
    }

    public LocalDate transactionDate() {
        return this.transactionDate;
    }

    public LocalDate selectedFromDate() {
        return this.selectedFromDate;
    }

    public LocalDate selectedToDate() {
        return this.selectedToDate;
    }

    public InterestBasisMode interestBasisMode() {
        return InterestBasisMode.fromInt(this.interestBasisMode);
    }

    public CustomPeriodReapplyPolicy customPeriodReapplyPolicy() {
        return CustomPeriodReapplyPolicy.fromInt(this.customPeriodReapplyPolicy);
    }

    public BigDecimal percentage() {
        return this.percentage;
    }

    public BigDecimal originalBasisAmount() {
        return this.originalBasisAmount;
    }

    public BigDecimal previouslyConsumedAmount() {
        return this.previouslyConsumedAmount;
    }

    public BigDecimal appliedAmount() {
        return this.appliedAmount;
    }
}
