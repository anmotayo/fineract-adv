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
package com.advancly.fineract.portfolio.savings.service;

import com.advancly.fineract.portfolio.savings.data.DynamicDepositClosureData;
import org.apache.fineract.infrastructure.core.api.JsonQuery;

/**
 * Read side of closing a Dynamic Deposit: the template the {@code close} screen is built from, and the preview of what
 * a premature closure on a chosen date would pay out. Neither writes anything.
 */
public interface DynamicDepositAccountClosureReadPlatformService {

    /** Options for closing the account at maturity, with the projected payout at maturity (or today, once matured). */
    DynamicDepositClosureData retrieveClosureTemplate(Long accountId);

    /**
     * What a premature closure dated {@code closedOnDate} (request body, with an optional
     * {@code earlyWithdrawalChargePercentage}) would pay out after the final interest and the early-withdrawal
     * forfeiture - exactly what {@code prematureClose} on the same date would withdraw or transfer.
     */
    DynamicDepositClosureData calculatePrematureAmount(Long accountId, JsonQuery query);
}
