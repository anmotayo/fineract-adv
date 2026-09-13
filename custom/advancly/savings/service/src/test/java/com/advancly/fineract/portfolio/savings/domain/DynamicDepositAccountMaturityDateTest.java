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
import java.math.BigDecimal;
import java.time.LocalDate;
import org.apache.fineract.portfolio.savings.SavingsPeriodFrequencyType;
import org.apache.fineract.portfolio.savings.domain.DepositAccountTermAndPreClosure;
import org.apache.fineract.portfolio.savings.domain.DepositPreClosureDetail;
import org.apache.fineract.portfolio.savings.domain.DepositTermDetail;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class DynamicDepositAccountMaturityDateTest {

    private static final LocalDate SUBMITTED_ON = LocalDate.of(2026, 1, 15);

    @Test
    void maturityDateIsSubmittedOnDatePlusDepositPeriodInMonths() {
        final DynamicDepositAccount account = accountWithTerm(6, SavingsPeriodFrequencyType.MONTHS);

        assertThat(account.calculateMaturityDate()).isEqualTo(LocalDate.of(2026, 7, 15));
    }

    @Test
    void maturityDateHonoursDaysWeeksAndYearsFrequencies() {
        assertThat(accountWithTerm(30, SavingsPeriodFrequencyType.DAYS).calculateMaturityDate()).isEqualTo(LocalDate.of(2026, 2, 14));
        assertThat(accountWithTerm(4, SavingsPeriodFrequencyType.WEEKS).calculateMaturityDate()).isEqualTo(LocalDate.of(2026, 2, 12));
        assertThat(accountWithTerm(2, SavingsPeriodFrequencyType.YEARS).calculateMaturityDate()).isEqualTo(LocalDate.of(2028, 1, 15));
    }

    @Test
    void maturityDateIsAnchoredOnActivationDateOnceActivated() {
        final DynamicDepositAccount account = accountWithTerm(6, SavingsPeriodFrequencyType.MONTHS);
        ReflectionTestUtils.setField(account, "activatedOnDate", LocalDate.of(2026, 2, 1));

        assertThat(account.calculateMaturityDate()).isEqualTo(LocalDate.of(2026, 8, 1));
    }

    @Test
    void updateMaturityDatePersistsTheDateAndPreservesTheExistingMaturityAmount() {
        final DynamicDepositAccount account = accountWithTerm(6, SavingsPeriodFrequencyType.MONTHS);

        account.updateMaturityDate();

        assertThat(account.maturityDate()).isEqualTo(LocalDate.of(2026, 7, 15));
        assertThat(account.accountTermAndPreClosure().maturityAmount()).isNull();
    }

    @Test
    void updateMaturityDateIsANoOpWhenTheTermIsUnusable() {
        final DynamicDepositAccount account = accountWithTerm(null, SavingsPeriodFrequencyType.MONTHS);

        account.updateMaturityDate();

        assertThat(account.calculateMaturityDate()).isNull();
        assertThat(account.maturityDate()).isNull();
    }

    @Test
    void isEarlyWithdrawalIsTrueStrictlyBeforeMaturityAndFalseOnOrAfterIt() {
        final DynamicDepositAccount account = accountWithTerm(6, SavingsPeriodFrequencyType.MONTHS);
        account.updateMaturityDate();

        assertThat(account.isEarlyWithdrawal(LocalDate.of(2026, 7, 14))).isTrue();
        assertThat(account.isEarlyWithdrawal(LocalDate.of(2026, 7, 15))).isFalse();
        assertThat(account.isEarlyWithdrawal(LocalDate.of(2026, 7, 16))).isFalse();
    }

    @Test
    void isEarlyWithdrawalIsFalseWhenNoMaturityDateHasBeenComputed() {
        final DynamicDepositAccount account = accountWithTerm(6, SavingsPeriodFrequencyType.MONTHS);

        assertThat(account.isEarlyWithdrawal(LocalDate.of(2026, 2, 1))).isFalse();
    }

    private DynamicDepositAccount accountWithTerm(final Integer depositPeriod, final SavingsPeriodFrequencyType frequencyType) {
        final DepositAccountTermAndPreClosure term = DepositAccountTermAndPreClosure.createNew(
                DepositPreClosureDetail.createFrom(false, null, null),
                DepositTermDetail.createFrom(depositPeriod, depositPeriod, frequencyType, frequencyType, null, null), null,
                BigDecimal.valueOf(100000), null, null, depositPeriod, frequencyType, null, null, false, null, null);

        final DynamicDepositAccount account = createInstance(DynamicDepositAccount.class);
        ReflectionTestUtils.setField(account, "submittedOnDate", SUBMITTED_ON);
        ReflectionTestUtils.setField(account, "accountTermAndPreClosure", term);
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
