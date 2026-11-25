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

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.apache.fineract.infrastructure.core.data.ApiParameterError;
import org.apache.fineract.infrastructure.core.exception.PlatformApiDataValidationException;
import org.apache.fineract.infrastructure.core.service.DateUtils;
import org.apache.fineract.portfolio.client.domain.Client;
import org.apache.fineract.portfolio.client.exception.ClientNotActiveException;
import org.apache.fineract.portfolio.group.domain.Group;
import org.apache.fineract.portfolio.group.exception.GroupNotActiveException;
import org.apache.fineract.portfolio.savings.domain.SavingsAccount;
import org.apache.fineract.portfolio.savings.domain.SavingsAccountCharge;
import org.apache.fineract.portfolio.savings.domain.SavingsEvent;
import org.apache.fineract.portfolio.savings.exception.SavingsActivityPriorToClientTransferException;

/** Core's deposit/withdrawal checks, repeated for the append path with core's error codes. */
public final class SavingsTransactionRules {

    private SavingsTransactionRules() {}

    /**
     * Core reverses accruals for a transaction on or before the accrued-till date; the append path can't, so it goes
     * through core. Reads the account row loaded under the lock, so it is never stale.
     */
    public static boolean accrualWouldBeReversed(final SavingsAccount account, final LocalDate transactionDate) {
        final LocalDate accruedTillDate = account.getAccruedTillDate();
        return account.savingsProduct().isAccrualBasedAccountingEnabled() && accruedTillDate != null
                && !DateUtils.isAfter(transactionDate, accruedTillDate);
    }

    /** Mirrors {@code SavingsAccount#deposit}. */
    public static void validateDepositAllowed(final SavingsAccount account, final LocalDate transactionDate) {
        final String resourceTypeName = account.depositAccountType().resourceName();
        if (account.isNotActive()) {
            throw validationError("error.msg." + resourceTypeName + ".transaction.account.is.not.active",
                    "Transaction is not allowed. Account is not active.", transactionDate);
        }
        validateDate(account, resourceTypeName, transactionDate);
        validateNotBeforeClientTransfer(SavingsEvent.SAVINGS_DEPOSIT, account, transactionDate);
    }

    /** Mirrors {@code SavingsAccount#withdraw}, including the lock-in period. */
    public static void validateWithdrawalAllowed(final SavingsAccount account, final LocalDate transactionDate) {
        final String resourceTypeName = account.depositAccountType().resourceName();
        if (!account.isTransactionsAllowed()) {
            throw validationError("error.msg." + resourceTypeName + ".transaction.account.is.not.active",
                    "Transaction is not allowed. Account is not active.", transactionDate);
        }
        validateDate(account, resourceTypeName, transactionDate);
        final LocalDate lockedInUntilDate = account.getLockedInUntilDate();
        if (lockedInUntilDate != null && DateUtils.isBefore(transactionDate, lockedInUntilDate)) {
            throw validationError("error.msg." + resourceTypeName + ".transaction.withdrawals.blocked.during.lockin.period",
                    "Withdrawal is not allowed. No withdrawals are allowed until after " + lockedInUntilDate, transactionDate,
                    lockedInUntilDate);
        }
        validateNotBeforeClientTransfer(SavingsEvent.SAVINGS_WITHDRAWAL, account, transactionDate);
    }

    /** Mirrors core's checkClientOrGroupActive. */
    public static void validateClientAndGroupActive(final SavingsAccount account) {
        final Client client = account.getClient();
        if (client != null && client.isNotActive()) {
            throw new ClientNotActiveException(client.getId());
        }
        final Group group = account.group();
        if (group != null && group.isNotActive()) {
            throw new GroupNotActiveException(group.getId());
        }
    }

    /** A withdrawal fee core pays inside the withdrawal; percent-of-interest charges are applied separately. */
    public static boolean hasAutoPayableWithdrawalFee(final SavingsAccount account) {
        for (final SavingsAccountCharge charge : account.charges()) {
            if (charge.isWithdrawalFee() && charge.isActive() && !charge.isPercentageOfInterest()) {
                return true;
            }
        }
        return false;
    }

    private static void validateDate(final SavingsAccount account, final String resourceTypeName, final LocalDate transactionDate) {
        if (DateUtils.isDateInTheFuture(transactionDate)) {
            throw validationError("error.msg." + resourceTypeName + ".transaction.in.the.future",
                    "Transaction date cannot be in the future.", transactionDate);
        }
        final LocalDate activationDate = account.getActivationDate();
        if (activationDate != null && DateUtils.isBefore(transactionDate, activationDate)) {
            throw validationError("error.msg." + resourceTypeName + ".transaction.before.activation.date",
                    "Transaction date cannot be before accounts activation date.", transactionDate, activationDate);
        }
    }

    private static void validateNotBeforeClientTransfer(final SavingsEvent event, final SavingsAccount account,
            final LocalDate transactionDate) {
        final Client client = account.getClient();
        if (client != null) {
            final LocalDate clientOfficeJoiningDate = client.getOfficeJoiningDate();
            if (clientOfficeJoiningDate != null && DateUtils.isBefore(transactionDate, clientOfficeJoiningDate)) {
                throw new SavingsActivityPriorToClientTransferException(event.toString(), clientOfficeJoiningDate);
            }
        }
    }

    private static PlatformApiDataValidationException validationError(final String code, final String defaultUserMessage,
            final LocalDate transactionDate, final Object... arguments) {
        final Object[] args = new Object[1 + arguments.length];
        args[0] = String.valueOf(transactionDate);
        for (int i = 0; i < arguments.length; i++) {
            args[i + 1] = String.valueOf(arguments[i]);
        }
        final ApiParameterError error = ApiParameterError.parameterError(code, defaultUserMessage, "transactionDate", args);
        final List<ApiParameterError> dataValidationErrors = new ArrayList<>();
        dataValidationErrors.add(error);
        return new PlatformApiDataValidationException(dataValidationErrors);
    }
}
