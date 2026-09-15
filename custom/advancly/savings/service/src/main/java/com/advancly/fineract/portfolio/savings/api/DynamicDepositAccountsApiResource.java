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

import static com.advancly.fineract.portfolio.savings.DynamicDepositApiConstants.DYNAMIC_DEPOSIT_ACCOUNT_RESOURCE_NAME;
import static com.advancly.fineract.portfolio.savings.DynamicDepositApiConstants.DYNAMIC_DEPOSIT_ACCOUNT_RESPONSE_DATA_PARAMETERS;

import com.advancly.fineract.portfolio.savings.data.DynamicDepositAccountData;
import com.advancly.fineract.portfolio.savings.data.DynamicDepositInterestSummaryData;
import com.advancly.fineract.portfolio.savings.service.DynamicDepositAccountReadPlatformService;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.UriInfo;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.Set;
import org.apache.commons.lang3.StringUtils;
import org.apache.fineract.commands.domain.CommandWrapper;
import org.apache.fineract.commands.service.CommandWrapperBuilder;
import org.apache.fineract.commands.service.PortfolioCommandSourceWritePlatformService;
import org.apache.fineract.infrastructure.core.api.ApiParameterHelper;
import org.apache.fineract.infrastructure.core.api.ApiRequestParameterHelper;
import org.apache.fineract.infrastructure.core.data.CommandProcessingResult;
import org.apache.fineract.infrastructure.core.exception.UnrecognizedQueryParamException;
import org.apache.fineract.infrastructure.core.serialization.ApiRequestJsonSerializationSettings;
import org.apache.fineract.infrastructure.core.serialization.DefaultToApiJsonSerializer;
import org.apache.fineract.infrastructure.core.service.CommandParameterUtil;
import org.apache.fineract.infrastructure.security.service.PlatformSecurityContext;
import org.apache.fineract.portfolio.account.data.PortfolioAccountData;
import org.apache.fineract.portfolio.account.service.AccountAssociationsReadPlatformService;
import org.apache.fineract.portfolio.savings.DepositAccountType;
import org.apache.fineract.portfolio.savings.SavingsApiConstants;
import org.apache.fineract.portfolio.savings.data.SavingsAccountChargeData;
import org.apache.fineract.portfolio.savings.data.SavingsAccountTransactionData;
import org.apache.fineract.portfolio.savings.service.DepositAccountReadPlatformService;
import org.apache.fineract.portfolio.savings.service.SavingsAccountChargeReadPlatformService;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;

/**
 * REST resource for the Dynamic Deposit account (deposit_type_enum = 500). Lifecycle-only for Phase 1 (create / update
 * / delete / submit-application transitions) - deposit/withdrawal/interest-posting/close are later-phase work, see the
 * implementation plan.
 */
@Path("/v1/dynamicdepositaccounts")
@Component
public class DynamicDepositAccountsApiResource {

    private static final String ACCOUNT_TYPE = "DYNAMICDEPOSIT";

    private final PlatformSecurityContext context;
    private final DynamicDepositAccountReadPlatformService dynamicDepositAccountReadPlatformService;
    private final DefaultToApiJsonSerializer<DynamicDepositAccountData> toApiJsonSerializer;
    private final PortfolioCommandSourceWritePlatformService commandsSourceWritePlatformService;
    private final ApiRequestParameterHelper apiRequestParameterHelper;
    private final DepositAccountReadPlatformService depositAccountReadPlatformService;
    private final SavingsAccountChargeReadPlatformService savingsAccountChargeReadPlatformService;
    private final AccountAssociationsReadPlatformService accountAssociationsReadPlatformService;

