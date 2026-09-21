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
package org.apache.fineract.portfolio.savings.data;

import static org.assertj.core.api.Assertions.assertThat;

import org.apache.fineract.accounting.common.AccountingRuleType;
import org.junit.jupiter.api.Test;

class SavingsProductDataTest {

    @Test
    void lookupProductHasNoAccountingEnabled() {
        final SavingsProductData product = SavingsProductData.lookup(1L, "Savings");

        assertThat(product.hasAccountingEnabled()).isFalse();
        assertThat(product.accountingRuleTypeId()).isEqualTo(AccountingRuleType.NONE.getValue());
        assertThat(product.isCashBasedAccountingEnabled()).isFalse();
        assertThat(product.isAccrualBasedAccountingEnabled()).isFalse();
        assertThat(product.isUpfrontAccrualAccounting()).isFalse();
        assertThat(product.isPeriodicAccrualAccounting()).isFalse();
    }

    @Test
    void accountingTypeChecksUseAccountingRuleId() {
        final SavingsProductData product = SavingsProductData.createForInterestPosting(1L,
                AccountingRuleType.ACCRUAL_PERIODIC.toEnumOptionData());

        assertThat(product.hasAccountingEnabled()).isTrue();
        assertThat(product.accountingRuleTypeId()).isEqualTo(AccountingRuleType.ACCRUAL_PERIODIC.getValue());
        assertThat(product.isCashBasedAccountingEnabled()).isFalse();
        assertThat(product.isAccrualBasedAccountingEnabled()).isTrue();
        assertThat(product.isUpfrontAccrualAccounting()).isFalse();
        assertThat(product.isPeriodicAccrualAccounting()).isTrue();
    }
}
