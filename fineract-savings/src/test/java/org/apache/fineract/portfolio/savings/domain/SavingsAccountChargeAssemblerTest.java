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
package org.apache.fineract.portfolio.savings.domain;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.apache.fineract.portfolio.charge.domain.Charge;
import org.apache.fineract.portfolio.charge.domain.ChargeCalculationType;
import org.apache.fineract.portfolio.charge.exception.ChargeCannotBeAppliedToException;
import org.apache.fineract.portfolio.savings.DepositAccountType;
import org.junit.jupiter.api.Test;

/**
 * Tests {@link SavingsAccountChargeAssembler#validateChargeAllowedForDepositAccountType(Charge, DepositAccountType)} -
 * the account-level counterpart to the Dynamic-Deposit-only gate on
 * {@link SavingsProductBaseAssembler#assembleListOfSavingsProductCharges}. A charge attached directly to an account
 * (rather than via its product) bypasses the product-level gate entirely, so this rule closes that gap: a
 * {@code PERCENT_OF_INTEREST} charge may only be attached to a Dynamic Deposit account, and
 * {@code PERCENT_OF_AMOUNT_AND_INTEREST} is rejected for every savings account type, Dynamic Deposit included.
 */
class SavingsAccountChargeAssemblerTest {

    private static final Long CHARGE_ID = 77L;

    @Test
    void percentOfInterestPenaltyCharge_onDynamicDepositAccount_isAllowed() {
        final Charge charge = mockCharge(ChargeCalculationType.PERCENT_OF_INTEREST);

        assertThatCode(
                () -> SavingsAccountChargeAssembler.validateChargeAllowedForDepositAccountType(charge, DepositAccountType.DYNAMIC_DEPOSIT))
                .doesNotThrowAnyException();
    }

    @Test
    void percentOfInterestPenaltyCharge_onPlainSavingsAccount_isRejected() {
        final Charge charge = mockCharge(ChargeCalculationType.PERCENT_OF_INTEREST);

        assertThatThrownBy(
                () -> SavingsAccountChargeAssembler.validateChargeAllowedForDepositAccountType(charge, DepositAccountType.SAVINGS_DEPOSIT))
                .isInstanceOf(ChargeCannotBeAppliedToException.class);
    }

    @Test
    void percentOfInterestPenaltyCharge_onFixedDepositAccount_isRejected() {
        final Charge charge = mockCharge(ChargeCalculationType.PERCENT_OF_INTEREST);

        assertThatThrownBy(
                () -> SavingsAccountChargeAssembler.validateChargeAllowedForDepositAccountType(charge, DepositAccountType.FIXED_DEPOSIT))
                .isInstanceOf(ChargeCannotBeAppliedToException.class);
    }

    @Test
    void percentOfAmountAndInterestCharge_onDynamicDepositAccount_isRejected() {
        // Out of scope for savings charges entirely - not even Dynamic Deposit may use it.
        final Charge charge = mockCharge(ChargeCalculationType.PERCENT_OF_AMOUNT_AND_INTEREST);

        assertThatThrownBy(
                () -> SavingsAccountChargeAssembler.validateChargeAllowedForDepositAccountType(charge, DepositAccountType.DYNAMIC_DEPOSIT))
                .isInstanceOf(ChargeCannotBeAppliedToException.class);
    }

    @Test
    void flatCharge_onPlainSavingsAccount_isStillAllowed() {
        final Charge charge = mockCharge(ChargeCalculationType.FLAT);

        assertThatCode(
                () -> SavingsAccountChargeAssembler.validateChargeAllowedForDepositAccountType(charge, DepositAccountType.SAVINGS_DEPOSIT))
                .doesNotThrowAnyException();
    }

    private Charge mockCharge(final ChargeCalculationType calculationType) {
        final Charge charge = mock(Charge.class);
        lenient().when(charge.getId()).thenReturn(CHARGE_ID);
        when(charge.getChargeCalculation()).thenReturn(calculationType.getValue());
        return charge;
    }
}
