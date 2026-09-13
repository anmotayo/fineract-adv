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
import org.apache.fineract.organisation.monetary.data.CurrencyData;
import org.apache.fineract.portfolio.savings.data.SavingsAccountStatusEnumData;

/**
 * Read-side representation of a Dynamic Deposit account (deposit_type_enum = 500). Deliberately lean for Phase 1:
 * exposes account identity/status, the reused {@code m_deposit_account_term_and_preclosure} fields (invested amount /
 * fixed tenor) and the new {@code allowWithdrawal} / {@code dynamicRateEnabled} fields. Interest-summary / rate-history
 * fields called for in the implementation plan's read-API section land with the Phase 2/3 rate-history and
 * interest-engine work.
 */
public class DynamicDepositAccountData implements Serializable {

    private final Long id;
    private final String accountNo;
    private final String externalId;
    private final Long clientId;
    private final String clientName;
    private final Long groupId;
    private final String groupName;
    private final Long savingsProductId;
    private final String savingsProductName;
    private final Long fieldOfficerId;
    private final SavingsAccountStatusEnumData status;
    private final CurrencyData currency;
    private final BigDecimal nominalAnnualInterestRate;
    private final EnumOptionData interestCompoundingPeriodType;
    private final EnumOptionData interestPostingPeriodType;
    private final EnumOptionData interestCalculationType;
    private final EnumOptionData interestCalculationDaysInYearType;
    private final BigDecimal depositAmount;
    private final Integer depositPeriod;
    private final EnumOptionData depositPeriodFrequencyType;
    private final LocalDate expectedFirstDepositOnDate;
    private final LocalDate maturityDate;
    private final BigDecimal maturityAmount;
    private final LocalDate submittedOnDate;
    private final LocalDate approvedOnDate;
    private final LocalDate activatedOnDate;
    private final boolean allowWithdrawal;
    private final boolean dynamicRateEnabled;
    private final boolean transferInterestToSavings;

    // template-only
    private final Collection<DynamicDepositProductData> productOptions;

    public DynamicDepositAccountData(final Long id, final String accountNo, final String externalId, final Long clientId,
            final String clientName, final Long groupId, final String groupName, final Long savingsProductId,
            final String savingsProductName, final Long fieldOfficerId, final SavingsAccountStatusEnumData status,
            final CurrencyData currency, final BigDecimal nominalAnnualInterestRate, final EnumOptionData interestCompoundingPeriodType,
            final EnumOptionData interestPostingPeriodType, final EnumOptionData interestCalculationType,
            final EnumOptionData interestCalculationDaysInYearType, final BigDecimal depositAmount, final Integer depositPeriod,
            final EnumOptionData depositPeriodFrequencyType, final LocalDate expectedFirstDepositOnDate, final LocalDate maturityDate,
            final BigDecimal maturityAmount, final LocalDate submittedOnDate, final LocalDate approvedOnDate,
            final LocalDate activatedOnDate, final boolean allowWithdrawal, final boolean dynamicRateEnabled,
            final boolean transferInterestToSavings) {
        this(id, accountNo, externalId, clientId, clientName, groupId, groupName, savingsProductId, savingsProductName, fieldOfficerId,
                status, currency, nominalAnnualInterestRate, interestCompoundingPeriodType, interestPostingPeriodType,
                interestCalculationType, interestCalculationDaysInYearType, depositAmount, depositPeriod, depositPeriodFrequencyType,
                expectedFirstDepositOnDate, maturityDate, maturityAmount, submittedOnDate, approvedOnDate, activatedOnDate, allowWithdrawal,
                dynamicRateEnabled, transferInterestToSavings, null);
    }

    private DynamicDepositAccountData(final Long id, final String accountNo, final String externalId, final Long clientId,
            final String clientName, final Long groupId, final String groupName, final Long savingsProductId,
            final String savingsProductName, final Long fieldOfficerId, final SavingsAccountStatusEnumData status,
            final CurrencyData currency, final BigDecimal nominalAnnualInterestRate, final EnumOptionData interestCompoundingPeriodType,
            final EnumOptionData interestPostingPeriodType, final EnumOptionData interestCalculationType,
            final EnumOptionData interestCalculationDaysInYearType, final BigDecimal depositAmount, final Integer depositPeriod,
            final EnumOptionData depositPeriodFrequencyType, final LocalDate expectedFirstDepositOnDate, final LocalDate maturityDate,
            final BigDecimal maturityAmount, final LocalDate submittedOnDate, final LocalDate approvedOnDate,
            final LocalDate activatedOnDate, final boolean allowWithdrawal, final boolean dynamicRateEnabled,
            final boolean transferInterestToSavings, final Collection<DynamicDepositProductData> productOptions) {
        this.id = id;
        this.accountNo = accountNo;
        this.externalId = externalId;
        this.clientId = clientId;
        this.clientName = clientName;
        this.groupId = groupId;
        this.groupName = groupName;
        this.savingsProductId = savingsProductId;
        this.savingsProductName = savingsProductName;
        this.fieldOfficerId = fieldOfficerId;
        this.status = status;
        this.currency = currency;
        this.nominalAnnualInterestRate = nominalAnnualInterestRate;
        this.interestCompoundingPeriodType = interestCompoundingPeriodType;
        this.interestPostingPeriodType = interestPostingPeriodType;
        this.interestCalculationType = interestCalculationType;
        this.interestCalculationDaysInYearType = interestCalculationDaysInYearType;
        this.depositAmount = depositAmount;
        this.depositPeriod = depositPeriod;
        this.depositPeriodFrequencyType = depositPeriodFrequencyType;
        this.expectedFirstDepositOnDate = expectedFirstDepositOnDate;
        this.maturityDate = maturityDate;
        this.maturityAmount = maturityAmount;
        this.submittedOnDate = submittedOnDate;
        this.approvedOnDate = approvedOnDate;
        this.activatedOnDate = activatedOnDate;
        this.allowWithdrawal = allowWithdrawal;
        this.dynamicRateEnabled = dynamicRateEnabled;
        this.transferInterestToSavings = transferInterestToSavings;
        this.productOptions = productOptions;
    }

    public static DynamicDepositAccountData withTemplateOptions(final DynamicDepositAccountData data,
            final Collection<DynamicDepositProductData> productOptions) {
        return new DynamicDepositAccountData(data.id, data.accountNo, data.externalId, data.clientId, data.clientName, data.groupId,
                data.groupName, data.savingsProductId, data.savingsProductName, data.fieldOfficerId, data.status, data.currency,
                data.nominalAnnualInterestRate, data.interestCompoundingPeriodType, data.interestPostingPeriodType,
                data.interestCalculationType, data.interestCalculationDaysInYearType, data.depositAmount, data.depositPeriod,
                data.depositPeriodFrequencyType, data.expectedFirstDepositOnDate, data.maturityDate, data.maturityAmount,
                data.submittedOnDate, data.approvedOnDate, data.activatedOnDate, data.allowWithdrawal, data.dynamicRateEnabled,
                data.transferInterestToSavings, productOptions);
    }

    public Long id() {
        return this.id;
    }

    public Long savingsProductId() {
        return this.savingsProductId;
    }

    public CurrencyData currency() {
        return this.currency;
    }
}
