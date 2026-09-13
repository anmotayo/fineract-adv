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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import com.google.gson.JsonParser;
import java.util.Set;
import org.apache.fineract.infrastructure.core.api.JsonCommand;
import org.apache.fineract.infrastructure.core.serialization.FromJsonHelper;
import org.apache.fineract.portfolio.charge.domain.Charge;
import org.apache.fineract.portfolio.charge.domain.ChargeCalculationType;
import org.apache.fineract.portfolio.charge.domain.ChargeRepositoryWrapper;
import org.apache.fineract.portfolio.charge.exception.ChargeCannotBeAppliedToException;
import org.apache.fineract.portfolio.savings.DepositAccountType;
import org.apache.fineract.portfolio.tax.domain.TaxGroupRepositoryWrapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Tests the Dynamic-Deposit-only gate on {@link SavingsProductBaseAssembler#assembleListOfSavingsProductCharges} added
 * in Phase 4 (interest-based charges): a charge whose calculation type is {@code PERCENT_OF_INTEREST} may only be
 * attached to a Dynamic Deposit product; attaching it to any other savings product type must fail validation.
 * {@code PERCENT_OF_AMOUNT_AND_INTEREST} is intentionally out of scope for savings charges altogether (it can become
 * principal-based unless capped/carry-forward rules are added) and must be rejected for every savings product type,
 * Dynamic Deposit included.
 */
@ExtendWith(MockitoExtension.class)
class SavingsProductBaseAssemblerTest {

    private static final String CURRENCY_CODE = "USD";
    private static final Long CHARGE_ID = 55L;

    @Mock
    private ChargeRepositoryWrapper chargeRepository;

    @Mock
    private TaxGroupRepositoryWrapper taxGroupRepository;

    private SavingsProductBaseAssembler assembler;
    private FromJsonHelper fromApiJsonHelper;

    @BeforeEach
    void setUp() {
        assembler = new SavingsProductBaseAssembler(chargeRepository, taxGroupRepository);
        fromApiJsonHelper = new FromJsonHelper();
    }

    @Test
    void percentOfInterestPenaltyCharge_onDynamicDepositProduct_isAllowed() {
        final Charge interestBasedCharge = mockCharge(ChargeCalculationType.PERCENT_OF_INTEREST);
        when(chargeRepository.findOneWithNotFoundDetection(CHARGE_ID)).thenReturn(interestBasedCharge);

        final JsonCommand command = commandWithChargeId(CHARGE_ID);

        final Set<Charge> charges = assembler.assembleListOfSavingsProductCharges(command, CURRENCY_CODE, "charges",
                DepositAccountType.DYNAMIC_DEPOSIT);

        assertThat(charges).containsExactly(interestBasedCharge);
    }

    @Test
    void percentOfInterestPenaltyCharge_onPlainSavingsProduct_isRejected() {
        final Charge interestBasedCharge = mockCharge(ChargeCalculationType.PERCENT_OF_INTEREST);
        when(chargeRepository.findOneWithNotFoundDetection(CHARGE_ID)).thenReturn(interestBasedCharge);

        final JsonCommand command = commandWithChargeId(CHARGE_ID);

        assertThatThrownBy(
                () -> assembler.assembleListOfSavingsProductCharges(command, CURRENCY_CODE, "charges", DepositAccountType.SAVINGS_DEPOSIT))
                .isInstanceOf(ChargeCannotBeAppliedToException.class);
    }

    @Test
    void percentOfInterestPenaltyCharge_onFixedDepositProduct_isRejected() {
        final Charge interestBasedCharge = mockCharge(ChargeCalculationType.PERCENT_OF_INTEREST);
        when(chargeRepository.findOneWithNotFoundDetection(CHARGE_ID)).thenReturn(interestBasedCharge);

        final JsonCommand command = commandWithChargeId(CHARGE_ID);

        assertThatThrownBy(
                () -> assembler.assembleListOfSavingsProductCharges(command, CURRENCY_CODE, "charges", DepositAccountType.FIXED_DEPOSIT))
                .isInstanceOf(ChargeCannotBeAppliedToException.class);
    }

    @Test
    void percentOfInterestPenaltyCharge_onRecurringDepositProduct_isRejected() {
        final Charge interestBasedCharge = mockCharge(ChargeCalculationType.PERCENT_OF_INTEREST);
        when(chargeRepository.findOneWithNotFoundDetection(CHARGE_ID)).thenReturn(interestBasedCharge);

        final JsonCommand command = commandWithChargeId(CHARGE_ID);

        assertThatThrownBy(() -> assembler.assembleListOfSavingsProductCharges(command, CURRENCY_CODE, "charges",
                DepositAccountType.RECURRING_DEPOSIT)).isInstanceOf(ChargeCannotBeAppliedToException.class);
    }

    @Test
    void percentOfAmountAndInterestCharge_onDynamicDepositProduct_isRejected() {
        // PERCENT_OF_AMOUNT_AND_INTEREST is out of scope for savings charges entirely - not even Dynamic Deposit
        // may use it.
        final Charge unsupportedCharge = mockCharge(ChargeCalculationType.PERCENT_OF_AMOUNT_AND_INTEREST);
        when(chargeRepository.findOneWithNotFoundDetection(CHARGE_ID)).thenReturn(unsupportedCharge);

        final JsonCommand command = commandWithChargeId(CHARGE_ID);

        assertThatThrownBy(
                () -> assembler.assembleListOfSavingsProductCharges(command, CURRENCY_CODE, "charges", DepositAccountType.DYNAMIC_DEPOSIT))
                .isInstanceOf(ChargeCannotBeAppliedToException.class);
    }

    @Test
    void percentOfAmountAndInterestCharge_onPlainSavingsProduct_isRejected() {
        final Charge unsupportedCharge = mockCharge(ChargeCalculationType.PERCENT_OF_AMOUNT_AND_INTEREST);
        when(chargeRepository.findOneWithNotFoundDetection(CHARGE_ID)).thenReturn(unsupportedCharge);

        final JsonCommand command = commandWithChargeId(CHARGE_ID);

        assertThatThrownBy(
                () -> assembler.assembleListOfSavingsProductCharges(command, CURRENCY_CODE, "charges", DepositAccountType.SAVINGS_DEPOSIT))
                .isInstanceOf(ChargeCannotBeAppliedToException.class);
    }

    @Test
    void flatCharge_onPlainSavingsProduct_isStillAllowed() {
        final Charge flatCharge = mockCharge(ChargeCalculationType.FLAT);
        when(chargeRepository.findOneWithNotFoundDetection(CHARGE_ID)).thenReturn(flatCharge);

        final JsonCommand command = commandWithChargeId(CHARGE_ID);

        final Set<Charge> charges = assembler.assembleListOfSavingsProductCharges(command, CURRENCY_CODE, "charges",
                DepositAccountType.SAVINGS_DEPOSIT);

        assertThat(charges).containsExactly(flatCharge);
    }

    private Charge mockCharge(final ChargeCalculationType calculationType) {
        final Charge charge = org.mockito.Mockito.mock(Charge.class);
        // getId() and getChargeCalculation() are only reached on some code paths (error-message construction, and
        // the interest-based check is short-circuited away entirely for Dynamic Deposit) - stub leniently so every
        // test can share this helper without tripping Mockito's strict-stub checks.
        org.mockito.Mockito.lenient().when(charge.getId()).thenReturn(CHARGE_ID);
        when(charge.isSavingsCharge()).thenReturn(true);
        when(charge.getCurrencyCode()).thenReturn(CURRENCY_CODE);
        org.mockito.Mockito.lenient().when(charge.getChargeCalculation()).thenReturn(calculationType.getValue());
        return charge;
    }

    private JsonCommand commandWithChargeId(final Long chargeId) {
        final String json = "{\"charges\":[{\"id\":" + chargeId + "}]}";
        return JsonCommand.fromExistingCommand(null, json, JsonParser.parseString(json), fromApiJsonHelper, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null);
    }
}
