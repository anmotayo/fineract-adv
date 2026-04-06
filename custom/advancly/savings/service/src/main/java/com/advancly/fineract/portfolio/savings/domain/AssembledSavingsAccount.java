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

import java.util.ArrayList;
import java.util.List;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.apache.fineract.portfolio.savings.domain.SavingsAccount;
import org.apache.fineract.portfolio.savings.domain.SavingsAccountTransaction;

/**
 * Lightweight wrapper that carries the assembled SavingsAccount along with interest/overdraft transactions that were
 * loaded separately (not attached to the entity's transaction list). This avoids modifying the JPA entity for
 * custom-module-only concerns.
 */
@Getter
@RequiredArgsConstructor
public class AssembledSavingsAccount {

    private final SavingsAccount account;
    private final List<SavingsAccountTransaction> interestAndOverdraftTransactions;
    private final SavingsAccountTransaction lastNonReversedTransaction;

    public static AssembledSavingsAccount of(SavingsAccount account, List<SavingsAccountTransaction> interestAndOverdraftTransactions) {
        return new AssembledSavingsAccount(account,
                interestAndOverdraftTransactions != null ? interestAndOverdraftTransactions : new ArrayList<>(), null);
    }

    public static AssembledSavingsAccount of(SavingsAccount account, List<SavingsAccountTransaction> interestAndOverdraftTransactions,
            SavingsAccountTransaction lastNonReversedTransaction) {
        return new AssembledSavingsAccount(account,
                interestAndOverdraftTransactions != null ? interestAndOverdraftTransactions : new ArrayList<>(),
                lastNonReversedTransaction);
    }
}
