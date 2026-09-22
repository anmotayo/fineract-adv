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
 * The cumulative early-withdrawal forfeiture amount: how much of the net, un-forfeited interest still sitting in the
 * account is taken back by this withdrawal.
 *
 * The percentage applies to the remaining available interest at the time of the withdrawal. Prior forfeitures are
 * removed from the basis first, so a later withdrawal only charges against the interest balance that remains plus any
 * new interest posted since the earlier withdrawal.
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
        return stillAvailable.multiply(zeroIfNull(percentage)).divide(ONE_HUNDRED, percentageMathContext()).max(BigDecimal.ZERO)
                .min(stillAvailable);
    }

    private static BigDecimal zeroIfNull(final BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }
}
