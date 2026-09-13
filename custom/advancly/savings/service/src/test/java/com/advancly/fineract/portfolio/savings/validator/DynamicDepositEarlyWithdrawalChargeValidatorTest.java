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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

import com.advancly.fineract.portfolio.savings.domain.DepositProductEarlyWithdrawalCharge;
import java.util.List;
import java.util.Set;
import org.apache.fineract.infrastructure.core.exception.PlatformApiDataValidationException;
import org.apache.fineract.portfolio.charge.domain.Charge;
import org.apache.fineract.portfolio.charge.domain.ChargeCalculationType;
import org.junit.jupiter.api.Test;

class DynamicDepositEarlyWithdrawalChargeValidatorTest {

    @Test
    void resolvesTheAttachedActivePercentOfInterestPenaltyCharge() {
        final Charge charge = charge(7L, true, true, ChargeCalculationType.PERCENT_OF_INTEREST);

        final Charge resolved = DynamicDepositEarlyWithdrawalChargeValidator.validateAndResolve(true, 7L, Set.of(charge));

        assertThat(resolved).isSameAs(charge);
    }

    @Test
    void returnsNullWhenThePenaltyIsDisabledAndNoChargeIsSelected() {
        assertThat(DynamicDepositEarlyWithdrawalChargeValidator.validateAndResolve(false, null, Set.of())).isNull();
    }

    @Test
    void rejectsAChargeSelectedWhileThePenaltyIsDisabled() {
        final Charge charge = charge(7L, true, true, ChargeCalculationType.PERCENT_OF_INTEREST);

        assertThatThrownBy(() -> DynamicDepositEarlyWithdrawalChargeValidator.validateAndResolve(false, 7L, Set.of(charge)))
                .isInstanceOf(PlatformApiDataValidationException.class)
                .satisfies(exception -> assertThat(exception.toString()).contains("early.withdrawal.charge.not.allowed.when.disabled"));
    }

    @Test
    void rejectsEnablingThePenaltyWithoutSelectingACharge() {
        assertThatThrownBy(() -> DynamicDepositEarlyWithdrawalChargeValidator.validateAndResolve(true, null, Set.of()))
                .isInstanceOf(PlatformApiDataValidationException.class)
                .satisfies(exception -> assertThat(exception.toString()).contains("early.withdrawal.charge.required"));
    }

    @Test
    void rejectsAChargeThatIsNotAttachedToTheProduct() {
        final Charge attached = charge(7L, true, true, ChargeCalculationType.PERCENT_OF_INTEREST);

        assertThatThrownBy(() -> DynamicDepositEarlyWithdrawalChargeValidator.validateAndResolve(true, 99L, Set.of(attached)))
                .isInstanceOf(PlatformApiDataValidationException.class)
                .satisfies(exception -> assertThat(exception.toString()).contains("early.withdrawal.charge.not.attached.to.product"));
    }

    @Test
    void rejectsAnInactiveCharge() {
        final Charge charge = charge(7L, false, true, ChargeCalculationType.PERCENT_OF_INTEREST);

        assertThatThrownBy(() -> DynamicDepositEarlyWithdrawalChargeValidator.validateAndResolve(true, 7L, Set.of(charge)))
                .isInstanceOf(PlatformApiDataValidationException.class)
                .satisfies(exception -> assertThat(exception.toString()).contains("early.withdrawal.charge.not.active"));
    }

    @Test
    void rejectsANonPenaltyCharge() {
        final Charge charge = charge(7L, true, false, ChargeCalculationType.PERCENT_OF_INTEREST);

        assertThatThrownBy(() -> DynamicDepositEarlyWithdrawalChargeValidator.validateAndResolve(true, 7L, Set.of(charge)))
                .isInstanceOf(PlatformApiDataValidationException.class)
                .satisfies(exception -> assertThat(exception.toString()).contains("early.withdrawal.charge.not.penalty"));
    }

    @Test
    void rejectsAChargeThatIsNotPercentOfInterest() {
        final Charge charge = charge(7L, true, true, ChargeCalculationType.FLAT);

        assertThatThrownBy(() -> DynamicDepositEarlyWithdrawalChargeValidator.validateAndResolve(true, 7L, Set.of(charge)))
                .isInstanceOf(PlatformApiDataValidationException.class)
                .satisfies(exception -> assertThat(exception.toString()).contains("early.withdrawal.charge.not.percent.of.interest"));
    }

    @Test
    void rejectsAProductThatAlreadyHasMoreThanOneEarlyWithdrawalCharge() {
        final List<DepositProductEarlyWithdrawalCharge> rows = List.of(DepositProductEarlyWithdrawalCharge.createNew(1L, 7L),
                DepositProductEarlyWithdrawalCharge.createNew(1L, 8L));

        assertThatThrownBy(() -> DynamicDepositEarlyWithdrawalChargeValidator.validateAtMostOneActiveCharge(rows))
                .isInstanceOf(PlatformApiDataValidationException.class)
                .satisfies(exception -> assertThat(exception.toString()).contains("early.withdrawal.charge.must.be.unique.per.product"));
    }

    @Test
    void acceptsAProductWithZeroOrOneEarlyWithdrawalCharge() {
        DynamicDepositEarlyWithdrawalChargeValidator.validateAtMostOneActiveCharge(List.of());
        DynamicDepositEarlyWithdrawalChargeValidator
                .validateAtMostOneActiveCharge(List.of(DepositProductEarlyWithdrawalCharge.createNew(1L, 7L)));
    }

    private Charge charge(final Long id, final boolean active, final boolean penalty, final ChargeCalculationType calculationType) {
        final Charge charge = mock(Charge.class);
        lenient().when(charge.getId()).thenReturn(id);
        lenient().when(charge.isActive()).thenReturn(active);
        lenient().when(charge.isPenalty()).thenReturn(penalty);
        lenient().when(charge.getChargeCalculation()).thenReturn(calculationType.getValue());
        return charge;
    }
}
