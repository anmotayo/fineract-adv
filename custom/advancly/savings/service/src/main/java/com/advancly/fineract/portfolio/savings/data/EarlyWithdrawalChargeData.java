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
 * Never-persisted preview for {@code POST /savingsaccounts/{accountId}/earlywithdrawalcharge}. {@code percentage} is
 * the request override when supplied, else the configured (or account-overridden) percentage.
 */
public final class EarlyWithdrawalChargeData implements Serializable {

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
    private final BigDecimal accountBalanceAfterPosting;

    private EarlyWithdrawalChargeData(final Long accountId, final LocalDate withdrawalDate, final boolean chargeApplicable,
            final String reason, final String interestBasisMode, final Long chargeId, final String chargeName, final BigDecimal percentage,
            final BigDecimal basisAmount, final BigDecimal chargeAmount, final LocalDate selectedFromDate, final LocalDate selectedToDate,
            final BigDecimal accountBalanceAfterPosting) {
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
        this.accountBalanceAfterPosting = accountBalanceAfterPosting;
    }

    public static EarlyWithdrawalChargeData notApplicable(final Long accountId, final LocalDate withdrawalDate, final String reason) {
        return notApplicable(accountId, withdrawalDate, reason, null);
    }

    public static EarlyWithdrawalChargeData notApplicable(final Long accountId, final LocalDate withdrawalDate, final String reason,
            final BigDecimal accountBalanceAfterPosting) {
        return new EarlyWithdrawalChargeData(accountId, withdrawalDate, false, reason, null, null, null, null, null, null, null, null,
                accountBalanceAfterPosting);
    }

    public static EarlyWithdrawalChargeData applicable(final Long accountId, final LocalDate withdrawalDate, final String interestBasisMode,
            final Long chargeId, final String chargeName, final BigDecimal percentage, final BigDecimal basisAmount,
            final BigDecimal chargeAmount, final LocalDate selectedFromDate, final LocalDate selectedToDate) {
        return applicable(accountId, withdrawalDate, interestBasisMode, chargeId, chargeName, percentage, basisAmount, chargeAmount,
                selectedFromDate, selectedToDate, null);
    }

    /**
     * {@code accountBalanceAfterPosting}: the balance after the exit posts interest and tax but before the charge
     * ({@code null} when nothing was posted); a full liquidation pays out this less the charge.
     */
    public static EarlyWithdrawalChargeData applicable(final Long accountId, final LocalDate withdrawalDate, final String interestBasisMode,
            final Long chargeId, final String chargeName, final BigDecimal percentage, final BigDecimal basisAmount,
            final BigDecimal chargeAmount, final LocalDate selectedFromDate, final LocalDate selectedToDate,
            final BigDecimal accountBalanceAfterPosting) {
        return new EarlyWithdrawalChargeData(accountId, withdrawalDate, true, null, interestBasisMode, chargeId, chargeName, percentage,
                basisAmount, chargeAmount, selectedFromDate, selectedToDate, accountBalanceAfterPosting);
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

    public BigDecimal accountBalanceAfterPosting() {
        return this.accountBalanceAfterPosting;
    }
}
