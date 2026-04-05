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
package com.advancly.fineract.portfolio.savings.testutil;

import java.math.BigDecimal;
import java.time.LocalDate;
import org.apache.fineract.organisation.monetary.domain.MonetaryCurrency;
import org.apache.fineract.organisation.monetary.domain.Money;
import org.apache.fineract.portfolio.savings.SavingsAccountTransactionType;
import org.apache.fineract.portfolio.savings.domain.SavingsAccount;
import org.apache.fineract.portfolio.savings.domain.SavingsAccountTransaction;
import org.springframework.test.util.ReflectionTestUtils;

public class SavingsAccountTransactionTestBuilder {

    private Long id;
    private SavingsAccount savingsAccount;
    private int typeOf = SavingsAccountTransactionType.DEPOSIT.getValue();
    private LocalDate dateOf = LocalDate.now();
    private BigDecimal amount = BigDecimal.valueOf(100);
    private BigDecimal runningBalance;
    private boolean reversed = false;
    private boolean reversalTransaction = false;

    public SavingsAccountTransactionTestBuilder withId(Long id) {
        this.id = id;
        return this;
    }

    public SavingsAccountTransactionTestBuilder withSavingsAccount(SavingsAccount account) {
        this.savingsAccount = account;
        return this;
    }

    public SavingsAccountTransactionTestBuilder withType(SavingsAccountTransactionType type) {
        this.typeOf = type.getValue();
        return this;
    }

    public SavingsAccountTransactionTestBuilder withDate(LocalDate date) {
        this.dateOf = date;
        return this;
    }

    public SavingsAccountTransactionTestBuilder withAmount(BigDecimal amount) {
        this.amount = amount;
        return this;
    }

    public SavingsAccountTransactionTestBuilder withRunningBalance(BigDecimal balance) {
        this.runningBalance = balance;
        return this;
    }

    public SavingsAccountTransactionTestBuilder reversed() {
        this.reversed = true;
        return this;
    }

    public SavingsAccountTransactionTestBuilder reversalTransaction() {
        this.reversalTransaction = true;
        return this;
    }

    public SavingsAccountTransaction build() {
        MonetaryCurrency currency = new MonetaryCurrency("USD", 2, null);
        SavingsAccountTransaction txn = SavingsAccountTransaction.deposit(savingsAccount, null, null, dateOf, Money.of(currency, amount),
                SavingsAccountTransactionType.DEPOSIT, null);

        ReflectionTestUtils.setField(txn, "id", id);
        ReflectionTestUtils.setField(txn, "typeOf", typeOf);
        ReflectionTestUtils.setField(txn, "reversed", reversed);
        ReflectionTestUtils.setField(txn, "reversalTransaction", reversalTransaction);
        if (runningBalance != null) {
            txn.setRunningBalance(Money.of(currency, runningBalance));
        }
        return txn;
    }
}
