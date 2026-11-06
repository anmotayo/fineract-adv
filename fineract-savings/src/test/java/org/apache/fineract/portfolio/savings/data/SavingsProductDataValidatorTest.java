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

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.apache.fineract.infrastructure.core.exception.UnsupportedParameterException;
import org.apache.fineract.infrastructure.core.serialization.FromJsonHelper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class SavingsProductDataValidatorTest {

    @Mock
    private SavingsProductAccountingDataValidator savingsProductAccountingDataValidator;

    private SavingsProductDataValidator validator;
    private FromJsonHelper fromApiJsonHelper;

    @BeforeEach
    void setUp() {
        fromApiJsonHelper = new FromJsonHelper();
        validator = new SavingsProductDataValidator(fromApiJsonHelper, savingsProductAccountingDataValidator);
    }

    @Test
    void rejectsLegacyEarlyWithdrawalChargeParametersOnCreate() {
        final String json = "{" + "\"name\":\"Test Product\",\"shortName\":\"TP\",\"currencyCode\":\"USD\",\"digitsAfterDecimal\":2,"
                + "\"inMultiplesOf\":1,\"nominalAnnualInterestRate\":5,\"interestCompoundingPeriodType\":1,"
                + "\"interestPostingPeriodType\":4,\"interestCalculationType\":1,\"interestCalculationDaysInYearType\":365,"
                + "\"locale\":\"en\",\"monthDayFormat\":\"dd MMM\",\"accountingRule\":1,"
                + "\"earlyWithdrawalPenaltyEnabled\":true,\"earlyWithdrawalChargeId\":7,\"earlyWithdrawalChargeMode\":2" + "}";

        assertThatThrownBy(() -> validator.validateForCreate(json)).isInstanceOf(UnsupportedParameterException.class);
    }
}
