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

import static com.advancly.fineract.portfolio.savings.DynamicDepositApiConstants.allowWithdrawalParamName;
import static com.advancly.fineract.portfolio.savings.DynamicDepositApiConstants.dynamicRateEnabledParamName;
import static com.advancly.fineract.portfolio.savings.DynamicDepositApiConstants.earlyWithdrawalPenaltyEnabledParamName;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import java.util.LinkedHashMap;
import java.util.Map;
import org.apache.fineract.infrastructure.core.api.JsonCommand;
import org.apache.fineract.infrastructure.core.domain.AbstractAuditableWithUTCDateTimeCustom;
import org.apache.fineract.portfolio.savings.domain.SavingsProduct;

/**
 * Product-level configuration for the Dynamic Deposit product type (deposit_type_enum = 500).
 *
 * Captures whether withdrawals are allowed on accounts created from this product and whether the interest rate is
 * re-resolved from the interest rate chart on every principal-changing transaction. New accounts inherit these values
 * unless overridden at submission (see {@link DepositAccountDynamicDetail}).
 */
@Entity
@Table(name = "m_deposit_product_dynamic_detail")
public class DepositProductDynamicDetail extends AbstractAuditableWithUTCDateTimeCustom<Long> {

    @OneToOne
    @JoinColumn(name = "savings_product_id", nullable = false, unique = true)
    private SavingsProduct product;

    @Column(name = "allow_withdrawal", nullable = false)
    private boolean allowWithdrawal;

    @Column(name = "dynamic_rate_enabled", nullable = false)
    private boolean dynamicRateEnabled;

    /**
     * Phase 4 (implementation plan Section 2): gate for the early-withdrawal penalty mechanism. When {@code false}, no
     * pending {@code m_savings_account_interest_charge} row is ever created for accounts of this product, whatever is
     * selected in {@code m_deposit_product_early_withdrawal_charge}.
     */
    @Column(name = "early_withdrawal_penalty_enabled", nullable = false)
    private boolean earlyWithdrawalPenaltyEnabled;

    protected DepositProductDynamicDetail() {
        //
    }

    private DepositProductDynamicDetail(final SavingsProduct product, final boolean allowWithdrawal, final boolean dynamicRateEnabled,
            final boolean earlyWithdrawalPenaltyEnabled) {
        this.product = product;
        this.allowWithdrawal = allowWithdrawal;
        this.dynamicRateEnabled = dynamicRateEnabled;
        this.earlyWithdrawalPenaltyEnabled = earlyWithdrawalPenaltyEnabled;
    }

    public static DepositProductDynamicDetail createNew(final SavingsProduct product, final boolean allowWithdrawal,
            final boolean dynamicRateEnabled, final boolean earlyWithdrawalPenaltyEnabled) {
        return new DepositProductDynamicDetail(product, allowWithdrawal, dynamicRateEnabled, earlyWithdrawalPenaltyEnabled);
    }

    public Map<String, Object> update(final JsonCommand command) {
        final Map<String, Object> actualChanges = new LinkedHashMap<>(3);

        if (command.isChangeInBooleanParameterNamed(allowWithdrawalParamName, this.allowWithdrawal)) {
            final boolean newValue = command.booleanPrimitiveValueOfParameterNamed(allowWithdrawalParamName);
            actualChanges.put(allowWithdrawalParamName, newValue);
            this.allowWithdrawal = newValue;
        }

        if (command.isChangeInBooleanParameterNamed(dynamicRateEnabledParamName, this.dynamicRateEnabled)) {
            final boolean newValue = command.booleanPrimitiveValueOfParameterNamed(dynamicRateEnabledParamName);
            actualChanges.put(dynamicRateEnabledParamName, newValue);
            this.dynamicRateEnabled = newValue;
        }

        if (command.isChangeInBooleanParameterNamed(earlyWithdrawalPenaltyEnabledParamName, this.earlyWithdrawalPenaltyEnabled)) {
            final boolean newValue = command.booleanPrimitiveValueOfParameterNamed(earlyWithdrawalPenaltyEnabledParamName);
            actualChanges.put(earlyWithdrawalPenaltyEnabledParamName, newValue);
            this.earlyWithdrawalPenaltyEnabled = newValue;
        }

        return actualChanges;
    }

    public void updateProductReference(final SavingsProduct product) {
        this.product = product;
    }

    public SavingsProduct product() {
        return this.product;
    }

    public boolean isAllowWithdrawal() {
        return this.allowWithdrawal;
    }

    public boolean isDynamicRateEnabled() {
        return this.dynamicRateEnabled;
    }

    public boolean isEarlyWithdrawalPenaltyEnabled() {
        return this.earlyWithdrawalPenaltyEnabled;
    }
}