    public DynamicDepositAccountsApiResource(final PlatformSecurityContext context,
            final DynamicDepositAccountReadPlatformService dynamicDepositAccountReadPlatformService,
            final DefaultToApiJsonSerializer<DynamicDepositAccountData> toApiJsonSerializer,
            final PortfolioCommandSourceWritePlatformService commandsSourceWritePlatformService,
            final ApiRequestParameterHelper apiRequestParameterHelper,
            final DepositAccountReadPlatformService depositAccountReadPlatformService,
            final SavingsAccountChargeReadPlatformService savingsAccountChargeReadPlatformService,
            final AccountAssociationsReadPlatformService accountAssociationsReadPlatformService) {
        this.context = context;
        this.dynamicDepositAccountReadPlatformService = dynamicDepositAccountReadPlatformService;
        this.toApiJsonSerializer = toApiJsonSerializer;
        this.commandsSourceWritePlatformService = commandsSourceWritePlatformService;
        this.apiRequestParameterHelper = apiRequestParameterHelper;
        this.depositAccountReadPlatformService = depositAccountReadPlatformService;
        this.savingsAccountChargeReadPlatformService = savingsAccountChargeReadPlatformService;
        this.accountAssociationsReadPlatformService = accountAssociationsReadPlatformService;
    }

    @POST
    @Consumes({ MediaType.APPLICATION_JSON })
    @Produces({ MediaType.APPLICATION_JSON })
    public String submitApplication(final String apiRequestBodyAsJson) {

        final CommandWrapper commandRequest = new CommandWrapperBuilder().createAccount(ACCOUNT_TYPE).withJson(apiRequestBodyAsJson)
                .build();

        final CommandProcessingResult result = this.commandsSourceWritePlatformService.logCommandSource(commandRequest);

        return this.toApiJsonSerializer.serialize(result);
    }

    @PUT
    @Path("{accountId}")
    @Consumes({ MediaType.APPLICATION_JSON })
    @Produces({ MediaType.APPLICATION_JSON })
    public String update(@PathParam("accountId") final Long accountId, final String apiRequestBodyAsJson) {

        final CommandWrapper commandRequest = new CommandWrapperBuilder().updateAccount(ACCOUNT_TYPE, accountId)
                .withJson(apiRequestBodyAsJson).build();

        final CommandProcessingResult result = this.commandsSourceWritePlatformService.logCommandSource(commandRequest);

        return this.toApiJsonSerializer.serialize(result);
    }

    @DELETE
    @Path("{accountId}")
    @Consumes({ MediaType.APPLICATION_JSON })
    @Produces({ MediaType.APPLICATION_JSON })
    public String delete(@PathParam("accountId") final Long accountId) {

        final CommandWrapper commandRequest = new CommandWrapperBuilder().createAccountCommand(ACCOUNT_TYPE, accountId, "delete").build();

        final CommandProcessingResult result = this.commandsSourceWritePlatformService.logCommandSource(commandRequest);

        return this.toApiJsonSerializer.serialize(result);
    }

