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

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDate;
import org.apache.fineract.infrastructure.core.domain.AbstractAuditableWithUTCDateTimeCustom;
import org.apache.fineract.portfolio.savings.domain.SavingsAccount;
import org.apache.fineract.portfolio.savings.domain.SavingsAccountTransaction;

/**
 * Marks how much of a withdrawal transaction on a Dynamic Deposit account represents previously-posted, not-yet-
 * withdrawn interest (implementation plan Section 10 steps 11-12) - a read/reporting side table only. The withdrawal
 * itself already correctly reduces the account balance via the normal transaction ledger; this table exists purely so
 * the interest-summary API (Section 9's {@code interestWithdrawn} field) can report the figure without re-deriving it
 * on every read. No {@code is_reversed} column of its own - both linked transactions already own reversal state.
 */
@Entity
@Table(name = "m_deposit_account_interest_withdrawal")
public class DepositAccountInterestWithdrawal extends AbstractAuditableWithUTCDateTimeCustom<Long> {

    @ManyToOne(optional = false)
    @JoinColumn(name = "savings_account_id", nullable = false)
    private SavingsAccount account;

    @ManyToOne(optional = false)
    @JoinColumn(name = "interest_posting_transaction_id", nullable = false)
    private SavingsAccountTransaction interestPostingTransaction;

    @ManyToOne(optional = false)
    @JoinColumn(name = "withdrawal_transaction_id", nullable = false)
    private SavingsAccountTransaction withdrawalTransaction;

    @Column(name = "withdrawn_interest_amount", nullable = false, scale = 6, precision = 19)
    private BigDecimal withdrawnInterestAmount;

    @Column(name = "transaction_date", nullable = false)
    private LocalDate transactionDate;

    protected DepositAccountInterestWithdrawal() {
        //
    }

    private DepositAccountInterestWithdrawal(final SavingsAccount account, final SavingsAccountTransaction interestPostingTransaction,
            final SavingsAccountTransaction withdrawalTransaction, final BigDecimal withdrawnInterestAmount,
            final LocalDate transactionDate) {
        this.account = account;
        this.interestPostingTransaction = interestPostingTransaction;
        this.withdrawalTransaction = withdrawalTransaction;
        this.withdrawnInterestAmount = withdrawnInterestAmount;
        this.transactionDate = transactionDate;
    }

    public static DepositAccountInterestWithdrawal createNew(final SavingsAccount account,
            final SavingsAccountTransaction interestPostingTransaction, final SavingsAccountTransaction withdrawalTransaction,
            final BigDecimal withdrawnInterestAmount, final LocalDate transactionDate) {
        return new DepositAccountInterestWithdrawal(account, interestPostingTransaction, withdrawalTransaction, withdrawnInterestAmount,
                transactionDate);
    }

    public SavingsAccountTransaction interestPostingTransaction() {
        return this.interestPostingTransaction;
    }

    public SavingsAccountTransaction withdrawalTransaction() {
        return this.withdrawalTransaction;
    }

    public BigDecimal withdrawnInterestAmount() {
        return this.withdrawnInterestAmount;
    }

    public LocalDate transactionDate() {
        return this.transactionDate;
    }
}
