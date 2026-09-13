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

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

/**
 * The authoritative cap applied at interest posting: the interest-based charge can never exceed the period's gross
 * interest minus its withholding tax, so the net effect of a posting on the account balance is never negative and
 * principal is preserved (implementation plan Section 10 step 9, Section 11 "Preserve principal").
 */
class DynamicDepositInterestChargeCapTest {

    @Test
    void thePendingTotalIsAppliedInFullWhenTheresEnoughNetInterestToCoverIt() {
        assertThat(DynamicDepositAccount.cappedInterestBasedChargeAmount(new BigDecimal("30"), new BigDecimal("500"), new BigDecimal("50")))
                .isEqualByComparingTo("30");
    }

    @Test
    void theChargeIsCappedAtGrossInterestMinusWithholdingTax() {
        assertThat(
                DynamicDepositAccount.cappedInterestBasedChargeAmount(new BigDecimal("480"), new BigDecimal("500"), new BigDecimal("50")))
                .isEqualByComparingTo("450");
    }

    @Test
    void cappingAtGrossAloneWouldNotBeEnoughToPreservePrincipal() {
        // 500 gross, 50 withholding tax, 500 pending: capping at gross would leave net interest at -50, i.e. the
        // posting would remove 50 from principal. The cap must be 450.
        assertThat(
                DynamicDepositAccount.cappedInterestBasedChargeAmount(new BigDecimal("500"), new BigDecimal("500"), new BigDecimal("50")))
                .isEqualByComparingTo("450");
    }

    @Test
    void nothingIsChargedWhenWithholdingTaxAlreadyConsumesTheWholeGrossInterest() {
        assertThat(DynamicDepositAccount.cappedInterestBasedChargeAmount(new BigDecimal("30"), new BigDecimal("50"), new BigDecimal("50")))
                .isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void aNegativeAvailableAmountNeverProducesANegativeCharge() {
        assertThat(DynamicDepositAccount.cappedInterestBasedChargeAmount(new BigDecimal("30"), new BigDecimal("10"), new BigDecimal("50")))
                .isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void aZeroPendingTotalStaysZero() {
        assertThat(DynamicDepositAccount.cappedInterestBasedChargeAmount(BigDecimal.ZERO, new BigDecimal("500"), BigDecimal.ZERO))
                .isEqualByComparingTo(BigDecimal.ZERO);
    }
}
