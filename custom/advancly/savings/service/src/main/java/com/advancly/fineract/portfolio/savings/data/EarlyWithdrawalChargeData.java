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
package com.advancly.fineract.portfolio.savings.data;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Response for {@code POST /savingsaccounts/{accountId}/earlywithdrawalcharge} - a read-only, never-persisted preview
 * of the early-withdrawal interest charge a withdrawal on {@code withdrawalDate} would incur, computed the same way the
 * real cumulative/custom-period early-withdrawal charge logic does, just without writing anything.
 *
 * {@code chargeApplicable} is {@code false} (with {@code reason} explaining why) when the account's product has no
 * qualifying early-withdrawal charge rule, no qualifying active penalty charge, or (custom-period mode only) the
 * selected period has already had a charge applied under an "once per selected period" policy.
 *
 * {@code percentage} is the percentage the charge was actually computed with - the request's
 * {@code earlyWithdrawalChargePercentage} override when one was supplied, otherwise whatever is configured on the
 * charge (or overridden on the account).
 */
public class EarlyWithdrawalChargeData implements Serializable {

    private final Long accountId;
    private final LocalDate withdrawalDate;
    private final boolean chargeApplicable;
    private final String reason;
    private final String interestBasisMode;
    private final Long chargeId;
    private final String chargeName;
    private final BigDecimal percentage;
    private final BigDecimal basisAmount;
    private final BigDecimal chargeAmount;
    private final LocalDate selectedFromDate;
    private final LocalDate selectedToDate;

    private EarlyWithdrawalChargeData(final Long accountId, final LocalDate withdrawalDate, final boolean chargeApplicable,
            final String reason, final String interestBasisMode, final Long chargeId, final String chargeName, final BigDecimal percentage,
            final BigDecimal basisAmount, final BigDecimal chargeAmount, final LocalDate selectedFromDate, final LocalDate selectedToDate) {
        this.accountId = accountId;
        this.withdrawalDate = withdrawalDate;
        this.chargeApplicable = chargeApplicable;
        this.reason = reason;
        this.interestBasisMode = interestBasisMode;
        this.chargeId = chargeId;
        this.chargeName = chargeName;
        this.percentage = percentage;
        this.basisAmount = basisAmount;
        this.chargeAmount = chargeAmount;
        this.selectedFromDate = selectedFromDate;
        this.selectedToDate = selectedToDate;
    }

    public static EarlyWithdrawalChargeData notApplicable(final Long accountId, final LocalDate withdrawalDate, final String reason) {
        return new EarlyWithdrawalChargeData(accountId, withdrawalDate, false, reason, null, null, null, null, null, null, null, null);
    }

    public static EarlyWithdrawalChargeData applicable(final Long accountId, final LocalDate withdrawalDate, final String interestBasisMode,
            final Long chargeId, final String chargeName, final BigDecimal percentage, final BigDecimal basisAmount,
            final BigDecimal chargeAmount, final LocalDate selectedFromDate, final LocalDate selectedToDate) {
        return new EarlyWithdrawalChargeData(accountId, withdrawalDate, true, null, interestBasisMode, chargeId, chargeName, percentage,
                basisAmount, chargeAmount, selectedFromDate, selectedToDate);
    }

    public Long accountId() {
        return this.accountId;
    }

    public LocalDate withdrawalDate() {
        return this.withdrawalDate;
    }

    public boolean chargeApplicable() {
        return this.chargeApplicable;
    }

    public String reason() {
        return this.reason;
    }

    public String interestBasisMode() {
        return this.interestBasisMode;
    }

    public Long chargeId() {
        return this.chargeId;
    }

    public String chargeName() {
        return this.chargeName;
    }

    public BigDecimal percentage() {
        return this.percentage;
    }

    public BigDecimal basisAmount() {
        return this.basisAmount;
    }

    public BigDecimal chargeAmount() {
        return this.chargeAmount;
    }

    public LocalDate selectedFromDate() {
        return this.selectedFromDate;
    }

    public LocalDate selectedToDate() {
        return this.selectedToDate;
    }
}
