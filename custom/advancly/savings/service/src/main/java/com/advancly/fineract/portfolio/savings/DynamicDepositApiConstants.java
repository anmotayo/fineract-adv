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

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

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
    public static final String dynamicRateEnabledParamName = "dynamicRateEnabled";

    // product-only early-withdrawal penalty parameters (implementation plan Section 2 / Phase 4)
    public static final String earlyWithdrawalPenaltyEnabledParamName = "earlyWithdrawalPenaltyEnabled";
    public static final String earlyWithdrawalChargeIdParamName = "earlyWithdrawalChargeId";

    // product parameters
    public static final Set<String> DYNAMIC_DEPOSIT_PRODUCT_REQUEST_DATA_PARAMETERS = new HashSet<>(
            Arrays.asList("locale", "name", "shortName", "description", "currencyCode", "digitsAfterDecimal", "inMultiplesOf",
                    "nominalAnnualInterestRate", "interestCompoundingPeriodType", "interestPostingPeriodType", "interestCalculationType",
                    "interestCalculationDaysInYearType", "lockinPeriodFrequency", "lockinPeriodFrequencyType", "accountingRule", "charges",
                    "charts", "minBalanceForInterestCalculation", "withHoldTax", "taxGroupId", allowWithdrawalParamName,
                    dynamicRateEnabledParamName, earlyWithdrawalPenaltyEnabledParamName, earlyWithdrawalChargeIdParamName));

    public static final Set<String> DYNAMIC_DEPOSIT_PRODUCT_RESPONSE_DATA_PARAMETERS = new HashSet<>(
            Arrays.asList("id", "name", "shortName", "description", "currency", "nominalAnnualInterestRate",
                    "interestCompoundingPeriodType", "interestPostingPeriodType", "interestCalculationType",
                    "interestCalculationDaysInYearType", "lockinPeriodFrequency", "lockinPeriodFrequencyType", "accountingRule", "charges",
                    "charts", "minBalanceForInterestCalculation", "withHoldTax", "taxGroupId", allowWithdrawalParamName,
                    dynamicRateEnabledParamName, earlyWithdrawalPenaltyEnabledParamName, earlyWithdrawalChargeIdParamName));

    // account parameters
    public static final Set<String> DYNAMIC_DEPOSIT_ACCOUNT_REQUEST_DATA_PARAMETERS = new HashSet<>(Arrays.asList("locale", "dateFormat",
            "clientId", "groupId", "productId", "fieldOfficerId", "accountNo", "externalId", "submittedOnDate", "nominalAnnualInterestRate",
            "interestCompoundingPeriodType", "interestPostingPeriodType", "interestCalculationType", "interestCalculationDaysInYearType",
            "minRequiredOpeningBalance", "lockinPeriodFrequency", "lockinPeriodFrequencyType", "withdrawalFeeForTransfers", "charges",
            "withHoldTax", "depositAmount", "depositPeriod", "depositPeriodFrequencyId", "expectedFirstDepositOnDate",
            "transferInterestToSavings", allowWithdrawalParamName, dynamicRateEnabledParamName));

    public static final Set<String> DYNAMIC_DEPOSIT_ACCOUNT_RESPONSE_DATA_PARAMETERS = new HashSet<>(Arrays.asList("id", "accountNo",
            "externalId", "clientId", "clientName", "groupId", "groupName", "savingsProductId", "savingsProductName", "fieldOfficerId",
            "status", "timeline", "currency", "nominalAnnualInterestRate", "interestCompoundingPeriodType", "interestPostingPeriodType",
            "interestCalculationType", "interestCalculationDaysInYearType", "depositAmount", "depositPeriod", "depositPeriodFrequencyType",
            "maturityAmount", "maturityDate", allowWithdrawalParamName, dynamicRateEnabledParamName, "interestBasedChargeDerived",
            "interestBasedChargePostedDerived"));
}
