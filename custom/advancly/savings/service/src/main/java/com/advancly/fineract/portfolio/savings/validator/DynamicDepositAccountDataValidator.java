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
import static com.advancly.fineract.portfolio.savings.DynamicDepositApiConstants.linkedAccountParamName;

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
 * Validates create/update requests for the {@code DYNAMICDEPOSITACCOUNT} entity.
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
     * A linked savings account is required whenever {@code transferInterestToSavings} is enabled, since interest cannot
     * be transferred anywhere without one. This does NOT depend on {@code allowWithdrawal} -
     * transfer-interest-to-savings is not a regular (withdrawal-style) transaction, so disabling withdrawals must never
     * force this off. Mirrors {@code DepositAccountDataValidator}'s {@code isLinkedAccRequired} /
     * {@code throwLinkedAccountRequiredError()} pattern for FD/RD accounts.
     */
    public void validateLinkedAccountRequiredWhenTransferInterestEnabled(final boolean transferInterestToSavings,
            final Long linkedAccountId) {
        if (transferInterestToSavings && linkedAccountId == null) {
            final List<ApiParameterError> dataValidationErrors = new ArrayList<>();
            final DataValidatorBuilder baseDataValidator = new DataValidatorBuilder(dataValidationErrors)
                    .resource(DYNAMIC_DEPOSIT_ACCOUNT_RESOURCE_NAME);
            baseDataValidator.reset().parameter(linkedAccountParamName).value(linkedAccountId).notNull().longGreaterThanZero();
            if (!dataValidationErrors.isEmpty()) {
                throw new PlatformApiDataValidationException(dataValidationErrors);
            }
        }
    }
}
