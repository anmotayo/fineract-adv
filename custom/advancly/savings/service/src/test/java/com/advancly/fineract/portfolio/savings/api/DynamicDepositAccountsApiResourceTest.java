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

import com.advancly.fineract.portfolio.savings.data.DynamicDepositAccountData;
import com.advancly.fineract.portfolio.savings.service.DynamicDepositAccountReadPlatformService;
import org.apache.fineract.commands.domain.CommandWrapper;
import org.apache.fineract.commands.service.PortfolioCommandSourceWritePlatformService;
import org.apache.fineract.infrastructure.core.api.ApiRequestParameterHelper;
import org.apache.fineract.infrastructure.core.data.CommandProcessingResult;
import org.apache.fineract.infrastructure.core.serialization.DefaultToApiJsonSerializer;
import org.apache.fineract.infrastructure.security.service.PlatformSecurityContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Guards against a regression to the defect fixed here: {@code createAccountCommand(...)} alone never populates
 * {@code CommandWrapper#getSavingsId()}, which both {@code CalculateInterestDynamicDepositAccountCommandHandler} and
 * {@code PostInterestDynamicDepositAccountCommandHandler} depend on ({@code command.getSavingsId()} being non-null) to
 * function at all - so the built command must carry the account id through {@code getSavingsId()}, not just
 * {@code getEntityId()}.
 */
class DynamicDepositAccountsApiResourceTest {

    private static final Long ACCOUNT_ID = 42L;

    private PortfolioCommandSourceWritePlatformService commandsSourceWritePlatformService;
    private DynamicDepositAccountsApiResource resource;

    @BeforeEach
    void setUp() {
        final PlatformSecurityContext context = mock(PlatformSecurityContext.class);
        final DynamicDepositAccountReadPlatformService readPlatformService = mock(DynamicDepositAccountReadPlatformService.class);
        final DefaultToApiJsonSerializer<DynamicDepositAccountData> toApiJsonSerializer = mock(DefaultToApiJsonSerializer.class);
        this.commandsSourceWritePlatformService = mock(PortfolioCommandSourceWritePlatformService.class);
        final ApiRequestParameterHelper apiRequestParameterHelper = mock(ApiRequestParameterHelper.class);
        when(this.commandsSourceWritePlatformService.logCommandSource(any(CommandWrapper.class)))
                .thenReturn(mock(CommandProcessingResult.class));
        this.resource = new DynamicDepositAccountsApiResource(context, readPlatformService, toApiJsonSerializer,
                this.commandsSourceWritePlatformService, apiRequestParameterHelper);
    }

    @Test
    void calculateInterestCommandCarriesTheAccountIdThroughGetSavingsId() {
        this.resource.handleCommands(ACCOUNT_ID, "calculateInterest", "{}");

        final CommandWrapper commandRequest = capturedCommandWrapper();
        assertThat(commandRequest.getSavingsId()).isEqualTo(ACCOUNT_ID);
        assertThat(commandRequest.getEntityId()).isEqualTo(ACCOUNT_ID);
    }

    @Test
    void postInterestCommandCarriesTheAccountIdThroughGetSavingsId() {
        this.resource.handleCommands(ACCOUNT_ID, "postInterest", "{}");

        final CommandWrapper commandRequest = capturedCommandWrapper();
        assertThat(commandRequest.getSavingsId()).isEqualTo(ACCOUNT_ID);
        assertThat(commandRequest.getEntityId()).isEqualTo(ACCOUNT_ID);
    }

    private CommandWrapper capturedCommandWrapper() {
        final ArgumentCaptor<CommandWrapper> captor = ArgumentCaptor.forClass(CommandWrapper.class);
        org.mockito.Mockito.verify(this.commandsSourceWritePlatformService).logCommandSource(captor.capture());
        return captor.getValue();
    }
}
