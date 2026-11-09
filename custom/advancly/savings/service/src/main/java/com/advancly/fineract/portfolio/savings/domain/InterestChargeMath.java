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
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import org.apache.fineract.organisation.monetary.data.CurrencyData;
import org.apache.fineract.organisation.monetary.domain.MonetaryCurrency;
import org.apache.fineract.organisation.monetary.domain.Money;
import org.apache.fineract.organisation.monetary.domain.MoneyHelper;

/**
 * Shared charge arithmetic for DynamicDepositAccount and AdvanclySavingsSchedularInterestPoster so the two cannot
 * drift.
 */
public final class InterestChargeMath {

    private static final BigDecimal ONE_HUNDRED = BigDecimal.valueOf(100L);

    private InterestChargeMath() {}

    // Not static: the rounding mode is per-tenant (thread-local).
    private static MathContext percentageMathContext() {
        return new MathContext(8, MoneyHelper.getRoundingMode());
    }

    public static BigDecimal recomputedChargeAmount(final BigDecimal grossInterest, final BigDecimal chargePercentage) {
        return grossInterest.multiply(chargePercentage).divide(ONE_HUNDRED, percentageMathContext()).min(grossInterest)
                .max(BigDecimal.ZERO);
    }

    /** Caps the charge at gross - wht so it never reaches principal. */
    public static BigDecimal cappedInterestChargeAmount(final BigDecimal recomputedTotal, final BigDecimal grossInterestForPeriod,
            final BigDecimal withholdingTaxForPeriod) {
        final BigDecimal available = grossInterestForPeriod.subtract(withholdingTaxForPeriod).max(BigDecimal.ZERO);
        return recomputedTotal.min(available).max(BigDecimal.ZERO);
    }

    /** Callers must round the capped amount with this: the MathContext bounds digits, not decimal places. */
    public static BigDecimal roundToCurrency(final BigDecimal amount, final CurrencyData currency) {
        return Money.of(currency, amount).getAmount();
    }

    public static BigDecimal roundToCurrency(final BigDecimal amount, final MonetaryCurrency currency) {
        return Money.of(currency, amount).getAmount();
    }

    /**
     * Splits {@code appliedTotal} proportionally; the last row takes the remainder. Non-last rows round DOWN so the
     * remainder can never go negative.
     */
    public static List<BigDecimal> distributeAcrossRows(final BigDecimal appliedTotal, final List<BigDecimal> recomputedAmounts,
            final BigDecimal recomputedTotal, final int currencyDecimalPlaces) {
        final int count = recomputedAmounts.size();
        final List<BigDecimal> rowAmounts = new ArrayList<>(count);
        BigDecimal distributed = BigDecimal.ZERO;
        for (int i = 0; i < count; i++) {
            final BigDecimal rowAmount;
            if (i == count - 1) {
                rowAmount = appliedTotal.subtract(distributed);
            } else if (recomputedTotal.compareTo(BigDecimal.ZERO) == 0) {
                rowAmount = BigDecimal.ZERO;
            } else {
                rowAmount = recomputedAmounts.get(i).multiply(appliedTotal).divide(recomputedTotal, percentageMathContext())
                        .setScale(currencyDecimalPlaces, RoundingMode.DOWN);
            }
            distributed = distributed.add(rowAmount);
            rowAmounts.add(rowAmount);
        }
        return rowAmounts;
    }
}
