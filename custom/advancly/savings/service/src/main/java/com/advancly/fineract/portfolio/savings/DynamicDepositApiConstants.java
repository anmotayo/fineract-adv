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
package com.advancly.fineract.portfolio.savings;

import java.util.Set;
import org.apache.fineract.accounting.common.AccountingConstants.SavingProductAccountingParams;
import org.apache.fineract.portfolio.savings.DepositsApiConstants;

/**
 * API constants for the Dynamic Deposit product/account (deposit_type_enum = 500). Kept in the custom module so that no
 * core Fineract class needs to change to support this product type - see {@code DepositAccountType} and
 * {@code DepositsApiConstants} in fineract-core for the two constants that were added there (the discriminator value
 * and the resource-name strings), which is the only core change this feature required.
 */
public final class DynamicDepositApiConstants {

    private DynamicDepositApiConstants() {

    }

    public static final String DYNAMIC_DEPOSIT_PRODUCT_RESOURCE_NAME = "dynamicdepositproduct";
    public static final String DYNAMIC_DEPOSIT_ACCOUNT_RESOURCE_NAME = "dynamicdepositaccount";

    // dynamic-detail parameters, shared between the product and account payloads
    public static final String allowWithdrawalParamName = "allowWithdrawal";
    public static final String linkedAccountParamName = "linkAccountId";
    public static final String dynamicRateEnabledParamName = "dynamicRateEnabled";

    // product-only early-withdrawal penalty parameters (implementation plan Section 2 / Phase 4)
    public static final String earlyWithdrawalPenaltyEnabledParamName = "earlyWithdrawalPenaltyEnabled";
    public static final String earlyWithdrawalChargeIdParamName = "earlyWithdrawalChargeId";
    public static final String earlyWithdrawalChargeModeParamName = "earlyWithdrawalChargeMode";

    // optional withdraw-command parameter: overrides the account's snapshotted early-withdrawal charge percentage for
    // that single withdrawal only. Allow-listed in core's SavingsAccountConstant (not here) because the shared
    // SavingsAccountTransactionDataValidator gates the withdraw payload before this module is ever consulted - see
    // DynamicDepositEarlyWithdrawalChargeService#resolvePercentage.
    public static final String earlyWithdrawalChargePercentageParamName = "earlyWithdrawalChargePercentage";

    // term / amount / withholding-tax-posting-type parameters, reused verbatim from the core Deposits API so JSON
    // payloads stay consistent across Fixed/Recurring/Dynamic deposit product and account types. Persisted in the same
    // m_deposit_product_term_and_preclosure / m_deposit_account_term_and_preclosure tables FD/RD already use.
    public static final String minDepositTermParamName = DepositsApiConstants.minDepositTermParamName;
    public static final String maxDepositTermParamName = DepositsApiConstants.maxDepositTermParamName;
    public static final String minDepositTermTypeIdParamName = DepositsApiConstants.minDepositTermTypeIdParamName;
    public static final String maxDepositTermTypeIdParamName = DepositsApiConstants.maxDepositTermTypeIdParamName;
    public static final String inMultiplesOfDepositTermParamName = DepositsApiConstants.inMultiplesOfDepositTermParamName;
    public static final String inMultiplesOfDepositTermTypeIdParamName = DepositsApiConstants.inMultiplesOfDepositTermTypeIdParamName;
    public static final String minDepositAmountParamName = DepositsApiConstants.depositMinAmountParamName;
    public static final String maxDepositAmountParamName = DepositsApiConstants.depositMaxAmountParamName;
    public static final String depositAmountParamName = DepositsApiConstants.depositAmountParamName;
    public static final String withHoldTaxPostingTypeIdParamName = DepositsApiConstants.withHoldTaxPostingTypeIdParamName;

    // product parameters
    public static final Set<String> DYNAMIC_DEPOSIT_PRODUCT_REQUEST_DATA_PARAMETERS = Set.of("locale", "name", "shortName", "description",
            "currencyCode", "digitsAfterDecimal", "inMultiplesOf", "interestCompoundingPeriodType", "interestPostingPeriodType",
            "interestCalculationType", "interestCalculationDaysInYearType", "lockinPeriodFrequency", "lockinPeriodFrequencyType",
            "accountingRule", "charges", "charts", "minBalanceForInterestCalculation", "withHoldTax", "taxGroupId",
            allowWithdrawalParamName, dynamicRateEnabledParamName, earlyWithdrawalPenaltyEnabledParamName, earlyWithdrawalChargeIdParamName,
            earlyWithdrawalChargeModeParamName, minDepositTermParamName, maxDepositTermParamName, minDepositTermTypeIdParamName,
            maxDepositTermTypeIdParamName, inMultiplesOfDepositTermParamName, inMultiplesOfDepositTermTypeIdParamName,
            minDepositAmountParamName, maxDepositAmountParamName, depositAmountParamName, withHoldTaxPostingTypeIdParamName,
            SavingProductAccountingParams.SAVINGS_REFERENCE.getValue(), SavingProductAccountingParams.SAVINGS_CONTROL.getValue(),
            SavingProductAccountingParams.TRANSFERS_SUSPENSE.getValue(), SavingProductAccountingParams.INTEREST_ON_SAVINGS.getValue(),
            SavingProductAccountingParams.INCOME_FROM_FEES.getValue(), SavingProductAccountingParams.INCOME_FROM_PENALTIES.getValue(),
            SavingProductAccountingParams.FEES_RECEIVABLE.getValue(), SavingProductAccountingParams.PENALTIES_RECEIVABLE.getValue(),
            SavingProductAccountingParams.INTEREST_PAYABLE.getValue(),
            SavingProductAccountingParams.PAYMENT_CHANNEL_FUND_SOURCE_MAPPING.getValue(),
            SavingProductAccountingParams.FEE_INCOME_ACCOUNT_MAPPING.getValue(),
            SavingProductAccountingParams.PENALTY_INCOME_ACCOUNT_MAPPING.getValue());

