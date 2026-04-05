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
package com.advancly.fineract.portfolio.savings.starter;

import com.advancly.fineract.portfolio.savings.service.SavingsAccountWritePlatformServiceDelegate;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.apache.fineract.infrastructure.core.api.JsonCommand;
import org.apache.fineract.infrastructure.core.data.CommandProcessingResult;
import org.apache.fineract.organisation.office.domain.Office;
import org.apache.fineract.organisation.staff.domain.Staff;
import org.apache.fineract.portfolio.savings.data.SavingsAccountData;
import org.apache.fineract.portfolio.savings.domain.SavingsAccount;
import org.apache.fineract.portfolio.savings.domain.SavingsAccountTransaction;
import org.apache.fineract.portfolio.savings.service.SavingsAccountWritePlatformService;

/**
 * Wrapper that adapts a SavingsAccountWritePlatformService to the SavingsAccountWritePlatformServiceDelegate interface.
 * Delegates all calls directly to the wrapped core implementation.
 */
@RequiredArgsConstructor
class CoreSavingsWritePlatformServiceDelegateImpl implements SavingsAccountWritePlatformServiceDelegate {

    private final SavingsAccountWritePlatformService delegate;

    @Override
    public CommandProcessingResult activate(Long savingsId, JsonCommand command) {
        return delegate.activate(savingsId, command);
    }

    @Override
    public CommandProcessingResult deposit(Long savingsId, JsonCommand command) {
        return delegate.deposit(savingsId, command);
    }

    @Override
    public CommandProcessingResult withdrawal(Long savingsId, JsonCommand command) {
        return delegate.withdrawal(savingsId, command);
    }

    @Override
    public CommandProcessingResult applyAnnualFee(Long savingsAccountChargeId, Long accountId) {
        return delegate.applyAnnualFee(savingsAccountChargeId, accountId);
    }

    @Override
    public CommandProcessingResult calculateInterest(Long savingsId) {
        return delegate.calculateInterest(savingsId);
    }

    @Override
    public CommandProcessingResult reverseTransaction(Long savingsId, Long transactionId, boolean allowAccountTransferModification,
            JsonCommand command) {
        return delegate.reverseTransaction(savingsId, transactionId, allowAccountTransferModification, command);
    }

    @Override
    public CommandProcessingResult undoTransaction(Long savingsId, Long transactionId, boolean allowAccountTransferModification) {
        return delegate.undoTransaction(savingsId, transactionId, allowAccountTransferModification);
    }

    @Override
    public CommandProcessingResult adjustSavingsTransaction(Long savingsId, Long transactionId, JsonCommand command) {
        return delegate.adjustSavingsTransaction(savingsId, transactionId, command);
    }

    @Override
    public CommandProcessingResult close(Long savingsId, JsonCommand command) {
        return delegate.close(savingsId, command);
    }

    @Override
    public SavingsAccountTransaction initiateSavingsTransfer(SavingsAccount account, LocalDate transferDate) {
        return delegate.initiateSavingsTransfer(account, transferDate);
    }

    @Override
    public SavingsAccountTransaction withdrawSavingsTransfer(SavingsAccount account, LocalDate transferDate) {
        return delegate.withdrawSavingsTransfer(account, transferDate);
    }

    @Override
    public void rejectSavingsTransfer(SavingsAccount account) {
        delegate.rejectSavingsTransfer(account);
    }

    @Override
    public SavingsAccountTransaction acceptSavingsTransfer(SavingsAccount account, LocalDate transferDate, Office acceptedInOffice,
            Staff staff) {
        return delegate.acceptSavingsTransfer(account, transferDate, acceptedInOffice, staff);
    }

    @Override
    public CommandProcessingResult addSavingsAccountCharge(JsonCommand command) {
        return delegate.addSavingsAccountCharge(command);
    }

    @Override
    public CommandProcessingResult updateSavingsAccountCharge(JsonCommand command) {
        return delegate.updateSavingsAccountCharge(command);
    }

    @Override
    public CommandProcessingResult deleteSavingsAccountCharge(Long savingsAccountId, Long savingsAccountChargeId, JsonCommand command) {
        return delegate.deleteSavingsAccountCharge(savingsAccountId, savingsAccountChargeId, command);
    }

    @Override
    public CommandProcessingResult waiveCharge(Long savingsAccountId, Long savingsAccountChargeId) {
        return delegate.waiveCharge(savingsAccountId, savingsAccountChargeId);
    }

