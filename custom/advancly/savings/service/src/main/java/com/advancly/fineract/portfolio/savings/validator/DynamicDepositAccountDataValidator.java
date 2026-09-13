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

import static com.advancly.fineract.portfolio.savings.DynamicDepositApiConstants.DYNAMIC_DEPOSIT_ACCOUNT_REQUEST_DATA_PARAMETERS;
import static com.advancly.fineract.portfolio.savings.DynamicDepositApiConstants.DYNAMIC_DEPOSIT_ACCOUNT_RESOURCE_NAME;
import static com.advancly.fineract.portfolio.savings.DynamicDepositApiConstants.allowWithdrawalParamName;

import com.google.gson.JsonElement;
import com.google.gson.reflect.TypeToken;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;
import org.apache.commons.lang3.StringUtils;
import org.apache.fineract.infrastructure.core.data.ApiParameterError;
import org.apache.fineract.infrastructure.core.data.DataValidatorBuilder;
import org.apache.fineract.infrastructure.core.exception.InvalidJsonException;
import org.apache.fineract.infrastructure.core.exception.PlatformApiDataValidationException;
import org.apache.fineract.infrastructure.core.serialization.FromJsonHelper;
import org.springframework.stereotype.Component;

/**
 * Validates create/update requests for the {@code DYNAMICDEPOSITACCOUNT} entity, including business rule 8 of the
 * implementation plan: {@code allowWithdrawal = false} requires {@code transferInterestToSavings = false}. The rule is
 * checked against the request's raw values where present and, for whichever side is omitted, against the value
 * {@code DynamicDepositAccountAssembler} would resolve (passed in as {@code resolvedAllowWithdrawal} /
 * {@code resolvedTransferInterestToSavings}), since either field may be inherited from the product or the account's
 * current state rather than supplied on this request.
 */
@Component
public class DynamicDepositAccountDataValidator {

    private final FromJsonHelper fromApiJsonHelper;

    public DynamicDepositAccountDataValidator(final FromJsonHelper fromApiJsonHelper) {
        this.fromApiJsonHelper = fromApiJsonHelper;
    }

    public void validateForCreate(final String json) {
        if (StringUtils.isBlank(json)) {
            throw new InvalidJsonException();
        }

        final Type typeOfMap = new TypeToken<java.util.Map<String, Object>>() {}.getType();
        this.fromApiJsonHelper.checkForUnsupportedParameters(typeOfMap, json, DYNAMIC_DEPOSIT_ACCOUNT_REQUEST_DATA_PARAMETERS);

        final List<ApiParameterError> dataValidationErrors = new ArrayList<>();
        final DataValidatorBuilder baseDataValidator = new DataValidatorBuilder(dataValidationErrors)
                .resource(DYNAMIC_DEPOSIT_ACCOUNT_RESOURCE_NAME);

        final JsonElement element = this.fromApiJsonHelper.parse(json);

        baseDataValidator.reset().parameter("productId").value(this.fromApiJsonHelper.extractLongNamed("productId", element)).notNull()
                .longGreaterThanZero();

        final Long clientId = this.fromApiJsonHelper.extractLongNamed("clientId", element);
        final Long groupId = this.fromApiJsonHelper.extractLongNamed("groupId", element);
        baseDataValidator.reset().anyOfNotNull(clientId, groupId);

        baseDataValidator.reset().parameter("submittedOnDate")
                .value(this.fromApiJsonHelper.extractLocalDateNamed("submittedOnDate", element)).notNull();
        baseDataValidator.reset().parameter("depositAmount")
                .value(this.fromApiJsonHelper.extractBigDecimalWithLocaleNamed("depositAmount", element)).notNull().positiveAmount();
        baseDataValidator.reset().parameter("depositPeriod")
                .value(this.fromApiJsonHelper.extractIntegerSansLocaleNamed("depositPeriod", element)).notNull().integerGreaterThanZero();
        baseDataValidator.reset().parameter("depositPeriodFrequencyId")
                .value(this.fromApiJsonHelper.extractIntegerSansLocaleNamed("depositPeriodFrequencyId", element)).notNull();

        if (this.fromApiJsonHelper.parameterExists("nominalAnnualInterestRate", element)) {
            baseDataValidator.reset().parameter("nominalAnnualInterestRate")
                    .value(this.fromApiJsonHelper.extractBigDecimalWithLocaleNamed("nominalAnnualInterestRate", element))
                    .zeroOrPositiveAmount();
        }

        if (!dataValidationErrors.isEmpty()) {
            throw new PlatformApiDataValidationException(dataValidationErrors);
        }
    }

    public void validateForUpdate(final String json) {
        if (StringUtils.isBlank(json)) {
            throw new InvalidJsonException();
        }

        final Type typeOfMap = new TypeToken<java.util.Map<String, Object>>() {}.getType();
        this.fromApiJsonHelper.checkForUnsupportedParameters(typeOfMap, json, DYNAMIC_DEPOSIT_ACCOUNT_REQUEST_DATA_PARAMETERS);
    }

    /**
     * Business rule 8: {@code allow_withdrawal = false} => {@code transfer_interest_to_savings = false}. Called by
     * {@code DynamicDepositAccountAssembler} with the values it has already resolved (request value, else the
     * account's/product's current value), since either field may be inherited rather than supplied on this request.
     */
    public void validateAllowWithdrawalTransferInterestRule(final boolean resolvedAllowWithdrawal,
            final boolean resolvedTransferInterestToSavings) {
        if (!resolvedAllowWithdrawal && resolvedTransferInterestToSavings) {
            final List<ApiParameterError> dataValidationErrors = new ArrayList<>();
            final DataValidatorBuilder baseDataValidator = new DataValidatorBuilder(dataValidationErrors)
                    .resource(DYNAMIC_DEPOSIT_ACCOUNT_RESOURCE_NAME);
            baseDataValidator.reset().parameter("transferInterestToSavings").value(resolvedTransferInterestToSavings)
                    .failWithCodeNoParameterAddedToErrorCode(allowWithdrawalParamName + ".false.requires.transferInterestToSavings.false");
            throw new PlatformApiDataValidationException(dataValidationErrors);
        }
    }
}
