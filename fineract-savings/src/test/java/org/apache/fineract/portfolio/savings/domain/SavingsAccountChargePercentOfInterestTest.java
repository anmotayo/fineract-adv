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
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

import java.math.BigDecimal;
import org.apache.fineract.portfolio.charge.domain.Charge;
import org.apache.fineract.portfolio.charge.domain.ChargeCalculationType;
import org.apache.fineract.portfolio.charge.domain.ChargeTimeType;
import org.junit.jupiter.api.Test;

/**
 * Guards the PERCENT_OF_INTEREST arms of {@link SavingsAccountCharge}. Enabling that calculation type for savings
 * (commit f51316b8f) made a previously unreachable code path reachable: it nulled {@code amount}, which the constructor
 * immediately dereferences through {@code determineIfFullyPaid()} -> {@code calculateOutstanding()}.
 */
class SavingsAccountChargePercentOfInterestTest {

    @Test
    void aPercentOfInterestChargeCanBeConstructedWithoutThrowing() {
        assertThatCode(() -> percentOfInterestCharge(new BigDecimal("5"))).doesNotThrowAnyException();
    }

    @Test
    void theConfiguredPercentageIsPreservedAndReadableBack() {
        final SavingsAccountCharge charge = percentOfInterestCharge(new BigDecimal("5"));

        assertThat(charge.getPercentage()).isEqualByComparingTo("5");
    }

    @Test
    void anAccountLevelOverrideWinsOverTheChargeDefinitionAmount() {
        final SavingsAccountCharge charge = percentOfInterestCharge(new BigDecimal("7.5"));

        assertThat(charge.getPercentage()).isEqualByComparingTo("7.5");
    }

    @Test
    void amountAndOutstandingAreZeroRatherThanNullSoBothNotNullColumnsStayValid() {
        final SavingsAccountCharge charge = percentOfInterestCharge(new BigDecimal("5"));

        assertThat(charge.amount()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(charge.amoutOutstanding()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void aFlatChargeStillHasNoPercentage() {
        final Charge definition = mock(Charge.class);
        lenient().when(definition.getAmount()).thenReturn(new BigDecimal("100"));
        lenient().when(definition.getChargeCalculation()).thenReturn(ChargeCalculationType.FLAT.getValue());

        final SavingsAccountCharge charge = SavingsAccountCharge.createNewWithoutSavingsAccount(definition, new BigDecimal("100"),
                ChargeTimeType.SAVINGS_ACTIVATION, ChargeCalculationType.FLAT, null, true, null, null);

        assertThat(charge.getPercentage()).isNull();
        assertThat(charge.amount()).isEqualByComparingTo("100");
    }

    private SavingsAccountCharge percentOfInterestCharge(final BigDecimal percentage) {
        final Charge definition = mock(Charge.class);
        lenient().when(definition.getAmount()).thenReturn(new BigDecimal("2"));
        lenient().when(definition.getChargeCalculation()).thenReturn(ChargeCalculationType.PERCENT_OF_INTEREST.getValue());

        return SavingsAccountCharge.createNewWithoutSavingsAccount(definition, percentage, ChargeTimeType.SAVINGS_ACTIVATION,
                ChargeCalculationType.PERCENT_OF_INTEREST, null, true, null, null);
    }
}
