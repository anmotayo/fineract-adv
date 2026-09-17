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

import java.math.BigDecimal;
import java.math.MathContext;
import org.apache.fineract.organisation.monetary.domain.MoneyHelper;

/**
 * Shared by {@link DynamicDepositAccount}'s entity-level per-period charge application and the plain-Savings
 * DTO/JDBC batch equivalent ({@code AdvanclySavingsSchedularInterestPoster}) — one implementation of the
 * recompute/cap arithmetic for both code paths, extracted so they can never drift apart.
 */
public final class InterestBasedChargeMath {

    private static final BigDecimal ONE_HUNDRED = BigDecimal.valueOf(100L);

    private InterestBasedChargeMath() {
    }

    // NOT a static constant: MoneyHelper.getRoundingMode() resolves the CURRENT tenant's configured rounding mode
    // from a thread-local, so capturing it in a static initialiser would either fail at class-load time or freeze
    // one tenant's setting for every other tenant.
    private static MathContext percentageMathContext() {
        return new MathContext(8, MoneyHelper.getRoundingMode());
    }

    /**
     * One contribution's amount, recomputed from its stored/resolved percentage against the period's real gross
     * interest. Clamped to [0, gross] per contribution; the authoritative cap on the SUM of several contributions is
     * {@link #cappedInterestBasedChargeAmount(BigDecimal, BigDecimal, BigDecimal)}.
     */
    public static BigDecimal recomputedChargeAmount(final BigDecimal grossInterest, final BigDecimal chargePercentage) {
        return grossInterest.multiply(chargePercentage).divide(ONE_HUNDRED, percentageMathContext()).min(grossInterest)
                .max(BigDecimal.ZERO);
    }

    /**
     * The authoritative cap on a period's interest-based charge: gross interest has just been credited and
     * withholding tax just debited, so capping the charge at their difference guarantees the whole posting's net
     * effect on the balance is {@code gross - wht - charge >= 0} — the charge always comes out of interest and
     * never reaches principal.
     */
    public static BigDecimal cappedInterestBasedChargeAmount(final BigDecimal recomputedTotal, final BigDecimal grossInterestForPeriod,
            final BigDecimal withholdingTaxForPeriod) {
        final BigDecimal available = grossInterestForPeriod.subtract(withholdingTaxForPeriod).max(BigDecimal.ZERO);
        return recomputedTotal.min(available).max(BigDecimal.ZERO);
    }
}
