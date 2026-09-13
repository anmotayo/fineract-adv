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
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.util.Objects;

/**
 * Classifier row marking which of a Dynamic Deposit product's existing {@code m_savings_product_charge} entries is the
 * early-withdrawal penalty charge (implementation plan Section 2). Deliberately a pure classifier: it adds no
 * configuration of its own - the penalty percentage is the charge's own {@code amount}, configured through normal
 * charge setup, exactly as Section 11 requires ("Configure the percentage through normal charge setup, not through
 * {@code m_deposit_product_dynamic_detail}").
 *
 * Composite primary key on {@code (savings_product_id, charge_id)} with a composite foreign key to
 * {@code m_savings_product_charge(savings_product_id, charge_id)}, so the database itself guarantees the selected
 * charge is attached to the product. No audit columns - the spec lists none for this table, and the row carries no
 * state beyond the selection itself.
 */
@Entity
@Table(name = "m_deposit_product_early_withdrawal_charge")
@IdClass(DepositProductEarlyWithdrawalCharge.Key.class)
public class DepositProductEarlyWithdrawalCharge {

    @Id
    @Column(name = "savings_product_id", nullable = false)
    private Long savingsProductId;

    @Id
    @Column(name = "charge_id", nullable = false)
    private Long chargeId;

    protected DepositProductEarlyWithdrawalCharge() {
        //
    }

    private DepositProductEarlyWithdrawalCharge(final Long savingsProductId, final Long chargeId) {
        this.savingsProductId = savingsProductId;
        this.chargeId = chargeId;
    }

    public static DepositProductEarlyWithdrawalCharge createNew(final Long savingsProductId, final Long chargeId) {
        return new DepositProductEarlyWithdrawalCharge(savingsProductId, chargeId);
    }

    public Long savingsProductId() {
        return this.savingsProductId;
    }

    public Long chargeId() {
        return this.chargeId;
    }

    /**
     * JPA {@code @IdClass} for the composite primary key. Must be public, {@link Serializable}, and have a public
     * no-argument constructor plus value-based {@code equals}/{@code hashCode}.
     */
    public static class Key implements Serializable {

        private Long savingsProductId;
        private Long chargeId;

        public Key() {
            //
        }

        public Key(final Long savingsProductId, final Long chargeId) {
            this.savingsProductId = savingsProductId;
            this.chargeId = chargeId;
        }

        @Override
        public boolean equals(final Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof Key otherKey)) {
                return false;
            }
            return Objects.equals(this.savingsProductId, otherKey.savingsProductId) && Objects.equals(this.chargeId, otherKey.chargeId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(this.savingsProductId, this.chargeId);
        }
    }
}
