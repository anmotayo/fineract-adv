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

import org.junit.jupiter.api.Test;

class EarlyWithdrawalChargeModeTest {

    @Test
    void perPeriodIsTheDefaultSoExistingProductsKeepTheirBehaviour() {
        assertThat(EarlyWithdrawalChargeMode.fromInt(null)).isEqualTo(EarlyWithdrawalChargeMode.PER_PERIOD);
        assertThat(EarlyWithdrawalChargeMode.fromInt(1)).isEqualTo(EarlyWithdrawalChargeMode.PER_PERIOD);
        assertThat(EarlyWithdrawalChargeMode.PER_PERIOD.getValue()).isEqualTo(1);
        assertThat(EarlyWithdrawalChargeMode.PER_PERIOD.isPerPeriod()).isTrue();
        assertThat(EarlyWithdrawalChargeMode.PER_PERIOD.isCumulative()).isFalse();
    }

    @Test
    void cumulativeRoundTripsThroughItsPersistedValue() {
        assertThat(EarlyWithdrawalChargeMode.fromInt(2)).isEqualTo(EarlyWithdrawalChargeMode.CUMULATIVE);
        assertThat(EarlyWithdrawalChargeMode.CUMULATIVE.getValue()).isEqualTo(2);
        assertThat(EarlyWithdrawalChargeMode.CUMULATIVE.isCumulative()).isTrue();
        assertThat(EarlyWithdrawalChargeMode.CUMULATIVE.isPerPeriod()).isFalse();
    }

    @Test
    void anUnrecognisedValueFallsBackToPerPeriodRatherThanThrowing() {
        assertThat(EarlyWithdrawalChargeMode.fromInt(99)).isEqualTo(EarlyWithdrawalChargeMode.PER_PERIOD);
    }
}