    @POST
    @Path("{accountId}")
    @Consumes({ MediaType.APPLICATION_JSON })
    @Produces({ MediaType.APPLICATION_JSON })
    public String handleCommands(@PathParam("accountId") final Long accountId, @QueryParam("command") final String commandParam,
            final String apiRequestBodyAsJson) {

        String jsonApiRequest = apiRequestBodyAsJson;
        if (StringUtils.isBlank(jsonApiRequest)) {
            jsonApiRequest = "{}";
        }

        final CommandWrapperBuilder builder = new CommandWrapperBuilder().withJson(jsonApiRequest);

        CommandWrapper commandRequest = null;
        if (CommandParameterUtil.is(commandParam, "approve")) {
            commandRequest = builder.createAccountCommand(ACCOUNT_TYPE, accountId, "approve").build();
        } else if (CommandParameterUtil.is(commandParam, "undoapproval")) {
            // actionName must read APPROVALUNDO to match the m_permission row seeded for this action (mirrors the
            // fixed deposit convention) - createAccountCommand upper-cases the raw command string it is given.
            commandRequest = builder.createAccountCommand(ACCOUNT_TYPE, accountId, "approvalundo").build();
        } else if (CommandParameterUtil.is(commandParam, "reject")) {
            commandRequest = builder.createAccountCommand(ACCOUNT_TYPE, accountId, "reject").build();
        } else if (CommandParameterUtil.is(commandParam, "withdrawnByApplicant")) {
            commandRequest = builder.createAccountCommand(ACCOUNT_TYPE, accountId, "withdraw").build();
        } else if (CommandParameterUtil.is(commandParam, "activate")) {
            commandRequest = builder.createAccountCommand(ACCOUNT_TYPE, accountId, "activate").build();
        } else if (CommandParameterUtil.is(commandParam, "calculateInterest")) {
            commandRequest = builder.createAccountCommand(ACCOUNT_TYPE, accountId, "calculateInterest").withSavingsId(accountId).build();
        } else if (CommandParameterUtil.is(commandParam, "postInterest")) {
            commandRequest = builder.createAccountCommand(ACCOUNT_TYPE, accountId, "postInterest").withSavingsId(accountId).build();
        } else if (CommandParameterUtil.is(commandParam, "close")) {
            commandRequest = builder.closeDynamicDepositAccount(accountId).build();
        } else if (CommandParameterUtil.is(commandParam, "prematureClose")) {
            commandRequest = builder.prematureCloseDynamicDepositAccount(accountId).build();
        }

        if (commandRequest == null) {
            throw new UnrecognizedQueryParamException("command", commandParam, new Object[] { "approve", "undoapproval", "reject",
                    "withdrawnByApplicant", "activate", "calculateInterest", "postInterest", "close", "prematureClose" });
        }

        final CommandProcessingResult result = this.commandsSourceWritePlatformService.logCommandSource(commandRequest);
        return this.toApiJsonSerializer.serialize(result);
    }

    @GET
    @Consumes({ MediaType.APPLICATION_JSON })
    @Produces({ MediaType.APPLICATION_JSON })
    public String retrieveAll(@Context final UriInfo uriInfo) {

        this.context.authenticatedUser().validateHasReadPermission(DYNAMIC_DEPOSIT_ACCOUNT_RESOURCE_NAME);

        final Collection<DynamicDepositAccountData> accounts = this.dynamicDepositAccountReadPlatformService.retrieveAll();

        final ApiRequestJsonSerializationSettings settings = this.apiRequestParameterHelper.process(uriInfo.getQueryParameters());
        return this.toApiJsonSerializer.serialize(settings, accounts, DYNAMIC_DEPOSIT_ACCOUNT_RESPONSE_DATA_PARAMETERS);
    }

    @GET
    @Path("{accountId}")
    @Consumes({ MediaType.APPLICATION_JSON })
    @Produces({ MediaType.APPLICATION_JSON })
    public String retrieveOne(@PathParam("accountId") final Long accountId,
            @DefaultValue("all") @QueryParam("chargeStatus") final String chargeStatus, @Context final UriInfo uriInfo) {

        this.context.authenticatedUser().validateHasReadPermission(DYNAMIC_DEPOSIT_ACCOUNT_RESOURCE_NAME);

        if (!(CommandParameterUtil.is(chargeStatus, "all") || CommandParameterUtil.is(chargeStatus, "active")
                || CommandParameterUtil.is(chargeStatus, "inactive"))) {
            throw new UnrecognizedQueryParamException("status", chargeStatus, new Object[] { "all", "active", "inactive" });
        }

        final DynamicDepositAccountData accountData = this.dynamicDepositAccountReadPlatformService.retrieveOne(accountId);
        final Set<String> mandatoryResponseParameters = new HashSet<>();
        final DynamicDepositAccountData accountDataWithAssociations = populateTemplateAndAssociations(accountId, accountData, chargeStatus,
                uriInfo, mandatoryResponseParameters);

        final ApiRequestJsonSerializationSettings settings = this.apiRequestParameterHelper.process(uriInfo.getQueryParameters(),
                mandatoryResponseParameters);
        return this.toApiJsonSerializer.serialize(settings, accountDataWithAssociations, DYNAMIC_DEPOSIT_ACCOUNT_RESPONSE_DATA_PARAMETERS);
    }

