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
package com.advancly.fineract.portfolio.savings.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.advancly.fineract.portfolio.savings.testutil.MoneyHelperInitializer;
import java.math.BigDecimal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class CumulativeForfeitureCalculatorTest {

    @BeforeEach
    void setUp() {
        MoneyHelperInitializer.initialize();
    }

    @Test
    void atOneHundredPercentTheWholePostedInterestIsForfeited() {
        final BigDecimal forfeit = CumulativeForfeitureCalculator.forfeitureAmount(new BigDecimal("100"), BigDecimal.ZERO, BigDecimal.ZERO,
                new BigDecimal("100"));

        assertThat(forfeit).isEqualByComparingTo("100");
    }

    @Test
    void aSecondWithdrawalOnlyForfeitsTheInterestPostedSinceTheFirst() {
        // 200 posted lifetime, 100 already forfeited by the first withdrawal -> only the new 100 is taken.
        final BigDecimal forfeit = CumulativeForfeitureCalculator.forfeitureAmount(new BigDecimal("200"), BigDecimal.ZERO,
                new BigDecimal("100"), new BigDecimal("100"));

        assertThat(forfeit).isEqualByComparingTo("100");
    }

    @Test
    void aPartialPercentageTargetsTheRemainingAvailableInterestAcrossWithdrawals() {
        // W1: 50% of 100 posted = 50.
        final BigDecimal first = CumulativeForfeitureCalculator.forfeitureAmount(new BigDecimal("100"), BigDecimal.ZERO, BigDecimal.ZERO,
                new BigDecimal("50"));
        assertThat(first).isEqualByComparingTo("50");

        // W2: 200 lifetime posted - 50 already taken = 150 still available; 50% of the remainder is 75.
        final BigDecimal second = CumulativeForfeitureCalculator.forfeitureAmount(new BigDecimal("200"), BigDecimal.ZERO,
                new BigDecimal("50"), new BigDecimal("50"));
        assertThat(second).isEqualByComparingTo("75");
    }

    @Test
    void laterWithdrawalUsesTheBalanceAfterPriorForfeitureAndNewAccrual() {
        final BigDecimal forfeit = CumulativeForfeitureCalculator.forfeitureAmount(new BigDecimal("200"), BigDecimal.ZERO,
                new BigDecimal("80"), new BigDecimal("80"));

        assertThat(forfeit).isEqualByComparingTo("96");
    }

    @Test
    void theBasisIsNetOfWithholdingTaxSoTheChargeCannotReachPrincipal() {
        // 100 gross posted, 10 withheld -> the customer only ever received 90.
        final BigDecimal forfeit = CumulativeForfeitureCalculator.forfeitureAmount(new BigDecimal("100"), new BigDecimal("10"),
                BigDecimal.ZERO, new BigDecimal("100"));

        assertThat(forfeit).isEqualByComparingTo("90");
    }

    @Test
    void nothingIsForfeitedWhenNoInterestHasPostedYet() {
        // The 5,000,000-deposited-then-withdrawn-after-three-weeks case: nothing posted, nothing to take.
        final BigDecimal forfeit = CumulativeForfeitureCalculator.forfeitureAmount(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                new BigDecimal("100"));

        assertThat(forfeit).isEqualByComparingTo("0");
    }

    @Test
    void nothingFurtherIsForfeitedWhenEverythingAlreadyHasBeen() {
        final BigDecimal forfeit = CumulativeForfeitureCalculator.forfeitureAmount(new BigDecimal("100"), BigDecimal.ZERO,
                new BigDecimal("100"), new BigDecimal("100"));

        assertThat(forfeit).isEqualByComparingTo("0");
    }

    @Test
    void theResultNeverExceedsTheInterestStillInTheAccount() {
        // Defensive: even with a nonsensical over-100 percentage the cap holds, so principal is untouchable.
        final BigDecimal forfeit = CumulativeForfeitureCalculator.forfeitureAmount(new BigDecimal("100"), BigDecimal.ZERO,
                new BigDecimal("30"), new BigDecimal("500"));

        assertThat(forfeit).isEqualByComparingTo("70");
    }

    @Test
    void nullInputsAreTreatedAsZero() {
        final BigDecimal forfeit = CumulativeForfeitureCalculator.forfeitureAmount(null, null, null, new BigDecimal("100"));

        assertThat(forfeit).isEqualByComparingTo("0");
    }
}
