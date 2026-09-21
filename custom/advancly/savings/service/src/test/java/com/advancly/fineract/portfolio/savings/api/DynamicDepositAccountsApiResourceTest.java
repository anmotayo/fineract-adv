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
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.advancly.fineract.portfolio.savings.data.DynamicDepositAccountData;
import com.advancly.fineract.portfolio.savings.service.DynamicDepositAccountReadPlatformService;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.UriInfo;
import java.util.Collection;
import java.util.List;
import org.apache.fineract.commands.domain.CommandWrapper;
import org.apache.fineract.commands.service.PortfolioCommandSourceWritePlatformService;
import org.apache.fineract.infrastructure.core.api.ApiRequestParameterHelper;
import org.apache.fineract.infrastructure.core.data.CommandProcessingResult;
import org.apache.fineract.infrastructure.core.serialization.ApiRequestJsonSerializationSettings;
import org.apache.fineract.infrastructure.core.serialization.DefaultToApiJsonSerializer;
import org.apache.fineract.infrastructure.security.service.PlatformSecurityContext;
import org.apache.fineract.portfolio.account.data.PortfolioAccountData;
import org.apache.fineract.portfolio.account.service.AccountAssociationsReadPlatformService;
import org.apache.fineract.portfolio.savings.DepositAccountType;
import org.apache.fineract.portfolio.savings.data.SavingsAccountChargeData;
import org.apache.fineract.portfolio.savings.data.SavingsAccountTransactionData;
import org.apache.fineract.portfolio.savings.service.DepositAccountReadPlatformService;
import org.apache.fineract.portfolio.savings.service.SavingsAccountChargeReadPlatformService;
import org.apache.fineract.useradministration.domain.AppUser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Guards against a regression to the defect fixed here: {@code createAccountCommand(...)} alone never populates
 * {@code CommandWrapper#getSavingsId()}, which both {@code CalculateInterestDynamicDepositAccountCommandHandler} and
 * {@code PostInterestDynamicDepositAccountCommandHandler} depend on ({@code command.getSavingsId()} being non-null) to
 * function at all - so the built command must carry the account id through {@code getSavingsId()}, not just
 * {@code getEntityId()}.
 */
class DynamicDepositAccountsApiResourceTest {

    private static final Long ACCOUNT_ID = 42L;

    private DynamicDepositAccountReadPlatformService readPlatformService;
    private DefaultToApiJsonSerializer<DynamicDepositAccountData> toApiJsonSerializer;
    private PortfolioCommandSourceWritePlatformService commandsSourceWritePlatformService;
    private DepositAccountReadPlatformService depositAccountReadPlatformService;
    private SavingsAccountChargeReadPlatformService savingsAccountChargeReadPlatformService;
    private AccountAssociationsReadPlatformService accountAssociationsReadPlatformService;
    private DynamicDepositAccountsApiResource resource;

    @BeforeEach
    void setUp() {
        final PlatformSecurityContext context = mock(PlatformSecurityContext.class);
        final AppUser user = mock(AppUser.class);
        when(context.authenticatedUser()).thenReturn(user);
        this.readPlatformService = mock(DynamicDepositAccountReadPlatformService.class);
        this.toApiJsonSerializer = mock(DefaultToApiJsonSerializer.class);
        this.commandsSourceWritePlatformService = mock(PortfolioCommandSourceWritePlatformService.class);
        this.depositAccountReadPlatformService = mock(DepositAccountReadPlatformService.class);
        this.savingsAccountChargeReadPlatformService = mock(SavingsAccountChargeReadPlatformService.class);
        this.accountAssociationsReadPlatformService = mock(AccountAssociationsReadPlatformService.class);
        final ApiRequestParameterHelper apiRequestParameterHelper = new ApiRequestParameterHelper();
        when(this.commandsSourceWritePlatformService.logCommandSource(any(CommandWrapper.class)))
                .thenReturn(mock(CommandProcessingResult.class));
        this.resource = new DynamicDepositAccountsApiResource(context, this.readPlatformService, this.toApiJsonSerializer,
                this.commandsSourceWritePlatformService, apiRequestParameterHelper, this.depositAccountReadPlatformService,
                this.savingsAccountChargeReadPlatformService, this.accountAssociationsReadPlatformService);
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

    @Test
    void closeCommandUsesDynamicDepositAccountEntity() {
        this.resource.handleCommands(ACCOUNT_ID, "close", "{}");

        final CommandWrapper commandRequest = capturedCommandWrapper();
        assertThat(commandRequest.getEntityName()).isEqualTo("DYNAMICDEPOSITACCOUNT");
        assertThat(commandRequest.getActionName()).isEqualTo("CLOSE");
        assertThat(commandRequest.getSavingsId()).isEqualTo(ACCOUNT_ID);
        assertThat(commandRequest.getEntityId()).isEqualTo(ACCOUNT_ID);
    }

    @Test
    void prematureCloseCommandUsesDynamicDepositAccountEntity() {
        this.resource.handleCommands(ACCOUNT_ID, "prematureClose", "{}");

        final CommandWrapper commandRequest = capturedCommandWrapper();
        assertThat(commandRequest.getEntityName()).isEqualTo("DYNAMICDEPOSITACCOUNT");
        assertThat(commandRequest.getActionName()).isEqualTo("PREMATURECLOSE");
        assertThat(commandRequest.getSavingsId()).isEqualTo(ACCOUNT_ID);
        assertThat(commandRequest.getEntityId()).isEqualTo(ACCOUNT_ID);
    }

    @Test
    @SuppressWarnings("unchecked")
    void retrieveOneWithAllAssociationsAddsTransactionsChargesAndLinkedAccount() {
        final DynamicDepositAccountData accountData = accountData();
        final SavingsAccountTransactionData transaction = mock(SavingsAccountTransactionData.class);
        final SavingsAccountChargeData charge = mock(SavingsAccountChargeData.class);
        final PortfolioAccountData linkedAccount = PortfolioAccountData.lookup(100L, "SA000100");
        when(this.readPlatformService.retrieveOne(ACCOUNT_ID)).thenReturn(accountData);
        when(this.depositAccountReadPlatformService.retrieveAllTransactions(DepositAccountType.DYNAMIC_DEPOSIT, ACCOUNT_ID))
                .thenReturn(List.of(transaction));
        when(this.savingsAccountChargeReadPlatformService.retrieveSavingsAccountCharges(ACCOUNT_ID, "all")).thenReturn(List.of(charge));
        when(this.accountAssociationsReadPlatformService.retriveSavingsLinkedAssociation(ACCOUNT_ID)).thenReturn(linkedAccount);

        this.resource.retrieveOne(ACCOUNT_ID, "all", uriInfo("all"));

        final DynamicDepositAccountData serializedAccount = capturedSerializedAccount();
        assertThat((Collection<SavingsAccountTransactionData>) ReflectionTestUtils.getField(serializedAccount, "transactions"))
                .containsExactly(transaction);
        assertThat((Collection<SavingsAccountChargeData>) ReflectionTestUtils.getField(serializedAccount, "charges"))
                .containsExactly(charge);
        assertThat(ReflectionTestUtils.getField(serializedAccount, "linkedAccount")).isSameAs(linkedAccount);
        assertThat(ReflectionTestUtils.getField(serializedAccount, "linkAccountId")).isEqualTo(100L);
    }

    @Test
    void retrieveOneAcceptsPlusPrefixedLinkedAccountAssociationNameUsedByTheUi() {
        final DynamicDepositAccountData accountData = accountData();
        final PortfolioAccountData linkedAccount = PortfolioAccountData.lookup(101L, "SA000101");
        when(this.readPlatformService.retrieveOne(ACCOUNT_ID)).thenReturn(accountData);
        when(this.accountAssociationsReadPlatformService.retriveSavingsLinkedAssociation(ACCOUNT_ID)).thenReturn(linkedAccount);

        this.resource.retrieveOne(ACCOUNT_ID, "all", uriInfo("charges,+linkedAccount"));

        verify(this.savingsAccountChargeReadPlatformService).retrieveSavingsAccountCharges(ACCOUNT_ID, "all");
        verify(this.accountAssociationsReadPlatformService).retriveSavingsLinkedAssociation(ACCOUNT_ID);
        assertThat(ReflectionTestUtils.getField(capturedSerializedAccount(), "linkedAccount")).isSameAs(linkedAccount);
    }

    private CommandWrapper capturedCommandWrapper() {
        final ArgumentCaptor<CommandWrapper> captor = ArgumentCaptor.forClass(CommandWrapper.class);
        verify(this.commandsSourceWritePlatformService).logCommandSource(captor.capture());
        return captor.getValue();
    }

    private DynamicDepositAccountData capturedSerializedAccount() {
        final ArgumentCaptor<DynamicDepositAccountData> captor = ArgumentCaptor.forClass(DynamicDepositAccountData.class);
        verify(this.toApiJsonSerializer).serialize(any(ApiRequestJsonSerializationSettings.class), captor.capture(), anySet());
        return captor.getValue();
    }

    private DynamicDepositAccountData accountData() {
        return new DynamicDepositAccountData(ACCOUNT_ID, "DD000042", null, 1L, "Client", null, null, 2L, "Dynamic Product", null, null,
                null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, true, true, true);
    }

    private UriInfo uriInfo(final String associations) {
        final MultivaluedMap<String, String> queryParameters = new MultivaluedHashMap<>();
        queryParameters.add("associations", associations);
        final UriInfo uriInfo = mock(UriInfo.class);
        when(uriInfo.getQueryParameters()).thenReturn(queryParameters);
        return uriInfo;
    }
}
