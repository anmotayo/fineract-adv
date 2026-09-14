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

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Constructor;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class DynamicDepositAccountWithdrawalLockTest {

    @Test
    void blocksWithdrawalWhenAllowWithdrawalIsFalse() {
        final DynamicDepositAccount account = accountWithAllowWithdrawal(false);

        assertThat(account.isWithdrawalBlockedByAccountRule()).isTrue();
    }

    @Test
    void allowsWithdrawalWhenAllowWithdrawalIsTrue() {
        final DynamicDepositAccount account = accountWithAllowWithdrawal(true);

        assertThat(account.isWithdrawalBlockedByAccountRule()).isFalse();
    }

    // Build a minimal DynamicDepositAccount with only its DepositAccountDynamicDetail(allowWithdrawal=...) set -
    // mirrors the no-arg-protected-constructor + ReflectionTestUtils pattern used by
    // DynamicDepositAccountWithHoldTaxTest in this same package to construct an account without going through the
    // full assembler.
    private DynamicDepositAccount accountWithAllowWithdrawal(final boolean allowWithdrawal) {
        final DynamicDepositAccount account = createInstance(DynamicDepositAccount.class);
        final DepositAccountDynamicDetail dynamicDetail = DepositAccountDynamicDetail.createNew(account, allowWithdrawal, false);
        ReflectionTestUtils.setField(account, "dynamicDetail", dynamicDetail);

        return account;
    }

    private static <T> T createInstance(final Class<T> clazz) {
        try {
            final Constructor<T> constructor = clazz.getDeclaredConstructor();
            constructor.setAccessible(true);
            return constructor.newInstance();
        } catch (final Exception e) {
            throw new RuntimeException("Failed to create instance of " + clazz.getName(), e);
        }
    }
}
