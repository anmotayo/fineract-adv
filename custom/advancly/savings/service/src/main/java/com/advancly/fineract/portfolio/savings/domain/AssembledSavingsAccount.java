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

import java.time.LocalDate;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.apache.fineract.portfolio.savings.domain.SavingsAccount;
import org.apache.fineract.portfolio.savings.domain.SavingsAccountTransaction;

/**
 * Account plus its last balance-bearing transaction for the append path. Not simply the last transaction: interest
 * postings and accruals are never windowed (see SavingsAccountTransaction#isBalanceBearing()).
 */
@Getter
@RequiredArgsConstructor
public class AssembledSavingsAccount {

    private final SavingsAccount account;
    private final SavingsAccountTransaction lastBalanceBearingTransaction;
    // Read under the account lock; null on the core path or with no transactions.
    private final LocalDate lastTransactionDate;

    public static AssembledSavingsAccount of(SavingsAccount account, SavingsAccountTransaction lastBalanceBearingTransaction) {
        return new AssembledSavingsAccount(account, lastBalanceBearingTransaction, null);
    }

    public static AssembledSavingsAccount of(SavingsAccount account, SavingsAccountTransaction lastBalanceBearingTransaction,
            LocalDate lastTransactionDate) {
        return new AssembledSavingsAccount(account, lastBalanceBearingTransaction, lastTransactionDate);
    }

    /** Re-check under the lock: the pre-lock check can miss a transaction a concurrent writer committed meanwhile. */
    public boolean isBackdated(final LocalDate transactionDate) {
        return lastTransactionDate != null && transactionDate.isBefore(lastTransactionDate);
    }
}
