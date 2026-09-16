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

import com.advancly.fineract.portfolio.savings.data.InterestCalculationData;
import java.math.BigDecimal;

public interface InterestCalculationReadPlatformService {

    /**
     * Read-only interest-calculation preview for any savings-family account (plain Savings, Fixed Deposit, Recurring
     * Deposit, Dynamic Deposit). Never persists anything, regardless of the account type or whether a simulated
     * top-up/withdrawal is supplied.
     *
     * @param savingsAccountId
     *            the account to preview
     * @param topUpAmount
     *            optional - simulates an additional deposit of this amount, dated today, before running the
     *            calculation
     * @param withdrawalAmount
     *            optional - simulates a withdrawal of this amount, dated today, before running the calculation
     */
    InterestCalculationData calculate(Long savingsAccountId, BigDecimal topUpAmount, BigDecimal withdrawalAmount);
}
