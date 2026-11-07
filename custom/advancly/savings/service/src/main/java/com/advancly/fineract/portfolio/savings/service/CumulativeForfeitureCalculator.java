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

import java.math.BigDecimal;
import java.math.MathContext;
import org.apache.fineract.organisation.monetary.domain.MoneyHelper;

/**
 * Cumulative early-withdrawal forfeiture on interest net of withholding tax and prior forfeitures, so it can never
 * reach into principal.
 */
public final class CumulativeForfeitureCalculator {

    private static final BigDecimal ONE_HUNDRED = BigDecimal.valueOf(100L);

    private CumulativeForfeitureCalculator() {}

    // Not a static constant: the rounding mode is per tenant (thread-local).
    private static MathContext percentageMathContext() {
        return new MathContext(8, MoneyHelper.getRoundingMode());
    }

    public static BigDecimal forfeitureAmount(final BigDecimal totalInterestPosted, final BigDecimal totalWithholdTax,
            final BigDecimal alreadyForfeited, final BigDecimal percentage) {
        final BigDecimal basis = zeroIfNull(totalInterestPosted).subtract(zeroIfNull(totalWithholdTax));
        final BigDecimal forfeited = zeroIfNull(alreadyForfeited);
        final BigDecimal stillAvailable = basis.subtract(forfeited);
        if (stillAvailable.compareTo(BigDecimal.ZERO) <= 0) {
            return BigDecimal.ZERO;
        }
        return stillAvailable.multiply(zeroIfNull(percentage)).divide(ONE_HUNDRED, percentageMathContext()).max(BigDecimal.ZERO)
                .min(stillAvailable);
    }

    private static BigDecimal zeroIfNull(final BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }
}
