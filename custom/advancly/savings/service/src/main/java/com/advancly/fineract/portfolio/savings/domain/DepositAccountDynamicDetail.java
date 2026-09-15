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

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import java.util.LinkedHashMap;
import java.util.Map;
import org.apache.fineract.infrastructure.core.api.JsonCommand;
import org.apache.fineract.infrastructure.core.domain.AbstractAuditableWithUTCDateTimeCustom;
import org.apache.fineract.portfolio.savings.domain.SavingsAccount;

/**
 * Account-level configuration for a Dynamic Deposit account (deposit_type_enum = 500).
 *
 * The values are resolved once at submission time: the request value is used when present, otherwise the value is
 * inherited from the product's {@link DepositProductDynamicDetail} (see DynamicDepositAccountAssembler).
 * {@code allowWithdrawal} is independent of {@code transferInterestToSavings} - disabling withdrawals does not require
 * or force disabling interest transfer, since that is not a regular (withdrawal-style) transaction.
 */
@Entity
@Table(name = "m_deposit_account_dynamic_detail")
public class DepositAccountDynamicDetail extends AbstractAuditableWithUTCDateTimeCustom<Long> {

    @OneToOne
    @JoinColumn(name = "savings_account_id", nullable = false, unique = true)
    private SavingsAccount account;

    @Column(name = "allow_withdrawal", nullable = false)
    private boolean allowWithdrawal;

    @Column(name = "dynamic_rate_enabled", nullable = false)
    private boolean dynamicRateEnabled;

    protected DepositAccountDynamicDetail() {
        //
    }

    private DepositAccountDynamicDetail(final SavingsAccount account, final boolean allowWithdrawal, final boolean dynamicRateEnabled) {
        this.account = account;
        this.allowWithdrawal = allowWithdrawal;
        this.dynamicRateEnabled = dynamicRateEnabled;
    }

    public static DepositAccountDynamicDetail createNew(final SavingsAccount account, final boolean allowWithdrawal,
            final boolean dynamicRateEnabled) {
        return new DepositAccountDynamicDetail(account, allowWithdrawal, dynamicRateEnabled);
    }

    public Map<String, Object> update(final JsonCommand command) {
        final Map<String, Object> actualChanges = new LinkedHashMap<>(2);

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

        return actualChanges;
    }

    public void updateAccountReference(final SavingsAccount account) {
        this.account = account;
    }

    public SavingsAccount account() {
        return this.account;
    }

    public boolean isAllowWithdrawal() {
        return this.allowWithdrawal;
    }

    public boolean isDynamicRateEnabled() {
        return this.dynamicRateEnabled;
    }
}
