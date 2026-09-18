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

import java.math.BigDecimal;

/**
 * Carries a single withdrawal request's early-withdrawal charge percentage override across the Advancly wrapper into
 * delegated core withdrawal flows. Dynamic Deposit withdrawals intentionally route through core so their entity-level
 * hooks run, and core loads a fresh account instance; this context lets that fresh instance resolve the same request
 * override without keeping Advancly-specific parsing in core.
 */
public final class EarlyWithdrawalChargePercentageOverrideContext {

    private static final ThreadLocal<BigDecimal> CURRENT = new ThreadLocal<>();

    private EarlyWithdrawalChargePercentageOverrideContext() {}

    public static void set(final BigDecimal percentage) {
        CURRENT.set(percentage);
    }

    public static BigDecimal get() {
        return CURRENT.get();
    }

    public static void clear() {
        CURRENT.remove();
    }
}
