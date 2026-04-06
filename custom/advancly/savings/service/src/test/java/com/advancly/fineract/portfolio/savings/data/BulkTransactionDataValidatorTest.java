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

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.apache.fineract.infrastructure.core.exception.InvalidJsonException;
import org.apache.fineract.infrastructure.core.exception.PlatformApiDataValidationException;
import org.apache.fineract.infrastructure.core.serialization.FromJsonHelper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class BulkTransactionDataValidatorTest {

    private BulkTransactionDataValidator validator;

    @BeforeEach
    void setUp() {
        validator = new BulkTransactionDataValidator(new FromJsonHelper());
    }

    @Test
    void testValidPayload() {
        String json = buildValidPayload();
        assertThatCode(() -> validator.validate(json)).doesNotThrowAnyException();
    }

    @Test
    void testEmptyTransactionsArray() {
        JsonObject payload = new JsonObject();
        payload.addProperty("dateFormat", "dd MMMM yyyy");
        payload.addProperty("locale", "en");
        payload.add("transactions", new JsonArray());

        assertThatThrownBy(() -> validator.validate(payload.toString())).isInstanceOf(PlatformApiDataValidationException.class);
    }

    @Test
    void testMissingTransactionsArray() {
        JsonObject payload = new JsonObject();
        payload.addProperty("dateFormat", "dd MMMM yyyy");
        payload.addProperty("locale", "en");

        assertThatThrownBy(() -> validator.validate(payload.toString())).isInstanceOf(PlatformApiDataValidationException.class);
    }

    @Test
    void testInvalidType() {
        JsonObject payload = new JsonObject();
        payload.addProperty("dateFormat", "dd MMMM yyyy");
        payload.addProperty("locale", "en");
        JsonArray txns = new JsonArray();
        JsonObject txn = new JsonObject();
        txn.addProperty("type", "transfer");
        txn.addProperty("transactionDate", "05 April 2026");
        txn.addProperty("transactionAmount", 1000);
        txn.addProperty("paymentTypeId", 1);
        txn.addProperty("receiptNumber", "REC-001");
        txns.add(txn);
        payload.add("transactions", txns);

        assertThatThrownBy(() -> validator.validate(payload.toString())).isInstanceOf(PlatformApiDataValidationException.class);
    }

    @Test
    void testDuplicateReceiptNumbers() {
        JsonObject payload = new JsonObject();
        payload.addProperty("dateFormat", "dd MMMM yyyy");
        payload.addProperty("locale", "en");
        JsonArray txns = new JsonArray();
        txns.add(buildTransaction("deposit", "REC-001", 1000));
        txns.add(buildTransaction("withdrawal", "REC-001", 500));
        payload.add("transactions", txns);

        assertThatThrownBy(() -> validator.validate(payload.toString())).isInstanceOf(PlatformApiDataValidationException.class);
    }

    @Test
    void testMissingReceiptNumber_isOptional() {
        JsonObject payload = new JsonObject();
        payload.addProperty("dateFormat", "dd MMMM yyyy");
        payload.addProperty("locale", "en");
        JsonArray txns = new JsonArray();
        JsonObject txn = new JsonObject();
        txn.addProperty("type", "deposit");
        txn.addProperty("transactionDate", "05 April 2026");
        txn.addProperty("transactionAmount", 1000);
        txn.addProperty("paymentTypeId", 1);
        txns.add(txn);
        payload.add("transactions", txns);

        // receiptNumber is optional — validation should pass without it
        assertThatCode(() -> validator.validate(payload.toString())).doesNotThrowAnyException();
    }

    @Test
    void testMissingTransactionAmount() {
        JsonObject payload = new JsonObject();
        payload.addProperty("dateFormat", "dd MMMM yyyy");
        payload.addProperty("locale", "en");
        JsonArray txns = new JsonArray();
        JsonObject txn = new JsonObject();
        txn.addProperty("type", "deposit");
        txn.addProperty("transactionDate", "05 April 2026");
        txn.addProperty("paymentTypeId", 1);
        txn.addProperty("receiptNumber", "REC-001");
        txns.add(txn);
        payload.add("transactions", txns);

        assertThatThrownBy(() -> validator.validate(payload.toString())).isInstanceOf(PlatformApiDataValidationException.class);
    }

    @Test
    void testNullJson() {
        assertThatThrownBy(() -> validator.validate(null)).isInstanceOf(InvalidJsonException.class);
    }

    private String buildValidPayload() {
        JsonObject payload = new JsonObject();
        payload.addProperty("dateFormat", "dd MMMM yyyy");
        payload.addProperty("locale", "en");
        JsonArray txns = new JsonArray();
        txns.add(buildTransaction("deposit", "REC-001", 5000));
        txns.add(buildTransaction("withdrawal", "REC-002", 2000));
        payload.add("transactions", txns);
        return payload.toString();
    }

    private JsonObject buildTransaction(String type, String receiptNumber, int amount) {
        JsonObject txn = new JsonObject();
        txn.addProperty("type", type);
        txn.addProperty("transactionDate", "05 April 2026");
        txn.addProperty("transactionAmount", amount);
        txn.addProperty("paymentTypeId", 1);
        txn.addProperty("receiptNumber", receiptNumber);
        return txn;
    }
}
