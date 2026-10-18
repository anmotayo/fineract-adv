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

import com.advancly.fineract.portfolio.savings.service.DynamicDepositInvestedAmountRecomputeService;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.apache.fineract.infrastructure.core.serialization.DefaultToApiJsonSerializer;
import org.apache.fineract.infrastructure.core.serialization.FromJsonHelper;
import org.apache.fineract.infrastructure.security.service.PlatformSecurityContext;
import org.springframework.stereotype.Component;

/**
 * {@code POST /v1/dynamicdepositaccounts/investedamountrecompute} - re-derives the invested amount and resolved rate of
 * existing Dynamic Deposit accounts (see {@link DynamicDepositInvestedAmountRecomputeService}).
 *
 * Body: {@code {"dryRun": true, "accountIds": [12, 13]}}. {@code dryRun} defaults to {@code true}, so nothing is
 * changed unless {@code "dryRun": false} is sent deliberately; {@code accountIds} defaults to every Dynamic Deposit
 * account.
 *
 * Administrative: it needs the {@value #PERMISSION} permission, which nothing grants by default, so only a user with
 * {@code ALL_FUNCTIONS} can run it unless the permission is deliberately given to a role.
 */
@Path("/v1/dynamicdepositaccounts/investedamountrecompute")
@Component
@RequiredArgsConstructor
public class DynamicDepositInvestedAmountRecomputeApiResource {

    static final String PERMISSION = "RECOMPUTE_INVESTEDAMOUNT_DYNAMICDEPOSITACCOUNT";

    private final PlatformSecurityContext context;
    private final DynamicDepositInvestedAmountRecomputeService recomputeService;
    private final DefaultToApiJsonSerializer<DynamicDepositInvestedAmountRecomputeService.Result> toApiJsonSerializer;
    private final FromJsonHelper fromJsonHelper;

    @POST
    @Consumes({ MediaType.APPLICATION_JSON })
    @Produces({ MediaType.APPLICATION_JSON })
    public String recompute(final String apiRequestBodyAsJson) {
        this.context.authenticatedUser().validateHasPermissionTo(PERMISSION);

        boolean dryRun = true;
        final List<Long> accountIds = new ArrayList<>();
        if (apiRequestBodyAsJson != null && !apiRequestBodyAsJson.isBlank()) {
            final JsonElement element = this.fromJsonHelper.parse(apiRequestBodyAsJson);
            if (element.isJsonObject()) {
                final JsonObject body = element.getAsJsonObject();
                if (body.has("dryRun") && !body.get("dryRun").isJsonNull()) {
                    dryRun = body.get("dryRun").getAsBoolean();
                }
                if (body.has("accountIds") && body.get("accountIds").isJsonArray()) {
                    body.getAsJsonArray("accountIds").forEach(id -> accountIds.add(id.getAsLong()));
                }
            }
        }
        return this.toApiJsonSerializer.serialize(this.recomputeService.run(accountIds, dryRun));
    }
}