    private DynamicDepositAccountData populateTemplateAndAssociations(final Long accountId, final DynamicDepositAccountData accountData,
            final String chargeStatus, final UriInfo uriInfo, final Set<String> mandatoryResponseParameters) {
        Collection<SavingsAccountTransactionData> transactions = null;
        Collection<SavingsAccountChargeData> charges = null;
        PortfolioAccountData linkedAccount = null;

        final Set<String> associationParameters = ApiParameterHelper.extractAssociationsForResponseIfProvided(uriInfo.getQueryParameters());
        associationParameters.addAll(associationParameters.stream().filter(parameter -> parameter.startsWith("+"))
                .map(parameter -> parameter.substring(1)).toList());
        if (!associationParameters.isEmpty()) {
            if (associationParameters.contains("all")) {
                associationParameters.addAll(
                        Arrays.asList(SavingsApiConstants.transactions, SavingsApiConstants.charges, SavingsApiConstants.linkedAccount));
            }

            if (associationParameters.contains(SavingsApiConstants.transactions)) {
                mandatoryResponseParameters.add(SavingsApiConstants.transactions);
                final Collection<SavingsAccountTransactionData> currentTransactions = this.depositAccountReadPlatformService
                        .retrieveAllTransactions(DepositAccountType.DYNAMIC_DEPOSIT, accountId);
                if (!CollectionUtils.isEmpty(currentTransactions)) {
                    transactions = currentTransactions;
                }
            }

            if (associationParameters.contains(SavingsApiConstants.charges)) {
                mandatoryResponseParameters.add(SavingsApiConstants.charges);
                final Collection<SavingsAccountChargeData> currentCharges = this.savingsAccountChargeReadPlatformService
                        .retrieveSavingsAccountCharges(accountId, chargeStatus);
                if (!CollectionUtils.isEmpty(currentCharges)) {
                    charges = currentCharges;
                }
            }

            if (associationParameters.contains(SavingsApiConstants.linkedAccount)) {
                mandatoryResponseParameters.add(SavingsApiConstants.linkedAccount);
                linkedAccount = this.accountAssociationsReadPlatformService.retriveSavingsLinkedAssociation(accountId);
            }
        }

        DynamicDepositAccountData templateData = null;
        final ApiRequestJsonSerializationSettings settings = this.apiRequestParameterHelper.process(uriInfo.getQueryParameters());
        if (settings.isTemplate()) {
            templateData = this.dynamicDepositAccountReadPlatformService.retrieveTemplate();
        }

        return DynamicDepositAccountData.associationsAndTemplate(accountData, templateData, transactions, charges, linkedAccount);
    }

    @GET
    @Path("{accountId}/interest-summary")
    @Consumes({ MediaType.APPLICATION_JSON })
    @Produces({ MediaType.APPLICATION_JSON })
    public String retrieveInterestSummary(@PathParam("accountId") final Long accountId) {

        this.context.authenticatedUser().validateHasReadPermission(DYNAMIC_DEPOSIT_ACCOUNT_RESOURCE_NAME);

        final DynamicDepositInterestSummaryData summary = this.dynamicDepositAccountReadPlatformService.retrieveInterestSummary(accountId);

        return this.toApiJsonSerializer.serialize(summary);
    }

    @GET
    @Path("template")
    @Consumes({ MediaType.APPLICATION_JSON })
    @Produces({ MediaType.APPLICATION_JSON })
    public String retrieveTemplate(@Context final UriInfo uriInfo) {

        this.context.authenticatedUser().validateHasReadPermission(DYNAMIC_DEPOSIT_ACCOUNT_RESOURCE_NAME);

        final DynamicDepositAccountData templateData = this.dynamicDepositAccountReadPlatformService.retrieveTemplate();

        final ApiRequestJsonSerializationSettings settings = this.apiRequestParameterHelper.process(uriInfo.getQueryParameters());
        return this.toApiJsonSerializer.serialize(settings, templateData, DYNAMIC_DEPOSIT_ACCOUNT_RESPONSE_DATA_PARAMETERS);
    }
}