    @Override
    public CommandProcessingResult payCharge(Long savingsAccountId, Long savingsAccountChargeId, JsonCommand command) {
        return delegate.payCharge(savingsAccountId, savingsAccountChargeId, command);
    }

    @Override
    public CommandProcessingResult inactivateCharge(Long savingsAccountId, Long savingsAccountChargeId) {
        return delegate.inactivateCharge(savingsAccountId, savingsAccountChargeId);
    }

    @Override
    public CommandProcessingResult assignFieldOfficer(Long savingsAccountId, JsonCommand command) {
        return delegate.assignFieldOfficer(savingsAccountId, command);
    }

    @Override
    public CommandProcessingResult unassignFieldOfficer(Long savingsAccountId, JsonCommand command) {
        return delegate.unassignFieldOfficer(savingsAccountId, command);
    }

    @Override
    public void applyChargeDue(Long savingsAccountChargeId, Long accountId) {
        delegate.applyChargeDue(savingsAccountChargeId, accountId);
    }

    @Override
    public void processPostActiveActions(SavingsAccount account, DateTimeFormatter fmt, Set<Long> existingTransactionIds,
            Set<Long> existingReversedTransactionIds) {
        delegate.processPostActiveActions(account, fmt, existingTransactionIds, existingReversedTransactionIds);
    }

    @Override
    public CommandProcessingResult modifyWithHoldTax(Long savingsAccountId, JsonCommand command) {
        return delegate.modifyWithHoldTax(savingsAccountId, command);
    }

    @Override
    public void setSubStatusInactive(Long savingsId) {
        delegate.setSubStatusInactive(savingsId);
    }

    @Override
    public void setSubStatusDormant(Long savingsId) {
        delegate.setSubStatusDormant(savingsId);
    }

    @Override
    public void escheat(Long savingsId) {
        delegate.escheat(savingsId);
    }

    @Override
    public CommandProcessingResult postInterest(JsonCommand command) {
        return delegate.postInterest(command);
    }

    @Override
    public void postInterest(SavingsAccount account, boolean postInterestAs, LocalDate transactionDate, boolean backdatedTxnsAllowedTill) {
        delegate.postInterest(account, postInterestAs, transactionDate, backdatedTxnsAllowedTill);
    }

    @Override
    public SavingsAccountData postInterest(SavingsAccountData account, boolean postInterestAs, LocalDate transactionDate,
            boolean backdatedTxnsAllowedTill) {
        return delegate.postInterest(account, postInterestAs, transactionDate, backdatedTxnsAllowedTill);
    }

    @Override
    public CommandProcessingResult blockAccount(Long savingsId, JsonCommand command) {
        return delegate.blockAccount(savingsId, command);
    }

    @Override
    public CommandProcessingResult unblockAccount(Long savingsId) {
        return delegate.unblockAccount(savingsId);
    }

    @Override
    public CommandProcessingResult holdAmount(Long savingsId, JsonCommand command) {
        return delegate.holdAmount(savingsId, command);
    }

    @Override
    public CommandProcessingResult blockCredits(Long savingsId, JsonCommand command) {
        return delegate.blockCredits(savingsId, command);
    }

    @Override
    public CommandProcessingResult unblockCredits(Long savingsId) {
        return delegate.unblockCredits(savingsId);
    }

    @Override
    public CommandProcessingResult blockDebits(Long savingsId, JsonCommand command) {
        return delegate.blockDebits(savingsId, command);
    }

    @Override
    public CommandProcessingResult unblockDebits(Long savingsId) {
        return delegate.unblockDebits(savingsId);
    }

    @Override
    public CommandProcessingResult releaseAmount(Long savingsId, Long transactionId) {
        return delegate.releaseAmount(savingsId, transactionId);
    }

    @Override
    public CommandProcessingResult gsimActivate(Long gsimId, JsonCommand command) {
        return delegate.gsimActivate(gsimId, command);
    }

    @Override
    public CommandProcessingResult gsimDeposit(Long gsimId, JsonCommand command) {
        return delegate.gsimDeposit(gsimId, command);
    }

    @Override
    public CommandProcessingResult bulkGSIMClose(Long gsimId, JsonCommand command) {
        return delegate.bulkGSIMClose(gsimId, command);
    }

    @Override
    public CommandProcessingResult bulkTransaction(Long savingsId, JsonCommand command) {
        return delegate.bulkTransaction(savingsId, command);
    }
}
