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
import static org.apache.fineract.portfolio.savings.DepositsApiConstants.depositAmountParamName;
import static org.apache.fineract.portfolio.savings.DepositsApiConstants.depositMaxAmountParamName;
import static org.apache.fineract.portfolio.savings.DepositsApiConstants.depositMinAmountParamName;
import static org.apache.fineract.portfolio.savings.DepositsApiConstants.maxDepositTermParamName;
import static org.apache.fineract.portfolio.savings.DepositsApiConstants.maxDepositTermTypeIdParamName;
import static org.apache.fineract.portfolio.savings.DepositsApiConstants.minDepositTermParamName;
import static org.apache.fineract.portfolio.savings.DepositsApiConstants.minDepositTermTypeIdParamName;
import static org.apache.fineract.portfolio.savings.DepositsApiConstants.withHoldTaxPostingTypeIdParamName;
import static org.apache.fineract.portfolio.savings.SavingsApiConstants.taxGroupIdParamName;
import static org.apache.fineract.portfolio.savings.SavingsApiConstants.withHoldTaxParamName;

import com.google.gson.JsonElement;
import com.google.gson.reflect.TypeToken;
import java.lang.reflect.Type;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.apache.fineract.accounting.common.AccountingValidations;
import org.apache.fineract.infrastructure.core.data.ApiParameterError;
import org.apache.fineract.infrastructure.core.data.DataValidatorBuilder;
import org.apache.fineract.infrastructure.core.exception.InvalidJsonException;
import org.apache.fineract.infrastructure.core.exception.PlatformApiDataValidationException;
import org.apache.fineract.infrastructure.core.serialization.FromJsonHelper;
import org.apache.fineract.portfolio.savings.DepositAccountType;
import org.apache.fineract.portfolio.savings.SavingsPeriodFrequencyType;
import org.apache.fineract.portfolio.savings.WithHoldTaxPostingType;
import org.apache.fineract.portfolio.savings.data.SavingsProductAccountingDataValidator;
import org.springframework.stereotype.Component;

/**
 * Validates create/update requests for the {@code DYNAMICDEPOSITPRODUCT} entity. Mirrors the shape of
 * {@code DepositProductDataValidator}'s fixed-deposit rules, scoped to the fields this Phase-1 product supports.
 */
@Component
public class DynamicDepositProductDataValidator {

    private final FromJsonHelper fromApiJsonHelper;
    private final SavingsProductAccountingDataValidator savingsProductAccountingDataValidator;

    public DynamicDepositProductDataValidator(final FromJsonHelper fromApiJsonHelper,
            final SavingsProductAccountingDataValidator savingsProductAccountingDataValidator) {
        this.fromApiJsonHelper = fromApiJsonHelper;
        this.savingsProductAccountingDataValidator = savingsProductAccountingDataValidator;
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

        baseDataValidator.reset().parameter("interestCompoundingPeriodType")
                .value(this.fromApiJsonHelper.extractIntegerSansLocaleNamed("interestCompoundingPeriodType", element)).notNull();
        baseDataValidator.reset().parameter("interestPostingPeriodType")
                .value(this.fromApiJsonHelper.extractIntegerSansLocaleNamed("interestPostingPeriodType", element)).notNull();
        baseDataValidator.reset().parameter("interestCalculationType")
                .value(this.fromApiJsonHelper.extractIntegerSansLocaleNamed("interestCalculationType", element)).notNull();
        baseDataValidator.reset().parameter("interestCalculationDaysInYearType")
                .value(this.fromApiJsonHelper.extractIntegerSansLocaleNamed("interestCalculationDaysInYearType", element)).notNull();

        final Integer accountingRuleType = this.fromApiJsonHelper.extractIntegerNamed("accountingRule", element, Locale.getDefault());
        baseDataValidator.reset().parameter("accountingRule").value(accountingRuleType).notNull().inMinMaxRange(1, 3);

        if (AccountingValidations.isCashBasedAccounting(accountingRuleType)
                || AccountingValidations.isAccrualPeriodicBasedAccounting(accountingRuleType)) {
            this.savingsProductAccountingDataValidator.evaluateProductAccountingData(accountingRuleType, false, element, baseDataValidator,
                    DepositAccountType.DYNAMIC_DEPOSIT, true);
        }

        validateTaxWithHoldingParams(baseDataValidator, element, true);
        validateDepositTermDetailForCreate(element, baseDataValidator);
        validateDepositAmountForCreate(element, baseDataValidator);

        if (!dataValidationErrors.isEmpty()) {
            throw new PlatformApiDataValidationException(dataValidationErrors);
        }
    }

