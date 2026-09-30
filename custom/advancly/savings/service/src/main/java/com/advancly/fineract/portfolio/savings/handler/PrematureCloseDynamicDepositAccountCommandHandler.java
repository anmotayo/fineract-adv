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
package com.advancly.fineract.portfolio.savings.handler;

import com.advancly.fineract.portfolio.savings.domain.DynamicDepositAccount;
import com.advancly.fineract.portfolio.savings.domain.DynamicDepositAccountRepository;
import org.apache.fineract.commands.annotation.CommandType;
import org.apache.fineract.commands.handler.NewCommandSourceHandler;
import org.apache.fineract.infrastructure.core.api.JsonCommand;
import org.apache.fineract.infrastructure.core.data.CommandProcessingResult;
import org.apache.fineract.portfolio.savings.SavingsApiConstants;
import org.apache.fineract.portfolio.savings.domain.SavingsAccountStatusType;
import org.apache.fineract.portfolio.savings.service.SavingsAccountWritePlatformService;
import org.apache.fineract.portfolio.savings.service.SavingsEnumerations;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@CommandType(entity = "DYNAMICDEPOSITACCOUNT", action = "PREMATURECLOSE")
public class PrematureCloseDynamicDepositAccountCommandHandler implements NewCommandSourceHandler {

    private final SavingsAccountWritePlatformService writePlatformService;
    private final DynamicDepositAccountRepository dynamicDepositAccountRepository;

    @Autowired
    public PrematureCloseDynamicDepositAccountCommandHandler(final SavingsAccountWritePlatformService writePlatformService,
            final DynamicDepositAccountRepository dynamicDepositAccountRepository) {
        this.writePlatformService = writePlatformService;
        this.dynamicDepositAccountRepository = dynamicDepositAccountRepository;
    }

    /**
     * {@code close} finalises every account type as {@code CLOSED}. A premature close of a Dynamic Deposit is recorded
     * as {@code PRE_MATURE_CLOSURE} instead - the same status Fixed and Recurring deposits end up in - so clients can
     * tell a premature closure from a normal one. The status is moved inside the same transaction, after the close has
     * validated and settled the account.
     */
    @Transactional
    @Override
    public CommandProcessingResult processCommand(final JsonCommand command) {
        final CommandProcessingResult result = this.writePlatformService.close(command.getSavingsId(), command);

        final DynamicDepositAccount account = this.dynamicDepositAccountRepository.findById(command.getSavingsId()).orElse(null);
        if (account != null && SavingsAccountStatusType.CLOSED.hasStateOf(account.getStatus())) {
            account.setStatus(SavingsAccountStatusType.PRE_MATURE_CLOSURE.getValue());
            this.dynamicDepositAccountRepository.save(account);
            if (result != null && result.getChanges() != null && result.getChanges().containsKey(SavingsApiConstants.statusParamName)) {
                result.getChanges().put(SavingsApiConstants.statusParamName,
                        SavingsEnumerations.status(SavingsAccountStatusType.PRE_MATURE_CLOSURE));
            }
        }
        return result;
    }
}
