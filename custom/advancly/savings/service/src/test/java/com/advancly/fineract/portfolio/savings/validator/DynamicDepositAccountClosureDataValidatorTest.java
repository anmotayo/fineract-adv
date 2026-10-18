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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.List;
import org.apache.fineract.infrastructure.core.api.JsonCommand;
import org.apache.fineract.infrastructure.core.data.ApiParameterError;
import org.apache.fineract.infrastructure.core.exception.InvalidJsonException;
import org.apache.fineract.infrastructure.core.exception.PlatformApiDataValidationException;
import org.apache.fineract.infrastructure.core.exception.UnsupportedParameterException;
import org.apache.fineract.infrastructure.core.serialization.FromJsonHelper;
import org.junit.jupiter.api.Test;

/**
 * Closing a Dynamic Deposit takes the same request shape as closing a Fixed or Recurring Deposit
 * ({@code onAccountClosureId}, {@code toSavingsAccountId}, ...), plus the early-withdrawal charge overrides the Dynamic
 * Deposit withdrawal already accepts.
 */
class DynamicDepositAccountClosureDataValidatorTest {

    private static final Long ACCOUNT_ID = 7L;
    private static final int WITHDRAW = 100;
    private static final int TRANSFER_TO_SAVINGS = 200;
    private static final int REINVEST_PRINCIPAL_AND_INTEREST = 300;
    private static final int REINVEST_PRINCIPAL_ONLY = 400;

    private final FromJsonHelper fromJsonHelper = new FromJsonHelper();
    private final DynamicDepositAccountClosureDataValidator validator = new DynamicDepositAccountClosureDataValidator(fromJsonHelper);

    @Test
    void acceptsWithdrawOnPrematureClose() {
        assertThatCode(() -> validator.validateClosing(command(payload(WITHDRAW)), true)).doesNotThrowAnyException();
    }

    @Test
    void acceptsTransferToAnotherSavingsAccountOnPrematureClose() {
        final JsonObject payload = payload(TRANSFER_TO_SAVINGS);
        payload.addProperty("toSavingsAccountId", 99L);
        payload.addProperty("transferDescription", "premature closure");

        assertThatCode(() -> validator.validateClosing(command(payload), true)).doesNotThrowAnyException();
    }

    @Test
    void acceptsEveryClosureTypeOnMaturityClose() {
        final JsonObject transfer = payload(TRANSFER_TO_SAVINGS);
        transfer.addProperty("toSavingsAccountId", 99L);

        assertThatCode(() -> validator.validateClosing(command(payload(WITHDRAW)), false)).doesNotThrowAnyException();
        assertThatCode(() -> validator.validateClosing(command(transfer), false)).doesNotThrowAnyException();
        assertThatCode(() -> validator.validateClosing(command(payload(REINVEST_PRINCIPAL_ONLY)), false)).doesNotThrowAnyException();
        assertThatCode(() -> validator.validateClosing(command(payload(REINVEST_PRINCIPAL_AND_INTEREST)), false))
                .doesNotThrowAnyException();
    }

    @Test
    void acceptsTheEarlyWithdrawalChargeOverrides() {
        final JsonObject payload = payload(WITHDRAW);
        payload.addProperty("applyEarlyWithdrawalCharge", true);
        payload.addProperty("earlyWithdrawalChargePercentage", 25);

        assertThatCode(() -> validator.validateClosing(command(payload), true)).doesNotThrowAnyException();
    }

    @Test
    void rejectsReinvestOnPrematureClose() {
        assertThat(errorCodes(() -> validator.validateClosing(command(payload(REINVEST_PRINCIPAL_ONLY)), true)))
                .containsExactly("validation.msg.dynamicdepositaccount.onAccountClosureId.reinvest.not.allowed");
        assertThat(errorCodes(() -> validator.validateClosing(command(payload(REINVEST_PRINCIPAL_AND_INTEREST)), true)))
                .containsExactly("validation.msg.dynamicdepositaccount.onAccountClosureId.reinvest.not.allowed");
    }

