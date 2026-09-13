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
import org.junit.jupiter.api.Test;

class DynamicDepositAccountDerivedChargeFieldsTest {

    @Test
    void bothDerivedFieldsReadAsZeroBeforeAnythingIsWritten() {
        final DynamicDepositAccount account = newAccount();

        assertThat(account.interestBasedChargeDerived()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(account.interestBasedChargePostedDerived()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void derivedFieldsRoundTripTheValuesWrittenToThem() {
        final DynamicDepositAccount account = newAccount();

        account.updateInterestBasedChargeDerived(new BigDecimal("12.50"));
        account.updateInterestBasedChargePostedDerived(new BigDecimal("40.00"));

        assertThat(account.interestBasedChargeDerived()).isEqualByComparingTo("12.50");
        assertThat(account.interestBasedChargePostedDerived()).isEqualByComparingTo("40.00");
    }

    @Test
    void writingNullIsTreatedAsZeroSoTheColumnsNeverGoBackToNull() {
        final DynamicDepositAccount account = newAccount();
        account.updateInterestBasedChargeDerived(new BigDecimal("12.50"));
        account.updateInterestBasedChargePostedDerived(new BigDecimal("40.00"));

        account.updateInterestBasedChargeDerived(null);
        account.updateInterestBasedChargePostedDerived(null);

        assertThat(account.interestBasedChargeDerived()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(account.interestBasedChargePostedDerived()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    private DynamicDepositAccount newAccount() {
        try {
            final Constructor<DynamicDepositAccount> constructor = DynamicDepositAccount.class.getDeclaredConstructor();
            constructor.setAccessible(true);
            return constructor.newInstance();
        } catch (final Exception e) {
            throw new RuntimeException("Failed to create instance of DynamicDepositAccount", e);
        }
    }
}
