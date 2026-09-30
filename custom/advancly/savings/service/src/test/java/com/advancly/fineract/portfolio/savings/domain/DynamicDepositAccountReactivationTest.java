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

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.math.BigDecimal;
import org.apache.fineract.portfolio.savings.domain.SavingsAccountStatusType;
import org.junit.jupiter.api.Test;

class DynamicDepositAccountReactivationTest {

    private static DynamicDepositAccount account(final SavingsAccountStatusType status, final BigDecimal balance) {
        final DynamicDepositAccount account = mock(DynamicDepositAccount.class, CALLS_REAL_METHODS);
        doReturn(status).when(account).getStatus();
        doReturn(balance).when(account).getAccountBalance();
        return account;
    }

    @Test
    void aPrematurelyClosedAccountWithABalanceIsReactivated() {
        final DynamicDepositAccount account = account(SavingsAccountStatusType.PRE_MATURE_CLOSURE, BigDecimal.TEN);

        account.activateAccountBasedOnBalance();

        verify(account).setStatus(SavingsAccountStatusType.ACTIVE.getValue());
    }

    @Test
    void aPrematurelyClosedAccountWithAZeroBalanceStaysClosed() {
        final DynamicDepositAccount account = account(SavingsAccountStatusType.PRE_MATURE_CLOSURE, BigDecimal.ZERO);

        account.activateAccountBasedOnBalance();

        verify(account, never()).setStatus(anyInt());
    }

    @Test
    void anActiveAccountIsLeftAlone() {
        final DynamicDepositAccount account = account(SavingsAccountStatusType.ACTIVE, BigDecimal.TEN);

        account.activateAccountBasedOnBalance();

        verify(account, never()).setStatus(anyInt());
    }
}
