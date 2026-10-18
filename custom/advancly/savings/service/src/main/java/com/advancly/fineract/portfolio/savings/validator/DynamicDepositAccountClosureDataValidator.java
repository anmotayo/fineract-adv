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

import static com.advancly.fineract.portfolio.savings.AdvanclyInterestChargeApiConstants.EARLY_WITHDRAWAL_CHARGE_PERCENTAGE;
import static com.advancly.fineract.portfolio.savings.DynamicDepositApiConstants.DYNAMIC_DEPOSIT_ACCOUNT_RESOURCE_NAME;
import static org.apache.fineract.portfolio.savings.DepositsApiConstants.bankNumberParamName;
import static org.apache.fineract.portfolio.savings.DepositsApiConstants.checkNumberParamName;
import static org.apache.fineract.portfolio.savings.DepositsApiConstants.closedOnDateParamName;
import static org.apache.fineract.portfolio.savings.DepositsApiConstants.onAccountClosureIdParamName;
import static org.apache.fineract.portfolio.savings.DepositsApiConstants.paymentTypeIdParamName;
import static org.apache.fineract.portfolio.savings.DepositsApiConstants.receiptNumberParamName;
import static org.apache.fineract.portfolio.savings.DepositsApiConstants.routingCodeParamName;
import static org.apache.fineract.portfolio.savings.DepositsApiConstants.toSavingsAccountIdParamName;
import static org.apache.fineract.portfolio.savings.DepositsApiConstants.transactionAccountNumberParamName;

import com.google.gson.JsonElement;
import com.google.gson.reflect.TypeToken;
import java.lang.reflect.Type;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.commons.lang3.StringUtils;
import org.apache.fineract.infrastructure.core.api.JsonCommand;
import org.apache.fineract.infrastructure.core.data.ApiParameterError;
import org.apache.fineract.infrastructure.core.data.DataValidatorBuilder;
import org.apache.fineract.infrastructure.core.exception.InvalidJsonException;
import org.apache.fineract.infrastructure.core.exception.PlatformApiDataValidationException;
import org.apache.fineract.infrastructure.core.serialization.FromJsonHelper;
import org.apache.fineract.portfolio.savings.DepositAccountOnClosureType;
import org.apache.fineract.portfolio.savings.DepositsApiConstants;
import org.springframework.stereotype.Component;

/**
 * Validates {@code close} and {@code prematureClose} requests for the {@code DYNAMICDEPOSITACCOUNT} entity.
 *
 * Takes the request shape Fixed and Recurring Deposit closure takes
 * ({@code DepositAccountTransactionDataValidator#validateClosing}) so every deposit type closes the same way. A
 * premature closure additionally accepts {@code earlyWithdrawalChargePercentage}, the override a Dynamic Deposit
 * withdrawal already accepts for the early-withdrawal charge. Like Fixed and Recurring Deposit, reinvesting is only
 * offered on a maturity closure.
 */
@Component
public class DynamicDepositAccountClosureDataValidator {

    /** Exactly what Fixed and Recurring Deposit closure accepts. */
    private static final Set<String> DYNAMIC_DEPOSIT_ACCOUNT_CLOSE_REQUEST_DATA_PARAMETERS = new HashSet<>(
            Arrays.asList(DepositsApiConstants.localeParamName, DepositsApiConstants.dateFormatParamName, closedOnDateParamName,
                    DepositsApiConstants.noteParamName, onAccountClosureIdParamName, paymentTypeIdParamName,
                    transactionAccountNumberParamName, checkNumberParamName, routingCodeParamName, receiptNumberParamName,
                    bankNumberParamName, DepositsApiConstants.transferDescriptionParamName, toSavingsAccountIdParamName));

    /** The closure parameters plus the override of the early-withdrawal charge percentage. */
    private static final Set<String> DYNAMIC_DEPOSIT_ACCOUNT_PRE_MATURE_CLOSE_REQUEST_DATA_PARAMETERS = createPreMatureCloseParameters();

    private static Set<String> createPreMatureCloseParameters() {
        final Set<String> parameters = new HashSet<>(DYNAMIC_DEPOSIT_ACCOUNT_CLOSE_REQUEST_DATA_PARAMETERS);
        parameters.add(EARLY_WITHDRAWAL_CHARGE_PERCENTAGE);
        return parameters;
    }

    private static final BigDecimal ONE_HUNDRED = BigDecimal.valueOf(100);

    private final FromJsonHelper fromApiJsonHelper;

    public DynamicDepositAccountClosureDataValidator(final FromJsonHelper fromApiJsonHelper) {
        this.fromApiJsonHelper = fromApiJsonHelper;
    }

