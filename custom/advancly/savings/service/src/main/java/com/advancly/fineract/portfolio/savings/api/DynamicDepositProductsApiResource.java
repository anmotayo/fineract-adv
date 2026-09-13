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

import static com.advancly.fineract.portfolio.savings.DynamicDepositApiConstants.DYNAMIC_DEPOSIT_PRODUCT_RESOURCE_NAME;
import static com.advancly.fineract.portfolio.savings.DynamicDepositApiConstants.DYNAMIC_DEPOSIT_PRODUCT_RESPONSE_DATA_PARAMETERS;

import com.advancly.fineract.portfolio.savings.data.DynamicDepositProductData;
import com.advancly.fineract.portfolio.savings.service.DynamicDepositProductReadPlatformService;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.UriInfo;
import java.util.Collection;
import org.apache.fineract.commands.domain.CommandWrapper;
import org.apache.fineract.commands.service.CommandWrapperBuilder;
import org.apache.fineract.commands.service.PortfolioCommandSourceWritePlatformService;
import org.apache.fineract.infrastructure.core.api.ApiRequestParameterHelper;
import org.apache.fineract.infrastructure.core.data.CommandProcessingResult;
import org.apache.fineract.infrastructure.core.serialization.ApiRequestJsonSerializationSettings;
import org.apache.fineract.infrastructure.core.serialization.DefaultToApiJsonSerializer;
import org.apache.fineract.infrastructure.security.service.PlatformSecurityContext;
import org.springframework.stereotype.Component;

/**
 * REST resource for the Dynamic Deposit product (deposit_type_enum = 500). A fully independent, parallel vertical slice
 * - own path, own {@code CommandWrapperBuilder.createProduct("DYNAMICDEPOSIT")}-generated entity code
 * ({@code DYNAMICDEPOSITPRODUCT}), no core changes required beyond the two documented in the implementation report.
 */
@Path("/v1/dynamicdepositproducts")
@Component
public class DynamicDepositProductsApiResource {

    private static final String PRODUCT_TYPE = "DYNAMICDEPOSIT";

    private final PlatformSecurityContext context;
    private final DynamicDepositProductReadPlatformService dynamicDepositProductReadPlatformService;
    private final DefaultToApiJsonSerializer<DynamicDepositProductData> toApiJsonSerializer;
    private final PortfolioCommandSourceWritePlatformService commandsSourceWritePlatformService;
    private final ApiRequestParameterHelper apiRequestParameterHelper;

    public DynamicDepositProductsApiResource(final PlatformSecurityContext context,
            final DynamicDepositProductReadPlatformService dynamicDepositProductReadPlatformService,
            final DefaultToApiJsonSerializer<DynamicDepositProductData> toApiJsonSerializer,
            final PortfolioCommandSourceWritePlatformService commandsSourceWritePlatformService,
            final ApiRequestParameterHelper apiRequestParameterHelper) {
        this.context = context;
        this.dynamicDepositProductReadPlatformService = dynamicDepositProductReadPlatformService;
        this.toApiJsonSerializer = toApiJsonSerializer;
        this.commandsSourceWritePlatformService = commandsSourceWritePlatformService;
        this.apiRequestParameterHelper = apiRequestParameterHelper;
    }

    @POST
    @Consumes({ MediaType.APPLICATION_JSON })
    @Produces({ MediaType.APPLICATION_JSON })
    public String create(final String apiRequestBodyAsJson) {

        final CommandWrapper commandRequest = new CommandWrapperBuilder().createProduct(PRODUCT_TYPE).withJson(apiRequestBodyAsJson)
                .build();

        final CommandProcessingResult result = this.commandsSourceWritePlatformService.logCommandSource(commandRequest);

        return this.toApiJsonSerializer.serialize(result);
    }

    @PUT
    @Path("{productId}")
    @Consumes({ MediaType.APPLICATION_JSON })
    @Produces({ MediaType.APPLICATION_JSON })
    public String update(@PathParam("productId") final Long productId, final String apiRequestBodyAsJson) {

        final CommandWrapper commandRequest = new CommandWrapperBuilder().updateProduct(PRODUCT_TYPE, productId)
                .withJson(apiRequestBodyAsJson).build();

        final CommandProcessingResult result = this.commandsSourceWritePlatformService.logCommandSource(commandRequest);

        return this.toApiJsonSerializer.serialize(result);
    }

