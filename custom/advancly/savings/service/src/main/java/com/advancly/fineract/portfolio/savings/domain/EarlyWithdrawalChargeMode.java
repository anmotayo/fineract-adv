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

/**
 * How a product's early-withdrawal penalty takes interest back.
 *
 * {@code PER_PERIOD} is the pre-existing behaviour: the withdrawal records a pending row and the charge is applied at
 * the next interest posting, computed against that period's gross interest. {@code CUMULATIVE} forfeits all interest
 * earned since inception, computed and charged immediately at the withdrawal.
 *
 * {@code PER_PERIOD} is deliberately value 1 and the fallback for both null and unrecognised values, so any product
 * configured before this enum existed keeps behaving exactly as it did.
 */
public enum EarlyWithdrawalChargeMode {

    PER_PERIOD(1), //
    CUMULATIVE(2);

    private final int value;

    EarlyWithdrawalChargeMode(final int value) {
        this.value = value;
    }

    public int getValue() {
        return this.value;
    }

    public static EarlyWithdrawalChargeMode fromInt(final Integer value) {
        if (value != null && value == CUMULATIVE.value) {
            return CUMULATIVE;
        }
        return PER_PERIOD;
    }

    public boolean isPerPeriod() {
        return this == PER_PERIOD;
    }

    public boolean isCumulative() {
        return this == CUMULATIVE;
    }
}