    private void validateDepositTermDetailForCreate(final JsonElement element, final DataValidatorBuilder baseDataValidator) {
        final Integer minTerm = this.fromApiJsonHelper.extractIntegerSansLocaleNamed(minDepositTermParamName, element);
        baseDataValidator.reset().parameter(minDepositTermParamName).value(minTerm).notNull().integerGreaterThanZero();

        if (this.fromApiJsonHelper.parameterExists(maxDepositTermParamName, element)) {
            final Integer maxTerm = this.fromApiJsonHelper.extractIntegerSansLocaleNamed(maxDepositTermParamName, element);
            baseDataValidator.reset().parameter(maxDepositTermParamName).value(maxTerm).integerGreaterThanZero();
        }

        final Integer minDepositTermType = this.fromApiJsonHelper.extractIntegerSansLocaleNamed(minDepositTermTypeIdParamName, element);
        baseDataValidator.reset().parameter(minDepositTermTypeIdParamName).value(minDepositTermType).ignoreIfNull()
                .isOneOfTheseValues(SavingsPeriodFrequencyType.integerValues());

        if (this.fromApiJsonHelper.parameterExists(maxDepositTermTypeIdParamName, element)) {
            final Integer maxDepositTermType = this.fromApiJsonHelper.extractIntegerSansLocaleNamed(maxDepositTermTypeIdParamName, element);
            baseDataValidator.reset().parameter(maxDepositTermTypeIdParamName).value(maxDepositTermType)
                    .isOneOfTheseValues(SavingsPeriodFrequencyType.integerValues());
        }
    }

    private void validateDepositTermDetailForUpdate(final JsonElement element, final DataValidatorBuilder baseDataValidator) {
        if (this.fromApiJsonHelper.parameterExists(minDepositTermParamName, element)) {
            final Integer minTerm = this.fromApiJsonHelper.extractIntegerSansLocaleNamed(minDepositTermParamName, element);
            baseDataValidator.reset().parameter(minDepositTermParamName).value(minTerm).integerGreaterThanZero();
        }

        if (this.fromApiJsonHelper.parameterExists(maxDepositTermParamName, element)) {
            final Integer maxTerm = this.fromApiJsonHelper.extractIntegerSansLocaleNamed(maxDepositTermParamName, element);
            baseDataValidator.reset().parameter(maxDepositTermParamName).value(maxTerm).integerGreaterThanZero();
        }

        if (this.fromApiJsonHelper.parameterExists(minDepositTermTypeIdParamName, element)) {
            final Integer minDepositTermType = this.fromApiJsonHelper.extractIntegerSansLocaleNamed(minDepositTermTypeIdParamName, element);
            baseDataValidator.reset().parameter(minDepositTermTypeIdParamName).value(minDepositTermType)
                    .isOneOfTheseValues(SavingsPeriodFrequencyType.integerValues());
        }

        if (this.fromApiJsonHelper.parameterExists(maxDepositTermTypeIdParamName, element)) {
            final Integer maxDepositTermType = this.fromApiJsonHelper.extractIntegerSansLocaleNamed(maxDepositTermTypeIdParamName, element);
            baseDataValidator.reset().parameter(maxDepositTermTypeIdParamName).value(maxDepositTermType)
                    .isOneOfTheseValues(SavingsPeriodFrequencyType.integerValues());
        }
    }

    private void validateDepositAmountForCreate(final JsonElement element, final DataValidatorBuilder baseDataValidator) {
        final BigDecimal depositAmount = this.fromApiJsonHelper.extractBigDecimalWithLocaleNamed(depositAmountParamName, element);
        baseDataValidator.reset().parameter(depositAmountParamName).value(depositAmount).notNull().positiveAmount();
        validateDepositAmountRange(element, baseDataValidator, depositAmount);
    }

    private void validateDepositAmountForUpdate(final JsonElement element, final DataValidatorBuilder baseDataValidator) {
        BigDecimal depositAmount = null;
        if (this.fromApiJsonHelper.parameterExists(depositAmountParamName, element)) {
            depositAmount = this.fromApiJsonHelper.extractBigDecimalWithLocaleNamed(depositAmountParamName, element);
            baseDataValidator.reset().parameter(depositAmountParamName).value(depositAmount).notNull().positiveAmount();
        }
        validateDepositAmountRange(element, baseDataValidator, depositAmount);
    }

