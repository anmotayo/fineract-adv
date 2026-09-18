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
package com.advancly.fineract.portfolio.savings.validator;

import static com.advancly.fineract.portfolio.savings.DynamicDepositApiConstants.earlyWithdrawalChargeIdParamName;
import static com.advancly.fineract.portfolio.savings.DynamicDepositApiConstants.earlyWithdrawalChargeModeParamName;

import com.advancly.fineract.portfolio.savings.domain.EarlyWithdrawalChargeMode;
import com.advancly.fineract.portfolio.savings.domain.SavingsProductEarlyWithdrawalCharge;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import org.apache.fineract.infrastructure.core.data.ApiParameterError;
import org.apache.fineract.infrastructure.core.data.DataValidatorBuilder;
import org.apache.fineract.infrastructure.core.exception.PlatformApiDataValidationException;
import org.apache.fineract.portfolio.charge.domain.Charge;
import org.apache.fineract.portfolio.charge.domain.ChargeCalculationType;
import org.apache.fineract.portfolio.charge.domain.ChargeTimeType;
import org.apache.fineract.portfolio.savings.SavingsCompoundingInterestPeriodType;

/**
 * Product-level validation of a Dynamic Deposit product's early-withdrawal penalty charge selection (implementation
 * plan Section 2 constraints + Section 11's qualification rules). Deliberately static and Spring-free so the rules can
 * be unit tested without a context; the caller ({@code DynamicDepositProductWritePlatformServiceJpaRepositoryImpl})
 * supplies the product's already-assembled {@code m_savings_product_charge} set.
 *
 * This does NOT re-check that the calculation type is allowed for the product TYPE - Task 1 of this phase already does
 * that in {@code SavingsProductBaseAssembler#assembleListOfSavingsProductCharges} (commit f51316b8f) and
 * {@code SavingsAccountChargeAssembler#validateChargeAllowedForDepositAccountType} (commit ffbb00218). What it checks
 * is narrower: that the charge singled out as THE early-withdrawal penalty really is an active, penalty, withdrawal
 * fee, percent-of-interest charge attached to this product.
 */
public final class DynamicDepositEarlyWithdrawalChargeValidator {

    private DynamicDepositEarlyWithdrawalChargeValidator() {

    }

