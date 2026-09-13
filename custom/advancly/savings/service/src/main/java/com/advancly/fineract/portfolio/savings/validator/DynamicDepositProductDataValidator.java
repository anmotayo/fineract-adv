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

import static com.advancly.fineract.portfolio.savings.DynamicDepositApiConstants.DYNAMIC_DEPOSIT_PRODUCT_REQUEST_DATA_PARAMETERS;
import static com.advancly.fineract.portfolio.savings.DynamicDepositApiConstants.DYNAMIC_DEPOSIT_PRODUCT_RESOURCE_NAME;

import com.google.gson.JsonElement;
import com.google.gson.reflect.TypeToken;
import java.lang.reflect.Type;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import org.apache.fineract.accounting.common.AccountingRuleType;
import org.apache.fineract.infrastructure.core.data.ApiParameterError;
import org.apache.fineract.infrastructure.core.data.DataValidatorBuilder;
import org.apache.fineract.infrastructure.core.exception.InvalidJsonException;
import org.apache.fineract.infrastructure.core.exception.PlatformApiDataValidationException;
import org.apache.fineract.infrastructure.core.serialization.FromJsonHelper;
import org.springframework.stereotype.Component;

/**
 * Validates create/update requests for the {@code DYNAMICDEPOSITPRODUCT} entity. Mirrors the shape of
 * {@code DepositProductDataValidator}'s fixed-deposit rules, scoped to the fields this Phase-1 product supports.
 */
@Component
public class DynamicDepositProductDataValidator {

    private final FromJsonHelper fromApiJsonHelper;

    public DynamicDepositProductDataValidator(final FromJsonHelper fromApiJsonHelper) {
        this.fromApiJsonHelper = fromApiJsonHelper;
    }

    public void validateForCreate(final String json) {
        if (org.apache.commons.lang3.StringUtils.isBlank(json)) {
            throw new InvalidJsonException();
        }

        final Type typeOfMap = new TypeToken<java.util.Map<String, Object>>() {}.getType();
        this.fromApiJsonHelper.checkForUnsupportedParameters(typeOfMap, json, DYNAMIC_DEPOSIT_PRODUCT_REQUEST_DATA_PARAMETERS);

        final List<ApiParameterError> dataValidationErrors = new ArrayList<>();
        final DataValidatorBuilder baseDataValidator = new DataValidatorBuilder(dataValidationErrors)
                .resource(DYNAMIC_DEPOSIT_PRODUCT_RESOURCE_NAME);

        final JsonElement element = this.fromApiJsonHelper.parse(json);

        baseDataValidator.reset().parameter("name").value(this.fromApiJsonHelper.extractStringNamed("name", element)).notBlank()
                .notExceedingLengthOf(100);
        baseDataValidator.reset().parameter("shortName").value(this.fromApiJsonHelper.extractStringNamed("shortName", element)).notBlank()
                .notExceedingLengthOf(4);
        baseDataValidator.reset().parameter("description").value(this.fromApiJsonHelper.extractStringNamed("description", element))
                .notBlank();
        baseDataValidator.reset().parameter("currencyCode").value(this.fromApiJsonHelper.extractStringNamed("currencyCode", element))
                .notBlank();
        baseDataValidator.reset().parameter("digitsAfterDecimal")
                .value(this.fromApiJsonHelper.extractIntegerSansLocaleNamed("digitsAfterDecimal", element)).notNull().inMinMaxRange(0, 6);
        baseDataValidator.reset().parameter("inMultiplesOf")
                .value(this.fromApiJsonHelper.extractIntegerSansLocaleNamed("inMultiplesOf", element)).ignoreIfNull()
                .integerZeroOrGreater();

        final BigDecimal nominalAnnualInterestRate = this.fromApiJsonHelper.extractBigDecimalWithLocaleNamed("nominalAnnualInterestRate",
                element);
        baseDataValidator.reset().parameter("nominalAnnualInterestRate").value(nominalAnnualInterestRate).ignoreIfNull()
                .zeroOrPositiveAmount();

        baseDataValidator.reset().parameter("interestCompoundingPeriodType")
                .value(this.fromApiJsonHelper.extractIntegerSansLocaleNamed("interestCompoundingPeriodType", element)).notNull();
        baseDataValidator.reset().parameter("interestPostingPeriodType")
                .value(this.fromApiJsonHelper.extractIntegerSansLocaleNamed("interestPostingPeriodType", element)).notNull();
        baseDataValidator.reset().parameter("interestCalculationType")
                .value(this.fromApiJsonHelper.extractIntegerSansLocaleNamed("interestCalculationType", element)).notNull();
        baseDataValidator.reset().parameter("interestCalculationDaysInYearType")
                .value(this.fromApiJsonHelper.extractIntegerSansLocaleNamed("interestCalculationDaysInYearType", element)).notNull();

        final Integer accountingRuleType = this.fromApiJsonHelper.extractIntegerSansLocaleNamed("accountingRule", element);
        baseDataValidator.reset().parameter("accountingRule").value(accountingRuleType).notNull()
                .isOneOfTheseValues((Object[]) AccountingRuleType.values());

        if (this.fromApiJsonHelper.parameterExists("withHoldTax", element)) {
            final boolean withHoldTax = this.fromApiJsonHelper.extractBooleanNamed("withHoldTax", element);
            if (withHoldTax) {
                baseDataValidator.reset().parameter("taxGroupId").value(this.fromApiJsonHelper.extractLongNamed("taxGroupId", element))
                        .notNull();
            }
        }

        if (!dataValidationErrors.isEmpty()) {
            throw new PlatformApiDataValidationException(dataValidationErrors);
        }
    }

    public void validateForUpdate(final String json) {
        if (org.apache.commons.lang3.StringUtils.isBlank(json)) {
            throw new InvalidJsonException();
        }

        final Type typeOfMap = new TypeToken<java.util.Map<String, Object>>() {}.getType();
        this.fromApiJsonHelper.checkForUnsupportedParameters(typeOfMap, json, DYNAMIC_DEPOSIT_PRODUCT_REQUEST_DATA_PARAMETERS);

        final List<ApiParameterError> dataValidationErrors = new ArrayList<>();
        final DataValidatorBuilder baseDataValidator = new DataValidatorBuilder(dataValidationErrors)
                .resource(DYNAMIC_DEPOSIT_PRODUCT_RESOURCE_NAME);

        final JsonElement element = this.fromApiJsonHelper.parse(json);

        if (this.fromApiJsonHelper.parameterExists("name", element)) {
            baseDataValidator.reset().parameter("name").value(this.fromApiJsonHelper.extractStringNamed("name", element)).notBlank()
                    .notExceedingLengthOf(100);
        }
        if (this.fromApiJsonHelper.parameterExists("shortName", element)) {
            baseDataValidator.reset().parameter("shortName").value(this.fromApiJsonHelper.extractStringNamed("shortName", element))
                    .notBlank().notExceedingLengthOf(4);
        }
        if (this.fromApiJsonHelper.parameterExists("accountingRule", element)) {
            baseDataValidator.reset().parameter("accountingRule")
                    .value(this.fromApiJsonHelper.extractIntegerSansLocaleNamed("accountingRule", element)).notNull()
                    .isOneOfTheseValues((Object[]) AccountingRuleType.values());
        }

        if (!dataValidationErrors.isEmpty()) {
            throw new PlatformApiDataValidationException(dataValidationErrors);
        }
    }
}
