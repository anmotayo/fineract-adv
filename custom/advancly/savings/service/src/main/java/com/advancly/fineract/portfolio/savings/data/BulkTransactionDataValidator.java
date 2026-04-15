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
package com.advancly.fineract.portfolio.savings.data;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.apache.fineract.infrastructure.core.data.ApiParameterError;
import org.apache.fineract.infrastructure.core.data.DataValidatorBuilder;
import org.apache.fineract.infrastructure.core.exception.InvalidJsonException;
import org.apache.fineract.infrastructure.core.exception.PlatformApiDataValidationException;
import org.apache.fineract.infrastructure.core.serialization.FromJsonHelper;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class BulkTransactionDataValidator {

    private static final String RESOURCE_NAME = "savingsAccountBulkTransaction";
    private static final Set<String> VALID_TYPES = Set.of("deposit", "withdrawal");

    private final FromJsonHelper fromApiJsonHelper;

    public void validate(final String json) {
        if (json == null || json.isBlank()) {
            throw new InvalidJsonException();
        }

        final JsonElement element = fromApiJsonHelper.parse(json);
        final List<ApiParameterError> dataValidationErrors = new ArrayList<>();
        final DataValidatorBuilder baseDataValidator = new DataValidatorBuilder(dataValidationErrors).resource(RESOURCE_NAME);

        final JsonObject rootJson = element.getAsJsonObject();
        final String dateFormat = fromApiJsonHelper.extractDateFormatParameter(rootJson);
        baseDataValidator.reset().parameter("dateFormat").value(dateFormat).notBlank();

        final String localeStr = fromApiJsonHelper.extractStringNamed("locale", element);
        baseDataValidator.reset().parameter("locale").value(localeStr).notBlank();

        final JsonArray transactions = fromApiJsonHelper.extractJsonArrayNamed("transactions", element);
        baseDataValidator.reset().parameter("transactions").value(transactions).notNull();

        if (transactions == null || transactions.isEmpty()) {
            baseDataValidator.reset().parameter("transactions").value(transactions == null ? null : transactions.size()).notNull()
                    .integerGreaterThanZero();
            throwExceptionIfValidationWarningsExist(dataValidationErrors);
            return;
        }

        final java.util.Locale locale = fromApiJsonHelper.extractLocaleParameter(rootJson);
        final Set<String> seenReceiptNumbers = new HashSet<>();

        for (int i = 0; i < transactions.size(); i++) {
            final JsonObject txn = transactions.get(i).getAsJsonObject();

            final String type = fromApiJsonHelper.extractStringNamed("type", txn);
            baseDataValidator.reset().parameter("type").value(type).notBlank();
            if (type != null && !VALID_TYPES.contains(type)) {
                baseDataValidator.reset().parameter("type").value(type).isOneOfTheseStringValues("deposit", "withdrawal");
            }

            final String transactionDate = fromApiJsonHelper.extractStringNamed("transactionDate", txn);
            baseDataValidator.reset().parameter("transactionDate").value(transactionDate).notBlank();

            final BigDecimal transactionAmount = locale != null ? fromApiJsonHelper.extractBigDecimalNamed("transactionAmount", txn, locale)
                    : fromApiJsonHelper.extractBigDecimalWithLocaleNamed("transactionAmount", txn);
            baseDataValidator.reset().parameter("transactionAmount").value(transactionAmount).notNull().positiveAmount();

            final Long paymentTypeId = fromApiJsonHelper.extractLongNamed("paymentTypeId", txn);
            baseDataValidator.reset().parameter("paymentTypeId").value(paymentTypeId).notNull().longGreaterThanZero();

            final String receiptNumber = fromApiJsonHelper.extractStringNamed("receiptNumber", txn);
            if (receiptNumber != null && !receiptNumber.isBlank()) {
                if (!seenReceiptNumbers.add(receiptNumber)) {
                    baseDataValidator.reset().parameter("receiptNumber").value(receiptNumber).failWithCode("duplicate.receipt.number");
                }
            }
        }

        throwExceptionIfValidationWarningsExist(dataValidationErrors);
    }

    private void throwExceptionIfValidationWarningsExist(final List<ApiParameterError> dataValidationErrors) {
        if (!dataValidationErrors.isEmpty()) {
            throw new PlatformApiDataValidationException(dataValidationErrors);
        }
    }
}
