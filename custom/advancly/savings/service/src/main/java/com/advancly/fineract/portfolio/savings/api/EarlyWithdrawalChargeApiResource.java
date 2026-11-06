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
 * Read-only early-withdrawal-charge preview - given an account and a prospective {@code withdrawalDate} (and, for a
 * custom-period charge rule, an explicit {@code selectedFromDate}/{@code selectedToDate}), returns the charge a
 * withdrawal on that date would incur under the account's product's early-withdrawal interest-charge rule, without ever
 * posting interest, applying a charge, or persisting anything. Is a {@code POST} (rather than a query-param
 * {@code GET}) purely so the optional custom-period selected-period parameters have somewhere natural to live.
 *
 * The request body may also carry an optional {@code earlyWithdrawalChargePercentage}, which overrides the percentage
 * configured on the charge (and any percentage override stored on the account) for this preview only - so a caller can
 * quote the charge at a percentage it is considering, exactly as the withdrawal itself can be submitted with one. It
 * must be between 0 and 100 when supplied; omitting it leaves the configured percentage in force.
 *
 * See {@link EarlyWithdrawalChargeReadPlatformService} for what it computes.
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
