package com.advancly.fineract.portfolio.savings.service;

import java.math.BigDecimal;
import java.math.MathContext;
import org.apache.fineract.organisation.monetary.domain.MoneyHelper;

/**
 * The cumulative early-withdrawal forfeiture amount: how much of the interest an account has earned since inception is
 * taken back by this withdrawal.
 *
 * The percentage targets a proportion of LIFETIME posted interest, and each withdrawal tops the account up to that
 * target - it is not applied to the remaining un-forfeited balance. Applying it to the remainder would compound the
 * percentage across repeat withdrawals: at 50%, a second withdrawal would take (200-50)*50% = 75, bringing the lifetime
 * total to 125 of 200 posted, i.e. 62.5% rather than the configured 50%.
 *
 * The basis is net of withholding tax because tax has already debited part of the posted interest from the balance (see
 * SavingsAccountSummary's WITHHOLD_TAX case), so the customer only ever received gross minus tax. Forfeiting the gross
 * figure would overshoot into principal by exactly the tax amount.
 *
 * Together those two rules make principal structurally untouchable: the result can never exceed
 * {@code basis - alreadyForfeited}, which is precisely the interest still sitting in the account.
 */
public final class CumulativeForfeitureCalculator {

    private static final BigDecimal ONE_HUNDRED = BigDecimal.valueOf(100L);

    private CumulativeForfeitureCalculator() {}

    // NOT a static constant: MoneyHelper.getRoundingMode() resolves the CURRENT tenant's configured rounding mode from
    // a thread-local, so capturing it once would freeze one tenant's setting for every other tenant.
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
        final BigDecimal target = basis.multiply(zeroIfNull(percentage)).divide(ONE_HUNDRED, percentageMathContext());
        return target.subtract(forfeited).max(BigDecimal.ZERO).min(stillAvailable);
    }

    private static BigDecimal zeroIfNull(final BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }
}