    /**
     * @param earlyWithdrawalPenaltyEnabled
     *            the product's {@code early_withdrawal_penalty_enabled} flag
     * @param earlyWithdrawalChargeId
     *            the id of the charge singled out as the early-withdrawal penalty, or {@code null}
     * @param productCharges
     *            the product's charges, i.e. its {@code m_savings_product_charge} rows
     * @param mode
     *            how the penalty takes interest back; {@code CUMULATIVE} is only valid on a
     *            {@code NO_COMPOUNDING_SIMPLE_INTEREST} product (see the check below)
     * @param compoundingPeriodType
     *            the product's compounding period type
     * @param resourceName
     *            the API resource name used in error responses (allows sharing this validator across product types)
     * @return the resolved {@link Charge}, or {@code null} when the penalty is disabled and no charge is selected
     */
    public static Charge validateAndResolve(final boolean earlyWithdrawalPenaltyEnabled, final Long earlyWithdrawalChargeId,
            final Collection<Charge> productCharges, final EarlyWithdrawalChargeMode mode,
            final SavingsCompoundingInterestPeriodType compoundingPeriodType, final String resourceName) {

        final List<ApiParameterError> dataValidationErrors = new ArrayList<>();
        final DataValidatorBuilder baseDataValidator = new DataValidatorBuilder(dataValidationErrors).resource(resourceName);

        if (!earlyWithdrawalPenaltyEnabled) {
            if (earlyWithdrawalChargeId != null) {
                baseDataValidator.reset().parameter(earlyWithdrawalChargeIdParamName).value(earlyWithdrawalChargeId)
                        .failWithCodeNoParameterAddedToErrorCode("early.withdrawal.charge.not.allowed.when.disabled");
                throw new PlatformApiDataValidationException(dataValidationErrors);
            }
            return null;
        }

        if (earlyWithdrawalChargeId == null) {
            baseDataValidator.reset().parameter(earlyWithdrawalChargeIdParamName).value(null)
                    .failWithCodeNoParameterAddedToErrorCode("early.withdrawal.charge.required");
            throw new PlatformApiDataValidationException(dataValidationErrors);
        }

        Charge selectedCharge = null;
        if (productCharges != null) {
            for (final Charge charge : productCharges) {
                if (earlyWithdrawalChargeId.equals(charge.getId())) {
                    selectedCharge = charge;
                    break;
                }
            }
        }

        if (selectedCharge == null) {
            baseDataValidator.reset().parameter(earlyWithdrawalChargeIdParamName).value(earlyWithdrawalChargeId)
                    .failWithCodeNoParameterAddedToErrorCode("early.withdrawal.charge.not.attached.to.product");
            throw new PlatformApiDataValidationException(dataValidationErrors);
        }

        if (!selectedCharge.isActive()) {
            baseDataValidator.reset().parameter(earlyWithdrawalChargeIdParamName).value(earlyWithdrawalChargeId)
                    .failWithCodeNoParameterAddedToErrorCode("early.withdrawal.charge.not.active");
            throw new PlatformApiDataValidationException(dataValidationErrors);
        }

        if (!selectedCharge.isPenalty()) {
            baseDataValidator.reset().parameter(earlyWithdrawalChargeIdParamName).value(earlyWithdrawalChargeId)
                    .failWithCodeNoParameterAddedToErrorCode("early.withdrawal.charge.not.penalty");
            throw new PlatformApiDataValidationException(dataValidationErrors);
        }

        if (!ChargeCalculationType.fromInt(selectedCharge.getChargeCalculation()).isPercentageOfInterest()) {
            baseDataValidator.reset().parameter(earlyWithdrawalChargeIdParamName).value(earlyWithdrawalChargeId)
                    .failWithCodeNoParameterAddedToErrorCode("early.withdrawal.charge.not.percent.of.interest");
            throw new PlatformApiDataValidationException(dataValidationErrors);
        }

        if (!ChargeTimeType.fromInt(selectedCharge.getChargeTimeType()).isWithdrawalFee()) {
            baseDataValidator.reset().parameter(earlyWithdrawalChargeIdParamName).value(earlyWithdrawalChargeId)
                    .failWithCodeNoParameterAddedToErrorCode("early.withdrawal.charge.not.withdrawal.fee");
            throw new PlatformApiDataValidationException(dataValidationErrors);
        }

        if (mode.isCumulative() && compoundingPeriodType != SavingsCompoundingInterestPeriodType.NO_COMPOUNDING_SIMPLE_INTEREST) {
            baseDataValidator.reset().parameter(earlyWithdrawalChargeModeParamName).value(mode.getValue())
                    .failWithCodeNoParameterAddedToErrorCode("early.withdrawal.charge.cumulative.mode.requires.no.compounding");
            throw new PlatformApiDataValidationException(dataValidationErrors);
        }

        return selectedCharge;
    }

    /**
     * Defensive enforcement of "only one active early-withdrawal charge per product" (Section 2). The primary
     * enforcement is the singular {@code earlyWithdrawalChargeId} API parameter plus the write service's
     * replace-rather-than-append semantics; this rejects a product whose classifier table already holds more than one
     * row, which can only happen if rows were written outside the API.
     *
     * @param existingRows
     *            the currently-stored early-withdrawal charge rows for the product
     * @param resourceName
     *            the API resource name used in error responses (allows sharing this validator across product types)
     */
    public static void validateAtMostOneActiveCharge(final List<SavingsProductEarlyWithdrawalCharge> existingRows,
            final String resourceName) {
        if (existingRows != null && existingRows.size() > 1) {
            final List<ApiParameterError> dataValidationErrors = new ArrayList<>();
            new DataValidatorBuilder(dataValidationErrors).resource(resourceName).reset().parameter(earlyWithdrawalChargeIdParamName)
                    .value(existingRows.size())
                    .failWithCodeNoParameterAddedToErrorCode("early.withdrawal.charge.must.be.unique.per.product");
            throw new PlatformApiDataValidationException(dataValidationErrors);
        }
    }
}
