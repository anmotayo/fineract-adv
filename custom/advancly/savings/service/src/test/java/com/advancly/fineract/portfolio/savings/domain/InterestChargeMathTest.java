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
import java.util.List;
import org.apache.fineract.organisation.monetary.data.CurrencyData;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class InterestChargeMathTest {

    private static final CurrencyData USD = new CurrencyData("USD", "US Dollar", 2, null, "$", "USD");

    @BeforeEach
    void setUp() {
        MoneyHelperInitializer.initialize();
    }

    @Test
    void recomputedChargeAmountAppliesPercentageAndCapsAtGross() {
        assertThat(InterestChargeMath.recomputedChargeAmount(new BigDecimal("100"), new BigDecimal("25"))).isEqualByComparingTo("25");
        assertThat(InterestChargeMath.recomputedChargeAmount(new BigDecimal("100"), new BigDecimal("150"))).isEqualByComparingTo("100");
    }

    @Test
    void recomputedChargeAmountNeverGoesNegative() {
        assertThat(InterestChargeMath.recomputedChargeAmount(new BigDecimal("100"), new BigDecimal("-10"))).isEqualByComparingTo("0");
    }

    @Test
    void cappedInterestChargeAmountCapsAtGrossMinusWithholdingTax() {
        // gross 500, wht 50: available basis is 450. Recomputed total of 500 must be capped to 450.
        assertThat(InterestChargeMath.cappedInterestChargeAmount(new BigDecimal("500"), new BigDecimal("500"), new BigDecimal("50")))
                .isEqualByComparingTo("450");
    }

    @Test
    void cappedInterestChargeAmountNeverExceedsRecomputedTotal() {
        assertThat(InterestChargeMath.cappedInterestChargeAmount(new BigDecimal("10"), new BigDecimal("500"), new BigDecimal("50")))
                .isEqualByComparingTo("10");
    }

    @Test
    void cappedInterestChargeAmountNeverGoesNegativeWhenWithholdingExceedsGross() {
        assertThat(InterestChargeMath.cappedInterestChargeAmount(new BigDecimal("100"), new BigDecimal("50"), new BigDecimal("80")))
                .isEqualByComparingTo("0");
    }

    /**
     * Review Finding 1 (Critical): {@code recomputedChargeAmount}/{@code cappedInterestChargeAmount} only bound
     * significant digits, not decimal places - reproduced here with the exact figures from that review (gross
     * {@code 57.89}, percentage {@code 33.33} -> {@code 19.294737}, scale 6) to prove {@code roundToCurrency} brings
     * that back down to the currency's actual decimal places.
     */
    @Test
    void roundToCurrencyRoundsAnOverPreciseRecomputedAmountDownToTheCurrencysDecimalPlaces() {
        final BigDecimal overPrecise = InterestChargeMath.recomputedChargeAmount(new BigDecimal("57.89"), new BigDecimal("33.33"));
        assertThat(overPrecise.scale()).isGreaterThan(2);

        final BigDecimal rounded = InterestChargeMath.roundToCurrency(overPrecise, USD);
        assertThat(rounded.scale()).isEqualTo(2);
        assertThat(rounded).isEqualByComparingTo("19.29");
    }

    @Test
    void distributeAcrossRowsGivesTheRemainderToTheLastRowAndSumsExactlyToTheAppliedTotal() {
        final BigDecimal grossInterest = new BigDecimal("57.89");
        final BigDecimal recomputedOne = InterestChargeMath.recomputedChargeAmount(grossInterest, new BigDecimal("33.33"));
        final BigDecimal recomputedTwo = InterestChargeMath.recomputedChargeAmount(grossInterest, new BigDecimal("16.67"));
        final BigDecimal recomputedTotal = recomputedOne.add(recomputedTwo);
        final BigDecimal appliedTotal = InterestChargeMath.roundToCurrency(recomputedTotal, USD);

        final List<BigDecimal> rowAmounts = InterestChargeMath.distributeAcrossRows(appliedTotal, List.of(recomputedOne, recomputedTwo),
                recomputedTotal, USD.getDecimalPlaces());

        assertThat(rowAmounts).hasSize(2);
        assertThat(rowAmounts.get(0).scale()).isLessThanOrEqualTo(2);
        assertThat(rowAmounts.get(1).scale()).isLessThanOrEqualTo(2);
        assertThat(rowAmounts.get(0).add(rowAmounts.get(1))).isEqualByComparingTo(appliedTotal);
    }

    @Test
    void distributeAcrossRowsGivesEverythingToTheOnlyRowWhenThereIsJustOne() {
        final BigDecimal appliedTotal = new BigDecimal("25.00");
        final List<BigDecimal> rowAmounts = InterestChargeMath.distributeAcrossRows(appliedTotal, List.of(new BigDecimal("25")),
                new BigDecimal("25"), USD.getDecimalPlaces());

        assertThat(rowAmounts).hasSize(1);
        assertThat(rowAmounts.get(0)).isEqualByComparingTo("25.00");
    }

    @Test
    void distributeAcrossRowsGivesEachRowZeroWhenTheRecomputedTotalIsZero() {
        final BigDecimal appliedTotal = BigDecimal.ZERO;
        final List<BigDecimal> rowAmounts = InterestChargeMath.distributeAcrossRows(appliedTotal, List.of(BigDecimal.ZERO, BigDecimal.ZERO),
                BigDecimal.ZERO, USD.getDecimalPlaces());

        assertThat(rowAmounts).hasSize(2);
        assertThat(rowAmounts.get(0)).isEqualByComparingTo("0");
        assertThat(rowAmounts.get(1)).isEqualByComparingTo("0");
    }
}