    @DELETE
    @Path("{productId}")
    @Consumes({ MediaType.APPLICATION_JSON })
    @Produces({ MediaType.APPLICATION_JSON })
    public String delete(@PathParam("productId") final Long productId) {

        // No generic "delete product" builder method exists on CommandWrapperBuilder (only type-specific ones, e.g.
        // deleteFixedDepositProduct) - constructing CommandWrapper directly here avoids adding one to the shared
        // core builder class, keeping this feature's core footprint to the two documented file edits.
        final CommandWrapper commandRequest = new CommandWrapper(null, null, null, null, null, "DELETE", "DYNAMICDEPOSITPRODUCT", productId,
                null, "/products/" + PRODUCT_TYPE + "/" + productId, null, null, productId, null, null, null, null, null, null, null);

        final CommandProcessingResult result = this.commandsSourceWritePlatformService.logCommandSource(commandRequest);

        return this.toApiJsonSerializer.serialize(result);
    }

    @GET
    @Consumes({ MediaType.APPLICATION_JSON })
    @Produces({ MediaType.APPLICATION_JSON })
    public String retrieveAll(@Context final UriInfo uriInfo) {

        this.context.authenticatedUser().validateHasReadPermission(DYNAMIC_DEPOSIT_PRODUCT_RESOURCE_NAME);

        final Collection<DynamicDepositProductData> products = this.dynamicDepositProductReadPlatformService.retrieveAll();

        final ApiRequestJsonSerializationSettings settings = this.apiRequestParameterHelper.process(uriInfo.getQueryParameters());
        return this.toApiJsonSerializer.serialize(settings, products, DYNAMIC_DEPOSIT_PRODUCT_RESPONSE_DATA_PARAMETERS);
    }

    @GET
    @Path("{productId}")
    @Consumes({ MediaType.APPLICATION_JSON })
    @Produces({ MediaType.APPLICATION_JSON })
    public String retrieveOne(@PathParam("productId") final Long productId, @Context final UriInfo uriInfo) {

        this.context.authenticatedUser().validateHasReadPermission(DYNAMIC_DEPOSIT_PRODUCT_RESOURCE_NAME);

        DynamicDepositProductData productData = this.dynamicDepositProductReadPlatformService.retrieveOne(productId);

        final ApiRequestJsonSerializationSettings settings = this.apiRequestParameterHelper.process(uriInfo.getQueryParameters());
        if (settings.isTemplate()) {
            final DynamicDepositProductData templateData = this.dynamicDepositProductReadPlatformService.retrieveTemplate();
            productData = DynamicDepositProductData.withTemplateOptions(productData, templateData.currencyOptions(),
                    templateData.interestCompoundingPeriodTypeOptions(), templateData.interestPostingPeriodTypeOptions(),
                    templateData.interestCalculationTypeOptions(), templateData.interestCalculationDaysInYearTypeOptions(),
                    templateData.lockinPeriodFrequencyTypeOptions(), templateData.accountingRuleOptions(), templateData.chargeOptions(),
                    templateData.taxGroupOptions(), templateData.chartTemplate());
        }

        return this.toApiJsonSerializer.serialize(settings, productData, DYNAMIC_DEPOSIT_PRODUCT_RESPONSE_DATA_PARAMETERS);
    }

    @GET
    @Path("template")
    @Consumes({ MediaType.APPLICATION_JSON })
    @Produces({ MediaType.APPLICATION_JSON })
    public String retrieveTemplate(@Context final UriInfo uriInfo) {

        this.context.authenticatedUser().validateHasReadPermission(DYNAMIC_DEPOSIT_PRODUCT_RESOURCE_NAME);

        final DynamicDepositProductData templateData = this.dynamicDepositProductReadPlatformService.retrieveTemplate();

        final ApiRequestJsonSerializationSettings settings = this.apiRequestParameterHelper.process(uriInfo.getQueryParameters());
        return this.toApiJsonSerializer.serialize(settings, templateData, DYNAMIC_DEPOSIT_PRODUCT_RESPONSE_DATA_PARAMETERS);
    }

}
