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
import java.util.Collection;
import org.apache.fineract.infrastructure.core.data.EnumOptionData;
import org.apache.fineract.portfolio.paymenttype.data.PaymentTypeData;
import org.apache.fineract.portfolio.savings.data.SavingsAccountData;

/**
 * What closing a Dynamic Deposit on {@code closedOnDate} would pay out, and the options to close it with. Backs both
 * {@code GET dynamicdepositaccounts/{id}/template?command=close} and {@code POST
 * dynamicdepositaccounts/{id}?command=calculatePrematureAmount}. Read-only: computing it writes nothing.
 *
 * {@code maturityAmount} is the net payout - the balance after the closing interest has posted (net of withholding tax)
 * and after any early-withdrawal forfeiture. It carries that name because that is the field the Fixed and Recurring
 * Deposit closure templates use for it and the closure screens already bind to it.
 *
 * {@code principal} is the account balance before the closing interest posts; {@code depositAmount} is the amount
 * invested, which is what reinvesting the principal only carries over (reinvesting principal and interest carries
 * {@code maturityAmount}).
 *
 * {@code forfeiture*} describe the early-withdrawal forfeiture a premature closure incurs; {@code forfeitureApplicable}
 * is {@code false} - and the amount zero - when the account's product has no qualifying cumulative rule, or the closure
 * is not early.
 */
public class DynamicDepositClosureData implements Serializable {

    private final Long accountId;
    private final LocalDate closedOnDate;
    private final LocalDate maturityDate;
    private final boolean earlyClosure;
    private final BigDecimal principal;
    private final BigDecimal depositAmount;
    private final BigDecimal interestPosted;
    private final BigDecimal withholdTax;
    private final boolean forfeitureApplicable;
    private final BigDecimal forfeitureAmount;
    private final BigDecimal forfeiturePercentage;
    private final Long forfeitureChargeId;
    private final String forfeitureChargeName;
    private final BigDecimal maturityAmount;
    private final Collection<EnumOptionData> onAccountClosureOptions;
    private final Collection<PaymentTypeData> paymentTypeOptions;
    private final Collection<SavingsAccountData> savingsAccounts;

    public DynamicDepositClosureData(final Long accountId, final LocalDate closedOnDate, final LocalDate maturityDate,
            final boolean earlyClosure, final BigDecimal principal, final BigDecimal depositAmount, final BigDecimal interestPosted,
            final BigDecimal withholdTax, final boolean forfeitureApplicable, final BigDecimal forfeitureAmount,
            final BigDecimal forfeiturePercentage, final Long forfeitureChargeId, final String forfeitureChargeName,
            final BigDecimal maturityAmount, final Collection<EnumOptionData> onAccountClosureOptions,
            final Collection<PaymentTypeData> paymentTypeOptions, final Collection<SavingsAccountData> savingsAccounts) {
        this.accountId = accountId;
        this.closedOnDate = closedOnDate;
        this.maturityDate = maturityDate;
        this.earlyClosure = earlyClosure;
        this.principal = principal;
        this.depositAmount = depositAmount;
        this.interestPosted = interestPosted;
        this.withholdTax = withholdTax;
        this.forfeitureApplicable = forfeitureApplicable;
        this.forfeitureAmount = forfeitureAmount;
        this.forfeiturePercentage = forfeiturePercentage;
        this.forfeitureChargeId = forfeitureChargeId;
        this.forfeitureChargeName = forfeitureChargeName;
        this.maturityAmount = maturityAmount;
        this.onAccountClosureOptions = onAccountClosureOptions;
        this.paymentTypeOptions = paymentTypeOptions;
        this.savingsAccounts = savingsAccounts;
    }

    public Long getAccountId() {
        return accountId;
    }

    public LocalDate getClosedOnDate() {
        return closedOnDate;
    }

    public LocalDate getMaturityDate() {
        return maturityDate;
    }

    public boolean isEarlyClosure() {
        return earlyClosure;
    }

    public BigDecimal getPrincipal() {
        return principal;
    }

    public BigDecimal getDepositAmount() {
        return depositAmount;
    }

    public BigDecimal getInterestPosted() {
        return interestPosted;
    }

    public BigDecimal getWithholdTax() {
        return withholdTax;
    }

    public boolean isForfeitureApplicable() {
        return forfeitureApplicable;
    }

    public BigDecimal getForfeitureAmount() {
        return forfeitureAmount;
    }

    public BigDecimal getForfeiturePercentage() {
        return forfeiturePercentage;
    }

    public Long getForfeitureChargeId() {
        return forfeitureChargeId;
    }

    public String getForfeitureChargeName() {
        return forfeitureChargeName;
    }

    public BigDecimal getMaturityAmount() {
        return maturityAmount;
    }

    public Collection<EnumOptionData> getOnAccountClosureOptions() {
        return onAccountClosureOptions;
    }

    public Collection<PaymentTypeData> getPaymentTypeOptions() {
        return paymentTypeOptions;
    }

    public Collection<SavingsAccountData> getSavingsAccounts() {
        return savingsAccounts;
    }
}
