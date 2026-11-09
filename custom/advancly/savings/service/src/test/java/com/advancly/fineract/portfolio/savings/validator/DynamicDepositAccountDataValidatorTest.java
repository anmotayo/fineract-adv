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

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.apache.fineract.infrastructure.core.exception.PlatformApiDataValidationException;
import org.apache.fineract.infrastructure.core.serialization.FromJsonHelper;
import org.junit.jupiter.api.Test;

/** Transferring interest requires a linked account, independent of {@code allowWithdrawal}. */
class DynamicDepositAccountDataValidatorTest {

    private final DynamicDepositAccountDataValidator validator = new DynamicDepositAccountDataValidator(new FromJsonHelper());

    @Test
    void throwsWhenTransferInterestEnabledWithoutALinkedAccount() {
        assertThatThrownBy(() -> validator.validateLinkedAccountRequiredWhenTransferInterestEnabled(true, null))
                .isInstanceOf(PlatformApiDataValidationException.class);
    }

    @Test
    void doesNotThrowWhenTransferInterestEnabledWithAValidLinkedAccount() {
        assertThatCode(() -> validator.validateLinkedAccountRequiredWhenTransferInterestEnabled(true, 9L)).doesNotThrowAnyException();
    }

    @Test
    void doesNotThrowWhenTransferInterestDisabledRegardlessOfLinkedAccount() {
        assertThatCode(() -> validator.validateLinkedAccountRequiredWhenTransferInterestEnabled(false, null)).doesNotThrowAnyException();
        assertThatCode(() -> validator.validateLinkedAccountRequiredWhenTransferInterestEnabled(false, 9L)).doesNotThrowAnyException();
    }

    @Test
    void allowWithdrawalHasNoBearingOnThisRule_regressionForTheRemovedCoupling() {
        assertThatCode(() -> validator.validateLinkedAccountRequiredWhenTransferInterestEnabled(true, 9L)).doesNotThrowAnyException();
    }
}
