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

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import java.util.List;
import org.apache.fineract.infrastructure.core.api.JsonCommand;
import org.apache.fineract.infrastructure.core.data.CommandProcessingResult;
import org.apache.fineract.portfolio.savings.data.SavingsAccountDataDTO;
import org.apache.fineract.portfolio.savings.domain.SavingsAccount;
import org.apache.fineract.portfolio.savings.domain.SavingsAccountRepositoryWrapper;
import org.apache.fineract.portfolio.savings.service.SavingsApplicationProcessWritePlatformService;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Primary
public class AdvanclySavingsApplicationProcessWritePlatformService implements SavingsApplicationProcessWritePlatformService {

    private final SavingsApplicationProcessWritePlatformService delegate;
    private final SavingsAccountRepositoryWrapper savingsAccountRepository;
    private final AdvanclyChargeInterestRuleValidator chargeInterestRuleValidator;

    public AdvanclySavingsApplicationProcessWritePlatformService(
            @Qualifier("coreSavingsApplicationProcessWritePlatformService") final SavingsApplicationProcessWritePlatformService delegate,
            final SavingsAccountRepositoryWrapper savingsAccountRepository,
            final AdvanclyChargeInterestRuleValidator chargeInterestRuleValidator) {
        this.delegate = delegate;
        this.savingsAccountRepository = savingsAccountRepository;
        this.chargeInterestRuleValidator = chargeInterestRuleValidator;
    }

    @Transactional
    @Override
    public CommandProcessingResult submitApplication(final JsonCommand command) {
        final CommandProcessingResult result = this.delegate.submitApplication(command);
        validateAccount(result);
        return result;
    }

    @Transactional
    @Override
    public CommandProcessingResult modifyApplication(final Long savingsId, final JsonCommand command) {
        final CommandProcessingResult result = this.delegate.modifyApplication(savingsId, command);
        validateAccount(savingsId);
        return result;
    }

    @Override
    public CommandProcessingResult deleteApplication(final Long savingsId) {
        return this.delegate.deleteApplication(savingsId);
    }

    @Override
    public CommandProcessingResult approveApplication(final Long savingsId, final JsonCommand command) {
        return this.delegate.approveApplication(savingsId, command);
    }

    @Override
    public CommandProcessingResult undoApplicationApproval(final Long savingsId, final JsonCommand command) {
        return this.delegate.undoApplicationApproval(savingsId, command);
    }

    @Override
    public CommandProcessingResult rejectApplication(final Long savingsId, final JsonCommand command) {
        return this.delegate.rejectApplication(savingsId, command);
    }

    @Override
    public CommandProcessingResult applicantWithdrawsFromApplication(final Long savingsId, final JsonCommand command) {
        return this.delegate.applicantWithdrawsFromApplication(savingsId, command);
    }

    @Transactional
    @Override
    public CommandProcessingResult createActiveApplication(final SavingsAccountDataDTO savingsAccountDataDTO) {
        final CommandProcessingResult result = this.delegate.createActiveApplication(savingsAccountDataDTO);
        validateAccount(result);
        return result;
    }

    @Transactional
    @Override
    public CommandProcessingResult submitGSIMApplication(final JsonCommand command) {
        CommandProcessingResult result = null;
        final JsonArray gsimApplications = command.arrayOfParameterNamed("clientArray");
        for (final JsonElement gsimApplication : gsimApplications) {
            result = submitApplication(JsonCommand.fromExistingCommand(command, gsimApplication,
                    gsimApplication.getAsJsonObject().get("clientId").getAsLong()));
        }
        return result;
    }

    @Override
    public CommandProcessingResult approveGSIMApplication(final Long gsimId, final JsonCommand command) {
        return this.delegate.approveGSIMApplication(gsimId, command);
    }

    @Override
    public CommandProcessingResult rejectGSIMApplication(final Long gsimId, final JsonCommand command) {
        return this.delegate.rejectGSIMApplication(gsimId, command);
    }

    @Override
    public CommandProcessingResult undoGSIMApplicationApproval(final Long gsimId, final JsonCommand command) {
        return this.delegate.undoGSIMApplicationApproval(gsimId, command);
    }

    @Transactional
    @Override
    public CommandProcessingResult modifyGSIMApplication(final Long gsimId, final JsonCommand command) {
        CommandProcessingResult result = null;
        final List<SavingsAccount> childSavings = this.savingsAccountRepository.findByGsimId(gsimId);
        for (final SavingsAccount account : childSavings) {
            result = modifyApplication(account.getId(), command);
        }
        return result;
    }

    private void validateAccount(final CommandProcessingResult result) {
        if (result == null) {
            return;
        }
        final Long savingsId = result.getSavingsId() == null ? result.getResourceId() : result.getSavingsId();
        validateAccount(savingsId);
    }

    private void validateAccount(final Long savingsId) {
        if (savingsId == null) {
            return;
        }
        final SavingsAccount account = this.savingsAccountRepository.findOneWithNotFoundDetection(savingsId);
        this.chargeInterestRuleValidator.validateAccountUsesDailyPostingForCustomPeriodInterestCharge(account);
    }
}
