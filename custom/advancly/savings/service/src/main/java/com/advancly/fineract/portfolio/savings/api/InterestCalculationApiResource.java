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

import com.advancly.fineract.portfolio.savings.data.InterestCalculationData;
import com.advancly.fineract.portfolio.savings.service.InterestCalculationReadPlatformService;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import java.math.BigDecimal;
import lombok.RequiredArgsConstructor;
import org.apache.fineract.infrastructure.core.serialization.DefaultToApiJsonSerializer;
import org.springframework.stereotype.Component;

/**
 * Read-only interest-calculation preview, spanning plain Savings, Fixed Deposit, Recurring Deposit and Dynamic
 * Deposit accounts - see {@link InterestCalculationReadPlatformService} for what it computes and the guarantee that
 * it never persists anything, including when {@code topUpAmount}/{@code withdrawalAmount} simulate a hypothetical
 * transaction.
 */
@Path("/v1/interestcalculation")
@Component
@RequiredArgsConstructor
public class InterestCalculationApiResource {

    private final InterestCalculationReadPlatformService interestCalculationReadPlatformService;
    private final DefaultToApiJsonSerializer<InterestCalculationData> toApiJsonSerializer;

    @GET
    @Path("{savingsAccountId}")
    @Consumes({ MediaType.APPLICATION_JSON })
    @Produces({ MediaType.APPLICATION_JSON })
    public String calculate(@PathParam("savingsAccountId") final Long savingsAccountId,
            @QueryParam("topUpAmount") final BigDecimal topUpAmount, @QueryParam("withdrawalAmount") final BigDecimal withdrawalAmount) {

        final InterestCalculationData result = this.interestCalculationReadPlatformService.calculate(savingsAccountId, topUpAmount,
                withdrawalAmount);

        return this.toApiJsonSerializer.serialize(result);
    }
}
