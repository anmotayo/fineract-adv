/*
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

import com.advancly.fineract.portfolio.savings.testutil.MoneyHelperInitializer;
import java.math.BigDecimal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class InterestBasedChargeMathTest {

    @BeforeEach
    void setUp() {
        MoneyHelperInitializer.initialize();
    }

    @Test
    void recomputedChargeAmountAppliesPercentageAndCapsAtGross() {
        assertThat(InterestBasedChargeMath.recomputedChargeAmount(new BigDecimal("100"), new BigDecimal("25")))
                .isEqualByComparingTo("25");
        assertThat(InterestBasedChargeMath.recomputedChargeAmount(new BigDecimal("100"), new BigDecimal("150")))
                .isEqualByComparingTo("100");
    }

    @Test
    void recomputedChargeAmountNeverGoesNegative() {
        assertThat(InterestBasedChargeMath.recomputedChargeAmount(new BigDecimal("100"), new BigDecimal("-10")))
                .isEqualByComparingTo("0");
    }

    @Test
    void cappedInterestBasedChargeAmountCapsAtGrossMinusWithholdingTax() {
        // gross 500, wht 50: available basis is 450. Recomputed total of 500 must be capped to 450.
        assertThat(InterestBasedChargeMath.cappedInterestBasedChargeAmount(new BigDecimal("500"), new BigDecimal("500"),
                new BigDecimal("50"))).isEqualByComparingTo("450");
    }

    @Test
    void cappedInterestBasedChargeAmountNeverExceedsRecomputedTotal() {
        assertThat(InterestBasedChargeMath.cappedInterestBasedChargeAmount(new BigDecimal("10"), new BigDecimal("500"),
                new BigDecimal("50"))).isEqualByComparingTo("10");
    }

    @Test
    void cappedInterestBasedChargeAmountNeverGoesNegativeWhenWithholdingExceedsGross() {
        assertThat(InterestBasedChargeMath.cappedInterestBasedChargeAmount(new BigDecimal("100"), new BigDecimal("50"),
                new BigDecimal("80"))).isEqualByComparingTo("0");
    }
}
