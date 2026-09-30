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
package com.advancly.fineract.portfolio.savings.data;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Collections;
import org.apache.fineract.portfolio.savings.data.SavingsAccountStatusEnumData;
import org.apache.fineract.portfolio.savings.data.SavingsAccountSubStatusEnumData;
import org.apache.fineract.portfolio.savings.domain.SavingsAccountSubStatusEnum;
import org.apache.fineract.portfolio.savings.service.SavingsEnumerations;
import org.junit.jupiter.api.Test;

class DynamicDepositAccountDataSubStatusTest {

    private static DynamicDepositAccountData account() {
        return new DynamicDepositAccountData(1L, "000001", null, 2L, null, null, null, 3L, null, null, (SavingsAccountStatusEnumData) null,
                null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, false, true, false);
    }

    @Test
    void subStatusIsAbsentUntilItIsSet() {
        assertThat(account().subStatus()).isNull();
    }

    @Test
    void withSubStatusExposesTheSubStatus() {
        final SavingsAccountSubStatusEnumData dormant = SavingsEnumerations.subStatus(SavingsAccountSubStatusEnum.DORMANT);

        final DynamicDepositAccountData data = DynamicDepositAccountData.withSubStatus(account(), dormant);

        assertThat(data.subStatus()).isSameAs(dormant);
        assertThat(data.subStatus().isDormant()).isTrue();
    }

    @Test
    void everyCopyFactoryKeepsTheSubStatus() {
        final SavingsAccountSubStatusEnumData block = SavingsEnumerations.subStatus(SavingsAccountSubStatusEnum.BLOCK);
        final DynamicDepositAccountData data = DynamicDepositAccountData.withSubStatus(account(), block);

        assertThat(DynamicDepositAccountData.withTemplateOptions(data, Collections.emptyList()).subStatus()).isSameAs(block);
        assertThat(DynamicDepositAccountData.withAssociations(data, Collections.emptyList(), Collections.emptyList(), null).subStatus())
                .isSameAs(block);
        assertThat(DynamicDepositAccountData.withRangeAndPostingType(data, 1, 2, null, null, null).subStatus()).isSameAs(block);
        assertThat(DynamicDepositAccountData.withChart(data, null).subStatus()).isSameAs(block);
    }
}