    @Test
    void requiresTheDestinationAccountWhenTransferring() {
        assertThat(errorCodes(() -> validator.validateClosing(command(payload(TRANSFER_TO_SAVINGS)), true)))
                .containsExactly("validation.msg.dynamicdepositaccount.toSavingsAccountId.cannot.be.blank");
    }

    @Test
    void rejectsTransferringToTheAccountBeingClosed() {
        final JsonObject payload = payload(TRANSFER_TO_SAVINGS);
        payload.addProperty("toSavingsAccountId", ACCOUNT_ID);

        assertThat(errorCodes(() -> validator.validateClosing(command(payload), true)))
                .containsExactly("validation.msg.dynamicdepositaccount.toSavingsAccountId.same.as.account.being.closed");
    }

    @Test
    void requiresTheClosureType() {
        final JsonObject payload = payload(WITHDRAW);
        payload.remove("onAccountClosureId");

        assertThat(errorCodes(() -> validator.validateClosing(command(payload), true)))
                .contains("validation.msg.dynamicdepositaccount.onAccountClosureId.cannot.be.blank");
    }

    @Test
    void rejectsAnUnknownClosureType() {
        assertThat(errorCodes(() -> validator.validateClosing(command(payload(999)), true)))
                .contains("validation.msg.dynamicdepositaccount.onAccountClosureId.is.not.one.of.expected.enumerations");
    }

    @Test
    void requiresTheClosedOnDate() {
        final JsonObject payload = payload(WITHDRAW);
        payload.remove("closedOnDate");

        assertThat(errorCodes(() -> validator.validateClosing(command(payload), true)))
                .containsExactly("validation.msg.dynamicdepositaccount.closedOnDate.cannot.be.blank");
    }

    @Test
    void rejectsAnEarlyWithdrawalChargePercentageOutsideZeroToOneHundred() {
        final JsonObject payload = payload(WITHDRAW);
        payload.addProperty("earlyWithdrawalChargePercentage", 101);

        assertThat(errorCodes(() -> validator.validateClosing(command(payload), true)))
                .containsExactly("validation.msg.dynamicdepositaccount.earlyWithdrawalChargePercentage.amount.is.not.within.min.max.range");
    }

    @Test
    void rejectsParametersTheClosureDoesNotKnow() {
        final JsonObject payload = payload(WITHDRAW);
        payload.addProperty("withdrawBalance", true);

        assertThatThrownBy(() -> validator.validateClosing(command(payload), true)).isInstanceOf(UnsupportedParameterException.class);
    }

    @Test
    void rejectsABlankBody() {
        assertThatThrownBy(() -> validator.validateClosing(commandFromJson(""), true)).isInstanceOf(InvalidJsonException.class);
    }

    private static JsonObject payload(final int onAccountClosureId) {
        final JsonObject payload = new JsonObject();
        payload.addProperty("dateFormat", "dd MMMM yyyy");
        payload.addProperty("locale", "en");
        payload.addProperty("closedOnDate", "20 January 2026");
        payload.addProperty("onAccountClosureId", onAccountClosureId);
        return payload;
    }

    private JsonCommand command(final JsonObject payload) {
        return commandFromJson(payload.toString());
    }

    private JsonCommand commandFromJson(final String json) {
        return JsonCommand.fromExistingCommand(null, json, json.isEmpty() ? null : JsonParser.parseString(json), fromJsonHelper, null, null,
                null, null, null, null, ACCOUNT_ID, null, null, null, null, null, null, null);
    }

    private static List<String> errorCodes(final Runnable action) {
        try {
            action.run();
        } catch (final PlatformApiDataValidationException e) {
            return e.getErrors().stream().map(ApiParameterError::getUserMessageGlobalisationCode).toList();
        }
        throw new AssertionError("Expected a PlatformApiDataValidationException");
    }
}
