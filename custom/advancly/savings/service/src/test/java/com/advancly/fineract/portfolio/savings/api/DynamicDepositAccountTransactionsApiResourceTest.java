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
package com.advancly.fineract.portfolio.savings.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.apache.fineract.commands.domain.CommandWrapper;
import org.apache.fineract.commands.service.PortfolioCommandSourceWritePlatformService;
import org.apache.fineract.infrastructure.core.api.ApiRequestParameterHelper;
import org.apache.fineract.infrastructure.core.data.CommandProcessingResult;
import org.apache.fineract.infrastructure.core.serialization.DefaultToApiJsonSerializer;
import org.apache.fineract.infrastructure.security.service.PlatformSecurityContext;
import org.apache.fineract.portfolio.paymenttype.service.PaymentTypeReadPlatformService;
import org.apache.fineract.portfolio.savings.data.SavingsAccountTransactionData;
import org.apache.fineract.portfolio.savings.service.SavingsAccountReadPlatformService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class DynamicDepositAccountTransactionsApiResourceTest {

    private static final Long ACCOUNT_ID = 42L;
    private static final Long TRANSACTION_ID = 99L;

    private PortfolioCommandSourceWritePlatformService commandsSourceWritePlatformService;
    private DynamicDepositAccountTransactionsApiResource resource;

    @BeforeEach
    void setUp() {
        final PlatformSecurityContext context = mock(PlatformSecurityContext.class);
        final DefaultToApiJsonSerializer<SavingsAccountTransactionData> toApiJsonSerializer = mock(DefaultToApiJsonSerializer.class);
        this.commandsSourceWritePlatformService = mock(PortfolioCommandSourceWritePlatformService.class);
        final ApiRequestParameterHelper apiRequestParameterHelper = mock(ApiRequestParameterHelper.class);
        final SavingsAccountReadPlatformService savingsAccountReadPlatformService = mock(SavingsAccountReadPlatformService.class);
        final PaymentTypeReadPlatformService paymentTypeReadPlatformService = mock(PaymentTypeReadPlatformService.class);
        when(this.commandsSourceWritePlatformService.logCommandSource(any(CommandWrapper.class)))
                .thenReturn(mock(CommandProcessingResult.class));
        this.resource = new DynamicDepositAccountTransactionsApiResource(context, toApiJsonSerializer,
                this.commandsSourceWritePlatformService, apiRequestParameterHelper, savingsAccountReadPlatformService,
                paymentTypeReadPlatformService);
    }

    @Test
    void depositCommandUsesDynamicDepositAccountEntity() {
        this.resource.transaction(ACCOUNT_ID, "deposit", "{}");

        final CommandWrapper commandRequest = capturedCommandWrapper();
        assertDynamicCommand(commandRequest, "DEPOSIT");
        assertThat(commandRequest.getTransactionId()).isNull();
        assertThat(commandRequest.getSubentityId()).isNull();
    }

    @Test
    void withdrawalCommandUsesDynamicDepositAccountEntity() {
        this.resource.transaction(ACCOUNT_ID, "withdrawal", "{}");

        final CommandWrapper commandRequest = capturedCommandWrapper();
        assertDynamicCommand(commandRequest, "WITHDRAWAL");
        assertThat(commandRequest.getTransactionId()).isNull();
        assertThat(commandRequest.getSubentityId()).isNull();
    }

    @Test
    void undoTransactionCommandUsesDynamicDepositAccountEntity() {
        this.resource.handleTransactionCommands(ACCOUNT_ID, TRANSACTION_ID, "undo", "{}");

        final CommandWrapper commandRequest = capturedCommandWrapper();
        assertDynamicCommand(commandRequest, "UNDOTRANSACTION");
        assertThat(commandRequest.getTransactionId()).isEqualTo(TRANSACTION_ID.toString());
        assertThat(commandRequest.getSubentityId()).isEqualTo(TRANSACTION_ID);
    }

    @Test
    void adjustTransactionCommandUsesDynamicDepositAccountEntity() {
        this.resource.handleTransactionCommands(ACCOUNT_ID, TRANSACTION_ID, "modify", "{}");

        final CommandWrapper commandRequest = capturedCommandWrapper();
        assertDynamicCommand(commandRequest, "ADJUSTTRANSACTION");
        assertThat(commandRequest.getTransactionId()).isEqualTo(TRANSACTION_ID.toString());
        assertThat(commandRequest.getSubentityId()).isEqualTo(TRANSACTION_ID);
    }

    private void assertDynamicCommand(final CommandWrapper commandRequest, final String actionName) {
        assertThat(commandRequest.getEntityName()).isEqualTo("DYNAMICDEPOSITACCOUNT");
        assertThat(commandRequest.getActionName()).isEqualTo(actionName);
        assertThat(commandRequest.getSavingsId()).isEqualTo(ACCOUNT_ID);
        assertThat(commandRequest.getEntityId()).isEqualTo(ACCOUNT_ID);
    }

    private CommandWrapper capturedCommandWrapper() {
        final ArgumentCaptor<CommandWrapper> captor = ArgumentCaptor.forClass(CommandWrapper.class);
        org.mockito.Mockito.verify(this.commandsSourceWritePlatformService).logCommandSource(captor.capture());
        return captor.getValue();
    }
}
