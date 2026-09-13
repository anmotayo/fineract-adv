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
 * {@code DynamicDepositAccount#depositAccountType()} fix (task 1 of the interest-based-charges plan, commit ffbb00218):
 * before that fix, {@code depositAccountType()} wrongly returned {@code SAVINGS_DEPOSIT}, so
 * {@code SavingsAccount#isWithHoldTaxApplicable} short-circuited to {@code true} regardless of any posting-type config.
 * Once {@code depositAccountType()} correctly reports {@code DYNAMIC_DEPOSIT}, that base-class logic falls back to a
 * posting-type-gated check - but Dynamic Deposit has no real posting-type configuration surface at all (see
 * {@code DynamicDepositAccount#withHoldTaxPostingType()}'s javadoc: neither the assembler nor the API constants ever
 * populate/accept one), so {@code accountTermAndPreClosure.getWithHoldTaxPostingType()} is always {@code null} for
 * every account actually created through the API. {@code DynamicDepositAccount} therefore overrides
 * {@code isWithHoldTaxApplicable(...)} to key on the {@code withHoldTax} flag alone, restoring the previously-working
 * (if accidental) behaviour without adding new API surface.
 *
 * <p>
 * {@link #buildRealisticAccount(boolean)} below constructs {@code accountTermAndPreClosure} exactly the way
 * {@code DynamicDepositAccountAssembler} does in production - {@code withHoldTaxPostingType} always {@code null} - so
 * these tests exercise the actual, reachable state of a real Dynamic Deposit account, not a hypothetical one.
 */
class DynamicDepositAccountWithHoldTaxTest {

    @Test
    void withHoldTaxEnabled_onARealisticallyConfiguredAccount_isApplicable() {
        final DynamicDepositAccount account = buildRealisticAccount(true);

        assertThat(account.depositAccountType()).isEqualTo(DepositAccountType.DYNAMIC_DEPOSIT);
        // Matches what DynamicDepositAccountAssembler actually produces - never anything else today.
        assertThat(account.withHoldTaxPostingType()).isNull();
        assertThat(account.isWithHoldTaxApplicable(account.withHoldTaxPostingType())).isTrue();
    }

    @Test
    void withHoldTaxDisabled_onARealisticallyConfiguredAccount_isNotApplicable() {
        final DynamicDepositAccount account = buildRealisticAccount(false);

        assertThat(account.isWithHoldTaxApplicable(account.withHoldTaxPostingType())).isFalse();
    }

    /**
     * Documents the current override's actual behaviour, should Dynamic Deposit ever gain real posting-type
     * configuration in a future phase: {@code isWithHoldTaxApplicable(...)} deliberately ignores its argument for this
     * class, so even a (today unreachable) non-null posting type has no effect - only the {@code withHoldTax} flag
     * decides. If a future task wires up real posting-type support, this override (and this test) is the place to
     * revisit.
     */
    @Test
    void withHoldTaxEnabled_evenIfAPostingTypeWereHypotheticallyConfigured_stillOnlyDependsOnTheFlag() {
        final DynamicDepositAccount account = createInstance(DynamicDepositAccount.class);
        account.setWithHoldTax(true);
        final DepositAccountTermAndPreClosure accountTermAndPreClosure = DepositAccountTermAndPreClosure.createNew(null, null, null, null,
                null, null, null, null, null, null, false, null, WithHoldTaxPostingType.MATURITY);
        ReflectionTestUtils.setField(account, "accountTermAndPreClosure", accountTermAndPreClosure);

        assertThat(account.withHoldTaxPostingType()).isEqualTo(WithHoldTaxPostingType.MATURITY);
        assertThat(account.isWithHoldTaxApplicable(account.withHoldTaxPostingType())).isTrue();
    }

    private DynamicDepositAccount buildRealisticAccount(final boolean withHoldTax) {
        final DynamicDepositAccount account = createInstance(DynamicDepositAccount.class);
        account.setWithHoldTax(withHoldTax);

        // Mirrors DynamicDepositAccountAssembler#assembleDynamicDepositAccount exactly: withHoldTaxPostingType is
        // always passed as null - there is no product- or account-level parameter to populate it from.
        final DepositAccountTermAndPreClosure accountTermAndPreClosure = DepositAccountTermAndPreClosure.createNew(null, null, null, null,
                null, null, null, null, null, null, false, null, null);
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
