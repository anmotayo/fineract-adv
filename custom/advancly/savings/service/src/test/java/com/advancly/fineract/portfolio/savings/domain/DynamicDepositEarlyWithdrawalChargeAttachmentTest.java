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
package com.advancly.fineract.portfolio.savings.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

import java.math.BigDecimal;
import java.util.HashSet;
import java.util.Set;
import org.apache.fineract.portfolio.charge.domain.Charge;
import org.apache.fineract.portfolio.charge.domain.ChargeCalculationType;
import org.apache.fineract.portfolio.charge.domain.ChargeTimeType;
import org.apache.fineract.portfolio.savings.domain.SavingsAccountCharge;
import org.junit.jupiter.api.Test;

class DynamicDepositEarlyWithdrawalChargeAttachmentTest {

    @Test
    void theProductEarlyWithdrawalChargeIsAttachedWhenTheRequestDidNotIncludeIt() {
        final Charge earlyWithdrawalCharge = percentOfInterestChargeDefinition(7L, new BigDecimal("5"));

        final Set<SavingsAccountCharge> result = DynamicDepositAccountAssembler.withEarlyWithdrawalCharge(new HashSet<>(),
                earlyWithdrawalCharge);

        assertThat(result).hasSize(1);
        final SavingsAccountCharge attached = result.iterator().next();
        assertThat(attached.getCharge()).isSameAs(earlyWithdrawalCharge);
        assertThat(attached.getPercentage()).isEqualByComparingTo("5");
    }

    @Test
    void anAccountLevelOverrideAlreadyInTheRequestIsLeftUntouched() {
        final Charge earlyWithdrawalCharge = percentOfInterestChargeDefinition(7L, new BigDecimal("5"));
        final SavingsAccountCharge override = SavingsAccountCharge.createNewWithoutSavingsAccount(earlyWithdrawalCharge,
                new BigDecimal("9"), ChargeTimeType.SAVINGS_ACTIVATION, ChargeCalculationType.PERCENT_OF_INTEREST, null, true, null, null);
        final Set<SavingsAccountCharge> requestCharges = new HashSet<>(Set.of(override));

        final Set<SavingsAccountCharge> result = DynamicDepositAccountAssembler.withEarlyWithdrawalCharge(requestCharges,
                earlyWithdrawalCharge);

        assertThat(result).hasSize(1);
        assertThat(result.iterator().next().getPercentage()).isEqualByComparingTo("9");
    }

    @Test
    void nothingIsAttachedWhenTheProductSelectsNoEarlyWithdrawalCharge() {
        final Set<SavingsAccountCharge> requestCharges = new HashSet<>();

        assertThat(DynamicDepositAccountAssembler.withEarlyWithdrawalCharge(requestCharges, null)).isEmpty();
    }

    @Test
    void unrelatedRequestChargesAreKeptAlongsideTheAttachedEarlyWithdrawalCharge() {
        final Charge flatFee = mock(Charge.class);
        lenient().when(flatFee.getId()).thenReturn(3L);
        lenient().when(flatFee.getAmount()).thenReturn(new BigDecimal("100"));
        lenient().when(flatFee.getChargeCalculation()).thenReturn(ChargeCalculationType.FLAT.getValue());
        lenient().when(flatFee.getChargeTimeType()).thenReturn(ChargeTimeType.SAVINGS_ACTIVATION.getValue());
        final SavingsAccountCharge existing = SavingsAccountCharge.createNewWithoutSavingsAccount(flatFee, new BigDecimal("100"),
                ChargeTimeType.SAVINGS_ACTIVATION, ChargeCalculationType.FLAT, null, true, null, null);
        final Charge earlyWithdrawalCharge = percentOfInterestChargeDefinition(7L, new BigDecimal("5"));

        final Set<SavingsAccountCharge> result = DynamicDepositAccountAssembler.withEarlyWithdrawalCharge(new HashSet<>(Set.of(existing)),
                earlyWithdrawalCharge);

        assertThat(result).hasSize(2);
        assertThat(result).anyMatch(charge -> charge.getCharge() == earlyWithdrawalCharge);
        assertThat(result).anyMatch(charge -> charge.getCharge() == flatFee);
    }

    private Charge percentOfInterestChargeDefinition(final Long id, final BigDecimal percentage) {
        final Charge charge = mock(Charge.class);
        lenient().when(charge.getId()).thenReturn(id);
        lenient().when(charge.getAmount()).thenReturn(percentage);
        lenient().when(charge.getChargeCalculation()).thenReturn(ChargeCalculationType.PERCENT_OF_INTEREST.getValue());
        lenient().when(charge.getChargeTimeType()).thenReturn(ChargeTimeType.WITHDRAWAL_FEE.getValue());
        return charge;
    }
}
