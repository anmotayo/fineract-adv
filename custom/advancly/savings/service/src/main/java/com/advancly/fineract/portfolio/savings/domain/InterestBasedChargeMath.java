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
 * Shared by {@link DynamicDepositAccount}'s entity-level per-period charge application and the plain-Savings DTO/JDBC
 * batch equivalent ({@code AdvanclySavingsSchedularInterestPoster}) — one implementation of the
 * recompute/cap/round/distribute arithmetic for both code paths, extracted so they can never drift apart.
 *
 * <p>
 * {@code recomputedChargeAmount}/{@code cappedInterestBasedChargeAmount} only bound SIGNIFICANT DIGITS
 * ({@code MathContext(8, ...)}), not decimal places - a non-round percentage against a non-round gross interest amount
 * can therefore carry more precision than the account currency (and the {@code DECIMAL(19,6)} columns storing it)
 * should ever show. Every caller MUST round the capped amount through {@link #roundToCurrency} before treating it as a
 * transaction amount or as the {@code appliedTotal} basis for {@link #distributeAcrossRows}.
 */
public final class InterestBasedChargeMath {

    private static final BigDecimal ONE_HUNDRED = BigDecimal.valueOf(100L);

    private InterestBasedChargeMath() {}

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
     * The authoritative cap on a period's interest-based charge: gross interest has just been credited and withholding
     * tax just debited, so capping the charge at their difference guarantees the whole posting's net effect on the
     * balance is {@code gross - wht - charge >= 0} — the charge always comes out of interest and never reaches
     * principal.
     */
    public static BigDecimal cappedInterestBasedChargeAmount(final BigDecimal recomputedTotal, final BigDecimal grossInterestForPeriod,
            final BigDecimal withholdingTaxForPeriod) {
        final BigDecimal available = grossInterestForPeriod.subtract(withholdingTaxForPeriod).max(BigDecimal.ZERO);
        return recomputedTotal.min(available).max(BigDecimal.ZERO);
    }

    /**
     * Rounds a raw capped charge amount down to the account currency's actual decimal places, via the same
     * {@code Money.of(...)} construction every other monetary amount in this codebase is rounded through. Callers must
     * apply this to {@link #cappedInterestBasedChargeAmount}'s result before it becomes a transaction amount or the
     * {@code appliedTotal} passed to {@link #distributeAcrossRows} - see this class's javadoc.
     */
    public static BigDecimal roundToCurrency(final BigDecimal amount, final CurrencyData currency) {
        return Money.of(currency, amount).getAmount();
    }

    /** {@link #roundToCurrency(BigDecimal, CurrencyData)}, for callers already holding a {@link MonetaryCurrency}. */
    public static BigDecimal roundToCurrency(final BigDecimal amount, final MonetaryCurrency currency) {
        return Money.of(currency, amount).getAmount();
    }

    /**
     * Distributes an already currency-rounded {@code appliedTotal} across each contribution's recomputed share,
     * proportionally to {@code recomputedAmounts}/{@code recomputedTotal}, with the LAST row absorbing whatever
     * rounding remainder is left over so the returned amounts always sum to exactly {@code appliedTotal}.
     *
     * <p>
     * Non-last rows are truncated DOWN ({@link RoundingMode#DOWN}), never rounded to the nearest per the tenant's
     * default mode: a default mode that rounds up can push the non-last rows' running sum above {@code appliedTotal},
     * which would make the last row's {@code appliedTotal.subtract(distributed)} go negative. Truncating down
     * guarantees the non-last rows' running sum never exceeds the exact partial total they approximate, so the last
     * row's remainder is always in {@code [0, appliedTotal]}.
     *
     * <p>
     * Uses the same tenant-aware {@link #percentageMathContext()} as {@link #recomputedChargeAmount} - previously,
     * {@code AdvanclySavingsSchedularInterestPoster} and {@code DynamicDepositAccount} each re-typed this loop with
     * their own {@code MathContext} ({@code MathContext.DECIMAL64} vs. this class's tenant-aware one), which had
     * already started to drift; this method is the single implementation both now call.
     *
     * @param appliedTotal
     *            the currency-rounded total to distribute - see {@link #roundToCurrency}
     * @param recomputedAmounts
     *            each contribution's recomputed share (same order, same size, as the returned list)
     * @param recomputedTotal
     *            the (pre-cap, pre-round) sum of {@code recomputedAmounts}, used only as the proportional basis
     * @param currencyDecimalPlaces
     *            the account currency's decimal places, to which every non-last row is scaled
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