    public static final Set<String> DYNAMIC_DEPOSIT_PRODUCT_RESPONSE_DATA_PARAMETERS = Set.of("id", "name", "shortName", "description",
            "currency", "interestCompoundingPeriodType", "interestPostingPeriodType", "interestCalculationType",
            "interestCalculationDaysInYearType", "lockinPeriodFrequency", "lockinPeriodFrequencyType", "accountingRule", "charges",
            "charts", "activeChart", "minBalanceForInterestCalculation", "withHoldTax", "taxGroupId", "taxGroup", allowWithdrawalParamName,
            dynamicRateEnabledParamName, earlyWithdrawalPenaltyEnabledParamName, earlyWithdrawalChargeIdParamName,
            earlyWithdrawalChargeModeParamName, minDepositTermParamName, maxDepositTermParamName, minDepositTermTypeIdParamName,
            maxDepositTermTypeIdParamName, inMultiplesOfDepositTermParamName, inMultiplesOfDepositTermTypeIdParamName,
            minDepositAmountParamName, maxDepositAmountParamName, depositAmountParamName, "withHoldTaxPostingType", "currencyOptions",
            "interestCompoundingPeriodTypeOptions", "interestPostingPeriodTypeOptions", "interestCalculationTypeOptions",
            "interestCalculationDaysInYearTypeOptions", "lockinPeriodFrequencyTypeOptions", "accountingRuleOptions", "chargeOptions",
            "penaltyOptions", "paymentTypeOptions", "accountingMappingOptions", "accountingMappings", "paymentChannelToFundSourceMappings",
            "feeToIncomeAccountMappings", "penaltyToIncomeAccountMappings", "taxGroupOptions", "chartTemplate", "depositTermTypeOptions",
            "withHoldTaxPostingTypeOptions");

    // account parameters
    public static final Set<String> DYNAMIC_DEPOSIT_ACCOUNT_REQUEST_DATA_PARAMETERS = Set.of("locale", "dateFormat", "clientId", "groupId",
            "productId", "fieldOfficerId", "accountNo", "externalId", "submittedOnDate", "nominalAnnualInterestRate",
            "interestCompoundingPeriodType", "interestPostingPeriodType", "interestCalculationType", "interestCalculationDaysInYearType",
            "minRequiredOpeningBalance", "lockinPeriodFrequency", "lockinPeriodFrequencyType", "withdrawalFeeForTransfers", "charges",
            "withHoldTax", depositAmountParamName, "depositPeriod", "depositPeriodFrequencyId", "expectedFirstDepositOnDate",
            "transferInterestToSavings", allowWithdrawalParamName, linkedAccountParamName, dynamicRateEnabledParamName,
            withHoldTaxPostingTypeIdParamName);

    public static final Set<String> DYNAMIC_DEPOSIT_ACCOUNT_RESPONSE_DATA_PARAMETERS = Set.of("id", "accountNo", "externalId", "clientId",
            "clientName", "groupId", "groupName", "savingsProductId", "savingsProductName", "fieldOfficerId", "status", "timeline",
            "currency", "nominalAnnualInterestRate", "interestCompoundingPeriodType", "interestPostingPeriodType",
            "interestCalculationType", "interestCalculationDaysInYearType", depositAmountParamName, "depositPeriod",
            "depositPeriodFrequencyType", "expectedFirstDepositOnDate", "maturityAmount", "maturityDate", "submittedOnDate",
            "approvedOnDate", "activatedOnDate", "transferInterestToSavings", linkedAccountParamName, "linkedAccount", "summary",
            "transactions", "charges", allowWithdrawalParamName, dynamicRateEnabledParamName, "interestBasedChargeDerived",
            minDepositTermParamName, maxDepositTermParamName, minDepositAmountParamName, maxDepositAmountParamName,
            "withHoldTaxPostingType", "chart");
}
