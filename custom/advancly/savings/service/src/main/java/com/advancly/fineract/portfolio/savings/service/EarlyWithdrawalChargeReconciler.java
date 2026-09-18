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
package com.advancly.fineract.portfolio.savings.service;

import com.advancly.fineract.portfolio.savings.domain.EarlyWithdrawalChargeMode;
import com.advancly.fineract.portfolio.savings.domain.SavingsProductEarlyWithdrawalCharge;
import com.advancly.fineract.portfolio.savings.domain.SavingsProductEarlyWithdrawalChargeRepository;
import com.advancly.fineract.portfolio.savings.validator.DynamicDepositEarlyWithdrawalChargeValidator;
import java.util.Collection;
import java.util.List;
import org.apache.fineract.infrastructure.core.api.JsonCommand;
import org.apache.fineract.portfolio.charge.domain.Charge;
import org.apache.fineract.portfolio.savings.SavingsCompoundingInterestPeriodType;
import org.springframework.stereotype.Component;

/**
 * Applies a product's early-withdrawal penalty charge+mode selection with replace-rather-than-append semantics
 * (implementation plan Section 2) — the primary enforcement of "only one active early-withdrawal charge per product".
 * Shared by Dynamic Deposit's and plain Savings's product write services so the rule has exactly one implementation.
 * The resource name flows from the caller into the shared validator methods, enabling each product type's error
 * responses to name the correct API resource.
 */
@Component
public class EarlyWithdrawalChargeReconciler {

    private final SavingsProductEarlyWithdrawalChargeRepository earlyWithdrawalChargeRepository;

    public EarlyWithdrawalChargeReconciler(final SavingsProductEarlyWithdrawalChargeRepository earlyWithdrawalChargeRepository) {
        this.earlyWithdrawalChargeRepository = earlyWithdrawalChargeRepository;
    }

    public void reconcile(final Long savingsProductId, final JsonCommand command, final boolean earlyWithdrawalPenaltyEnabled,
            final Collection<Charge> productCharges, final SavingsCompoundingInterestPeriodType compoundingPeriodType,
            final String earlyWithdrawalChargeIdParamName, final String earlyWithdrawalChargeModeParamName, final String resourceName) {

        final List<SavingsProductEarlyWithdrawalCharge> existingRows = this.earlyWithdrawalChargeRepository
                .findBySavingsProductId(savingsProductId);
        DynamicDepositEarlyWithdrawalChargeValidator.validateAtMostOneActiveCharge(existingRows, resourceName);

        final Long requestedChargeId = command.parameterExists(earlyWithdrawalChargeIdParamName)
                ? command.longValueOfParameterNamed(earlyWithdrawalChargeIdParamName)
                : (existingRows.isEmpty() ? null : existingRows.get(0).chargeId());

        final Integer requestedMode = command.parameterExists(earlyWithdrawalChargeModeParamName)
                ? command.integerValueOfParameterNamed(earlyWithdrawalChargeModeParamName)
                : (existingRows.isEmpty() ? null : existingRows.get(0).mode().getValue());
        final EarlyWithdrawalChargeMode mode = EarlyWithdrawalChargeMode.fromInt(requestedMode);

        final Charge resolvedCharge = DynamicDepositEarlyWithdrawalChargeValidator.validateAndResolve(earlyWithdrawalPenaltyEnabled,
                requestedChargeId, productCharges, mode, compoundingPeriodType, resourceName);

        this.earlyWithdrawalChargeRepository.deleteAll(existingRows);
        this.earlyWithdrawalChargeRepository.flush();
        if (resolvedCharge != null) {
            this.earlyWithdrawalChargeRepository
                    .saveAndFlush(SavingsProductEarlyWithdrawalCharge.createNew(savingsProductId, resolvedCharge.getId(), mode));
        }
    }
}