    private void validateDepositAmountRange(final JsonElement element, final DataValidatorBuilder baseDataValidator,
            final BigDecimal depositAmount) {
        BigDecimal depositMinAmount = null;
        if (this.fromApiJsonHelper.parameterExists(depositMinAmountParamName, element)) {
            depositMinAmount = this.fromApiJsonHelper.extractBigDecimalWithLocaleNamed(depositMinAmountParamName, element);
            baseDataValidator.reset().parameter(depositMinAmountParamName).value(depositMinAmount).notNull().positiveAmount();
        }

        BigDecimal depositMaxAmount = null;
        if (this.fromApiJsonHelper.parameterExists(depositMaxAmountParamName, element)) {
            depositMaxAmount = this.fromApiJsonHelper.extractBigDecimalWithLocaleNamed(depositMaxAmountParamName, element);
            baseDataValidator.reset().parameter(depositMaxAmountParamName).value(depositMaxAmount).notNull().positiveAmount();
        }

        if (depositAmount == null) {
            return;
        }

        if (depositMaxAmount != null) {
            if (depositMinAmount != null) {
                baseDataValidator.reset().parameter(depositMaxAmountParamName).value(depositMaxAmount).notLessThanMin(depositMinAmount);
                if (depositMinAmount.compareTo(depositMaxAmount) <= 0) {
                    baseDataValidator.reset().parameter(depositAmountParamName).value(depositAmount)
                            .inMinAndMaxAmountRange(depositMinAmount, depositMaxAmount);
                }
            } else {
                baseDataValidator.reset().parameter(depositAmountParamName).value(depositAmount).notGreaterThanMax(depositMaxAmount);
            }
        } else if (depositMinAmount != null) {
            baseDataValidator.reset().parameter(depositAmountParamName).value(depositAmount).notLessThanMin(depositMinAmount);
        }
    }

    /**
     * Shared by create and update: fixes the bug where update() previously skipped the "withHoldTax==true requires
     * taxGroupId" check that create() already had, and adds the withHoldTaxPostingTypeId requirement that Dynamic
     * Deposit never validated at all (mirrors {@code DepositProductDataValidator#validateTaxWithHoldingParams}).
     */
    private void validateTaxWithHoldingParams(final DataValidatorBuilder baseDataValidator, final JsonElement element,
            final boolean isCreate) {
        Boolean withHoldTax = this.fromApiJsonHelper.extractBooleanNamed(withHoldTaxParamName, element);
        if (withHoldTax == null) {
            withHoldTax = false;
        }

        if (this.fromApiJsonHelper.parameterExists(taxGroupIdParamName, element)) {
            final Long taxGroupId = this.fromApiJsonHelper.extractLongNamed(taxGroupIdParamName, element);
            baseDataValidator.reset().parameter(taxGroupIdParamName).value(taxGroupId).ignoreIfNull().longGreaterThanZero();
            if (withHoldTax) {
                baseDataValidator.reset().parameter(taxGroupIdParamName).value(taxGroupId).notNull();
            }
        } else if (withHoldTax && isCreate) {
            final Long taxGroupId = null;
            baseDataValidator.reset().parameter(taxGroupIdParamName).value(taxGroupId).notNull();
        }

        if (withHoldTax) {
            final Integer withHoldTaxPostingTypeId = this.fromApiJsonHelper.extractIntegerSansLocaleNamed(withHoldTaxPostingTypeIdParamName,
                    element);
            if (isCreate || this.fromApiJsonHelper.parameterExists(withHoldTaxPostingTypeIdParamName, element)) {
                baseDataValidator.reset().parameter(withHoldTaxPostingTypeIdParamName).value(withHoldTaxPostingTypeId).notNull()
                        .isOneOfTheseValues(WithHoldTaxPostingType.integerValues());
            }
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
            final Integer accountingRuleType = this.fromApiJsonHelper.extractIntegerNamed("accountingRule", element, Locale.getDefault());
            baseDataValidator.reset().parameter("accountingRule").value(accountingRuleType).notNull().inMinMaxRange(1, 3);

            if (AccountingValidations.isCashBasedAccounting(accountingRuleType)
                    || AccountingValidations.isAccrualPeriodicBasedAccounting(accountingRuleType)) {
                this.savingsProductAccountingDataValidator.evaluateProductAccountingData(accountingRuleType, false, element,
                        baseDataValidator, DepositAccountType.DYNAMIC_DEPOSIT, false);
            }
        }

        validateTaxWithHoldingParams(baseDataValidator, element, false);
        validateDepositTermDetailForUpdate(element, baseDataValidator);
        validateDepositAmountForUpdate(element, baseDataValidator);

        if (!dataValidationErrors.isEmpty()) {
            throw new PlatformApiDataValidationException(dataValidationErrors);
        }
    }
}
