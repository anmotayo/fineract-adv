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

import com.advancly.fineract.portfolio.savings.data.EarlyWithdrawalChargeData;
import com.advancly.fineract.portfolio.savings.service.EarlyWithdrawalChargeReadPlatformService;
import com.google.gson.JsonElement;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import lombok.RequiredArgsConstructor;
import org.apache.fineract.infrastructure.core.api.JsonQuery;
import org.apache.fineract.infrastructure.core.serialization.DefaultToApiJsonSerializer;
import org.apache.fineract.infrastructure.core.serialization.FromJsonHelper;
import org.springframework.stereotype.Component;

/**
 * Read-only early-withdrawal-charge preview. A {@code POST} so the optional custom-period dates fit in the body; an
 * optional {@code earlyWithdrawalChargePercentage} (0-100) overrides the configured percentage for this preview only.
 */
@Path("/v1/savingsaccounts/{accountId}/earlywithdrawalcharge")
@Component
@RequiredArgsConstructor
public class EarlyWithdrawalChargeApiResource {

    private final EarlyWithdrawalChargeReadPlatformService earlyWithdrawalChargeReadPlatformService;
    private final DefaultToApiJsonSerializer<EarlyWithdrawalChargeData> toApiJsonSerializer;
    private final FromJsonHelper fromJsonHelper;

    @POST
    @Consumes({ MediaType.APPLICATION_JSON })
    @Produces({ MediaType.APPLICATION_JSON })
    public String calculate(@PathParam("accountId") final Long accountId, final String apiRequestBodyAsJson) {

        final JsonElement parsedQuery = this.fromJsonHelper.parse(apiRequestBodyAsJson);
        final JsonQuery query = JsonQuery.from(apiRequestBodyAsJson, parsedQuery, this.fromJsonHelper);

        final EarlyWithdrawalChargeData result = this.earlyWithdrawalChargeReadPlatformService.calculate(accountId, query);

        return this.toApiJsonSerializer.serialize(result);
    }
}
