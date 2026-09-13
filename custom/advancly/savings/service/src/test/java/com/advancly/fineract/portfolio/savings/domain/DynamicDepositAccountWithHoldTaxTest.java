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
import org.apache.fineract.portfolio.savings.DepositAccountType;
import org.apache.fineract.portfolio.savings.WithHoldTaxPostingType;
import org.apache.fineract.portfolio.savings.domain.DepositAccountTermAndPreClosure;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Regression test for the withholding-tax regression introduced alongside the
 * {@code DynamicDepositAccount#depositAccountType()} fix (task 1 of the interest-based-charges plan): before that fix,
 * {@code depositAccountType()} wrongly returned {@code SAVINGS_DEPOSIT}, so
 * {@code SavingsAccount#isWithHoldTaxApplicable} short-circuited to {@code true} via its
 * {@code depositAccountType().isSavingsDeposit()} disjunct regardless of the account's actual WHT-posting-type config.
 * Once {@code depositAccountType()} correctly reports {@code DYNAMIC_DEPOSIT}, that disjunct becomes {@code false}, and
 * without an override reading the account's configured {@code withHoldTaxPostingType} (mirroring
 * {@code FixedDepositAccount}/{@code RecurringDepositAccount}), withholding tax would be silently disabled for every
 * Dynamic Deposit account.
 */
class DynamicDepositAccountWithHoldTaxTest {

    @Test
    void withHoldTaxEnabledAndInterestPostingConfigured_isApplicable() {
        final DynamicDepositAccount account = buildAccount(true, WithHoldTaxPostingType.INTEREST_POSTING);

        assertThat(account.depositAccountType()).isEqualTo(DepositAccountType.DYNAMIC_DEPOSIT);
        assertThat(account.withHoldTaxPostingType()).isEqualTo(WithHoldTaxPostingType.INTEREST_POSTING);
        assertThat(account.isWithHoldTaxApplicable(account.withHoldTaxPostingType())).isTrue();
    }

    @Test
    void withHoldTaxDisabled_isNeverApplicableEvenWithPostingTypeConfigured() {
        final DynamicDepositAccount account = buildAccount(false, WithHoldTaxPostingType.INTEREST_POSTING);

        assertThat(account.isWithHoldTaxApplicable(account.withHoldTaxPostingType())).isFalse();
    }

    @Test
    void withHoldTaxEnabledButNoPostingTypeConfigured_isNotApplicable() {
        final DynamicDepositAccount account = buildAccount(true, null);

        assertThat(account.withHoldTaxPostingType()).isNull();
        assertThat(account.isWithHoldTaxApplicable(account.withHoldTaxPostingType())).isFalse();
    }

    @Test
    void withHoldTaxEnabledAndMaturityPostingConfigured_isNotApplicable() {
        // Only INTEREST_POSTING makes isWithHoldTaxApplicable true for a non-SAVINGS_DEPOSIT account - MATURITY
        // withholding is handled through a different path (account closure), not interest posting.
        final DynamicDepositAccount account = buildAccount(true, WithHoldTaxPostingType.MATURITY);

        assertThat(account.isWithHoldTaxApplicable(account.withHoldTaxPostingType())).isFalse();
    }

    private DynamicDepositAccount buildAccount(final boolean withHoldTax, final WithHoldTaxPostingType withHoldTaxPostingType) {
        final DynamicDepositAccount account = createInstance(DynamicDepositAccount.class);
        account.setWithHoldTax(withHoldTax);

        final DepositAccountTermAndPreClosure accountTermAndPreClosure = DepositAccountTermAndPreClosure.createNew(null, null, null, null,
                null, null, null, null, null, null, false, null, withHoldTaxPostingType);
        ReflectionTestUtils.setField(account, "accountTermAndPreClosure", accountTermAndPreClosure);

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
