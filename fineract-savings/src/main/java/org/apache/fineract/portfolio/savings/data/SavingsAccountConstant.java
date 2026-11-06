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
package org.apache.fineract.portfolio.savings.data;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import org.apache.fineract.portfolio.savings.SavingsApiConstants;

public class SavingsAccountConstant extends SavingsApiConstants {

    /**
     * These parameters will match the class level parameters of {@link SavingsProductData}. Where possible, we try to
     * get response parameters to match those of request parameters.
     */
    protected static final Set<String> SAVINGS_ACCOUNT_REQUEST_DATA_PARAMETERS = new HashSet<>(Arrays.asList(localeParamName,
            dateFormatParamName, monthDayFormatParamName, staffIdParamName, isGSIM, isParentAccount, accountNoParamName,
            externalIdParamName, clientIdParamName, groupIdParamName, productIdParamName, fieldOfficerIdParamName, submittedOnDateParamName,
            nominalAnnualInterestRateParamName, interestCompoundingPeriodTypeParamName, interestPostingPeriodTypeParamName,
            interestCalculationTypeParamName, interestCalculationDaysInYearTypeParamName, minRequiredOpeningBalanceParamName,
            lockinPeriodFrequencyParamName, lockinPeriodFrequencyTypeParamName,
            // withdrawalFeeAmountParamName, withdrawalFeeTypeParamName,
            withdrawalFeeForTransfersParamName, feeAmountParamName, feeOnMonthDayParamName, chargesParamName, allowOverdraftParamName,
            overdraftLimitParamName, minRequiredBalanceParamName, enforceMinRequiredBalanceParamName, lienAllowedParamName,
            maxAllowedLienLimitParamName, nominalAnnualInterestRateOverdraftParamName, minOverdraftForInterestCalculationParamName,
            withHoldTaxParamName, datatables, gsimApplicationId, gsimLastApplication));

    /**
     * These parameters will match the class level parameters of {@link SavingsAccountData}. Where possible, we try to
     * get response parameters to match those of request parameters.
     */

    protected static final Set<String> SAVINGS_ACCOUNT_TRANSACTION_REQUEST_DATA_PARAMETERS = new HashSet<>(Arrays.asList(localeParamName,
            dateFormatParamName, transactionDateParamName, transactionAmountParamName, paymentTypeIdParamName,
            transactionAccountNumberParamName, checkNumberParamName, routingCodeParamName, receiptNumberParamName, bankNumberParamName,
            retailEntriesParamName, childAccountIdParamName, noteParamName, amountParamName, dateParamName, isManualTransaction,
            lienTransaction, chargesPaidByData, submittedOnDateParamName, accountIdParamName, accountNoParamName,
            // "earlyWithdrawalChargePercentage" - optional, ignored by every account type except Dynamic Deposit (the
            // custom advancly module has no compile-time visibility of this core validator to add its own param, so
            // it is allow-listed here instead): overrides the account's snapshotted early-withdrawal charge
            // percentage for this single withdrawal only. See AdvanclyInterestChargeApplicationService.
            "earlyWithdrawalChargePercentage",
            // "selectedFromDate" / "selectedToDate" - optional custom-period bounds for charge-driven early-withdrawal
            // interest charge calculation.
            "selectedFromDate", "selectedToDate",
            // "applyEarlyWithdrawalCharge" - optional. Plain Savings has no maturity date, so Fineract cannot decide
            // for itself whether a withdrawal is "early"; the upstream application signals it here. Ignored unless the
            // product is configured for an early-withdrawal penalty. Allow-listed in core for the same reason as
            // earlyWithdrawalChargePercentage: the custom advancly module cannot see this validator.
            "applyEarlyWithdrawalCharge",
            // "type" - "deposit"/"withdrawal". Not a core deposit/withdrawal request parameter; present only because
            // the custom advancly bulk-transaction endpoint forwards backdated transactions to this same core
            // deposit(...)/withdrawal(...) path one at a time, deep-copying each per-transaction JSON object (which
            // carries its own "type" field) rather than stripping it first. Allow-listed here for the same reason as
            // the other advancly params above. See
            // AdvanclySavingsAccountWritePlatformService#delegateBulkTransactionToCore.
            "type"));

    protected static final Set<String> SAVINGS_ACCOUNT_TRANSACTION_RESPONSE_DATA_PARAMETERS = new HashSet<>(
            Arrays.asList(idParamName, accountNoParamName));

    protected static final Set<String> SAVINGS_ACCOUNT_ACTIVATION_REQUEST_DATA_PARAMETERS = new HashSet<>(
            Arrays.asList(localeParamName, dateFormatParamName, activatedOnDateParamName));

    protected static final Set<String> SAVINGS_ACCOUNT_CLOSE_REQUEST_DATA_PARAMETERS = new HashSet<>(
            Arrays.asList(localeParamName, dateFormatParamName, closedOnDateParamName, noteParamName, paymentTypeIdParamName,
                    withdrawBalanceParamName, transactionAccountNumberParamName, checkNumberParamName, routingCodeParamName,
                    receiptNumberParamName, bankNumberParamName, postInterestValidationOnClosure,
                    // "applyEarlyWithdrawalCharge" - optional, mirroring the same param on a withdrawal (see
                    // SAVINGS_ACCOUNT_TRANSACTION_REQUEST_DATA_PARAMETERS above). A premature closure is just another
                    // early exit, and plain Savings has no maturity date to decide earliness for itself, so the
                    // upstream application signals it here too. Allow-listed in core for the same reason: the custom
                    // advancly module cannot see this validator.
                    "applyEarlyWithdrawalCharge"));

    protected static final Set<String> SAVINGS_ACCOUNT_CHARGES_ADD_REQUEST_DATA_PARAMETERS = new HashSet<>(
            Arrays.asList(chargeIdParamName, amountParamName, dueAsOfDateParamName, dateFormatParamName, localeParamName,
                    feeOnMonthDayParamName, monthDayFormatParamName, feeIntervalParamName));

    protected static final Set<String> SAVINGS_ACCOUNT_CHARGES_PAY_CHARGE_REQUEST_DATA_PARAMETERS = new HashSet<>(
            Arrays.asList(amountParamName, dueAsOfDateParamName, dateFormatParamName, localeParamName, noteParamName));

}
