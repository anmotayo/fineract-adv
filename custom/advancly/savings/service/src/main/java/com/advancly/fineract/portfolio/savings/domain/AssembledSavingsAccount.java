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
package com.advancly.fineract.portfolio.savings.domain;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.apache.fineract.portfolio.savings.domain.SavingsAccount;
import org.apache.fineract.portfolio.savings.domain.SavingsAccountTransaction;

/**
 * Lightweight wrapper that carries the assembled SavingsAccount along with the last balance-bearing transaction (the
 * row whose balance window needs closing when a new transaction is appended) for the O(1) append path. This is
 * deliberately not just "the last non-reversed transaction": that row may be an interest posting/accrual/overdraft
 * interest row, which core never windows (see SavingsAccountTransaction#isBalanceBearing()).
 */
@Getter
@RequiredArgsConstructor
public class AssembledSavingsAccount {

    private final SavingsAccount account;
    private final SavingsAccountTransaction lastBalanceBearingTransaction;

    public static AssembledSavingsAccount of(SavingsAccount account, SavingsAccountTransaction lastBalanceBearingTransaction) {
        return new AssembledSavingsAccount(account, lastBalanceBearingTransaction);
    }
}
