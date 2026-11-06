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
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDate;
import org.apache.fineract.infrastructure.core.domain.AbstractAuditableWithUTCDateTimeCustom;
import org.apache.fineract.portfolio.savings.domain.SavingsAccount;
import org.apache.fineract.portfolio.savings.domain.SavingsAccountTransaction;

/**
 * Captures the interest rate resolved and used for the interval starting at one principal-changing transaction, for a
 * Dynamic Deposit account (deposit_type_enum = 500) - see implementation plan Section 4.
 *
 * One row is written per principal-changing event (initial funding/activation, top-up, withdrawal, transfer-in,
 * transfer-out, and the replacement transaction created by an adjustment). A plain reversal of an existing transaction
 * does not need its own row updated or deleted - per plan Section 8 step 5, rows are pure link/marker rows keyed to a
 * transaction that already owns reversal state (mirroring {@code m_loan_transaction_relation}); a reversed
 * transaction's row is simply excluded later by the interest engine joining back to
 * {@code m_savings_account_transaction.is_reversed}. The reversal itself creates a *new* transaction (a correcting
 * entry) which gets its own row (event type {@link DynamicDepositRateHistoryEventType#REVERSAL}), since it changes the
 * invested amount going forward from that point.
 */
@Entity
@Table(name = "m_deposit_account_dynamic_rate_history")
public class DepositAccountDynamicRateHistory extends AbstractAuditableWithUTCDateTimeCustom<Long> {

    @ManyToOne(optional = false)
    @JoinColumn(name = "savings_account_id", nullable = false)
    private SavingsAccount account;

    // cascade = PERSIST: the transaction that triggered this row may not yet be flushed/have a generated id at the
    // point this row is created (e.g. the plain, non-backdated SavingsAccount.deposit()/withdraw() path only appends
    // to an in-memory list - persistence is driven by the caller afterward). Cascading persist here is safe and
    // idempotent even when the same transaction is also reachable via the account's own transaction collection,
    // since it is the same managed Java instance within the current persistence context.
    @ManyToOne(optional = false, cascade = CascadeType.PERSIST)
    @JoinColumn(name = "savings_account_transaction_id", nullable = false)
    private SavingsAccountTransaction transaction;

    @Column(name = "transaction_date", nullable = false)
    private LocalDate transactionDate;

    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", nullable = false, length = 32)
    private DynamicDepositRateHistoryEventType eventType;

    @Column(name = "invested_amount_after_transaction", nullable = false, scale = 6, precision = 19)
    private BigDecimal investedAmountAfterTransaction;

    @Column(name = "deposit_period")
    private Integer depositPeriod;

    @Column(name = "deposit_period_frequency_enum")
    private Integer depositPeriodFrequencyType;

    @Column(name = "interest_rate_chart_id")
    private Long interestRateChartId;

    @Column(name = "interest_rate_slab_id")
    private Long interestRateSlabId;

    @Column(name = "base_annual_interest_rate", scale = 6, precision = 19)
    private BigDecimal baseAnnualInterestRate;

    @Column(name = "resolved_annual_interest_rate", nullable = false, scale = 6, precision = 19)
    private BigDecimal resolvedAnnualInterestRate;

    @Enumerated(EnumType.STRING)
    @Column(name = "rate_source", nullable = false, length = 32)
    private DynamicDepositRateSource rateSource;

    protected DepositAccountDynamicRateHistory() {
        //
    }

    private DepositAccountDynamicRateHistory(final SavingsAccount account, final SavingsAccountTransaction transaction,
            final LocalDate transactionDate, final DynamicDepositRateHistoryEventType eventType,
            final BigDecimal investedAmountAfterTransaction, final Integer depositPeriod, final Integer depositPeriodFrequencyType,
            final Long interestRateChartId, final Long interestRateSlabId, final BigDecimal baseAnnualInterestRate,
            final BigDecimal resolvedAnnualInterestRate, final DynamicDepositRateSource rateSource) {
        this.account = account;
        this.transaction = transaction;
        this.transactionDate = transactionDate;
        this.eventType = eventType;
        this.investedAmountAfterTransaction = investedAmountAfterTransaction;
        this.depositPeriod = depositPeriod;
        this.depositPeriodFrequencyType = depositPeriodFrequencyType;
        this.interestRateChartId = interestRateChartId;
        this.interestRateSlabId = interestRateSlabId;
        this.baseAnnualInterestRate = baseAnnualInterestRate;
        this.resolvedAnnualInterestRate = resolvedAnnualInterestRate;
        this.rateSource = rateSource;
    }

    public static DepositAccountDynamicRateHistory createNew(final SavingsAccount account, final SavingsAccountTransaction transaction,
            final LocalDate transactionDate, final DynamicDepositRateHistoryEventType eventType,
            final BigDecimal investedAmountAfterTransaction, final Integer depositPeriod, final Integer depositPeriodFrequencyType,
            final Long interestRateChartId, final Long interestRateSlabId, final BigDecimal baseAnnualInterestRate,
            final BigDecimal resolvedAnnualInterestRate, final DynamicDepositRateSource rateSource) {
        return new DepositAccountDynamicRateHistory(account, transaction, transactionDate, eventType, investedAmountAfterTransaction,
                depositPeriod, depositPeriodFrequencyType, interestRateChartId, interestRateSlabId, baseAnnualInterestRate,
                resolvedAnnualInterestRate, rateSource);
    }

    public SavingsAccount account() {
        return this.account;
    }

    public SavingsAccountTransaction transaction() {
        return this.transaction;
    }

    public LocalDate transactionDate() {
        return this.transactionDate;
    }

    public DynamicDepositRateHistoryEventType eventType() {
        return this.eventType;
    }

    public BigDecimal investedAmountAfterTransaction() {
        return this.investedAmountAfterTransaction;
    }

    public Long interestRateChartId() {
        return this.interestRateChartId;
    }

    public Long interestRateSlabId() {
        return this.interestRateSlabId;
    }

    public BigDecimal baseAnnualInterestRate() {
        return this.baseAnnualInterestRate;
    }

    public BigDecimal resolvedAnnualInterestRate() {
        return this.resolvedAnnualInterestRate;
    }

    public DynamicDepositRateSource rateSource() {
        return this.rateSource;
    }

    /**
     * Corrects this row's invested amount and resolved rate after a backdated principal-changing transaction (or the
     * undo of one) changes what was true as of this row's transaction date. See
     * {@code DynamicDepositRateHistoryService#reconcileFollowingRows}.
     */
    public void correct(final BigDecimal investedAmountAfterTransaction, final Long interestRateChartId, final Long interestRateSlabId,
            final BigDecimal baseAnnualInterestRate, final BigDecimal resolvedAnnualInterestRate,
            final DynamicDepositRateSource rateSource) {
        this.investedAmountAfterTransaction = investedAmountAfterTransaction;
        this.interestRateChartId = interestRateChartId;
        this.interestRateSlabId = interestRateSlabId;
        this.baseAnnualInterestRate = baseAnnualInterestRate;
        this.resolvedAnnualInterestRate = resolvedAnnualInterestRate;
        this.rateSource = rateSource;
    }
}