    public void validateClosing(final JsonCommand command, final boolean isPreMatureClose) {
        final String json = command.json();

        if (StringUtils.isBlank(json)) {
            throw new InvalidJsonException();
        }

        final Type typeOfMap = new TypeToken<Map<String, Object>>() {}.getType();
        this.fromApiJsonHelper.checkForUnsupportedParameters(typeOfMap, json,
                isPreMatureClose ? DYNAMIC_DEPOSIT_ACCOUNT_PRE_MATURE_CLOSE_REQUEST_DATA_PARAMETERS
                        : DYNAMIC_DEPOSIT_ACCOUNT_CLOSE_REQUEST_DATA_PARAMETERS);

        final List<ApiParameterError> dataValidationErrors = new ArrayList<>();
        final DataValidatorBuilder baseDataValidator = new DataValidatorBuilder(dataValidationErrors)
                .resource(DYNAMIC_DEPOSIT_ACCOUNT_RESOURCE_NAME);

        final JsonElement element = command.parsedJson();

        final LocalDate closedOnDate = this.fromApiJsonHelper.extractLocalDateNamed(closedOnDateParamName, element);
        baseDataValidator.reset().parameter(closedOnDateParamName).value(closedOnDate).notNull();

        final Integer onAccountClosureId = this.fromApiJsonHelper.extractIntegerSansLocaleNamed(onAccountClosureIdParamName, element);
        baseDataValidator.reset().parameter(onAccountClosureIdParamName).value(onAccountClosureId).notBlank()
                .isOneOfTheseValues(DepositAccountOnClosureType.integerValues());

        if (onAccountClosureId != null && isKnownClosureType(onAccountClosureId)) {
            final DepositAccountOnClosureType closureType = DepositAccountOnClosureType.fromInt(onAccountClosureId);
            if (closureType.isTransferToSavings()) {
                validateTransferDestination(command, baseDataValidator, element);
            } else if (closureType.isReinvest() && isPreMatureClose) {
                baseDataValidator.reset().parameter(onAccountClosureIdParamName).value(onAccountClosureId)
                        .failWithCode("reinvest.not.allowed", "Re-Invest is not supported for account pre mature close");
            }
        }

        if (this.fromApiJsonHelper.parameterExists(EARLY_WITHDRAWAL_CHARGE_PERCENTAGE, element)) {
            final BigDecimal percentage = this.fromApiJsonHelper.extractBigDecimalWithLocaleNamed(EARLY_WITHDRAWAL_CHARGE_PERCENTAGE,
                    element);
            baseDataValidator.reset().parameter(EARLY_WITHDRAWAL_CHARGE_PERCENTAGE).value(percentage).notNull()
                    .inMinAndMaxAmountRange(BigDecimal.ZERO, ONE_HUNDRED);
        }

        final Integer paymentTypeId = this.fromApiJsonHelper.extractIntegerWithLocaleNamed(paymentTypeIdParamName, element);
        baseDataValidator.reset().parameter(paymentTypeIdParamName).value(paymentTypeId).ignoreIfNull().integerGreaterThanZero();
        for (final String paymentDetailParameterName : Arrays.asList(transactionAccountNumberParamName, checkNumberParamName,
                routingCodeParamName, receiptNumberParamName, bankNumberParamName)) {
            final String paymentDetailParameterValue = this.fromApiJsonHelper.extractStringNamed(paymentDetailParameterName, element);
            baseDataValidator.reset().parameter(paymentDetailParameterName).value(paymentDetailParameterValue).ignoreIfNull()
                    .notExceedingLengthOf(50);
        }

        if (!dataValidationErrors.isEmpty()) {
            throw new PlatformApiDataValidationException("validation.msg.validation.errors.exist", "Validation errors exist.",
                    dataValidationErrors);
        }
    }

    private void validateTransferDestination(final JsonCommand command, final DataValidatorBuilder baseDataValidator,
            final JsonElement element) {
        final Long toSavingsAccountId = this.fromApiJsonHelper.extractLongNamed(toSavingsAccountIdParamName, element);
        baseDataValidator.reset().parameter(toSavingsAccountIdParamName).value(toSavingsAccountId).notNull();
        if (toSavingsAccountId != null && toSavingsAccountId.equals(command.getSavingsId())) {
            baseDataValidator.reset().parameter(toSavingsAccountIdParamName).value(toSavingsAccountId).failWithCode(
                    "same.as.account.being.closed", "The destination of a closure transfer cannot be the account being closed");
        }
    }

    private static boolean isKnownClosureType(final Integer onAccountClosureId) {
        return Arrays.asList(DepositAccountOnClosureType.integerValues()).contains(onAccountClosureId);
    }
}
