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

import java.time.LocalDate;
import java.util.HashMap;
import org.apache.fineract.infrastructure.businessdate.domain.BusinessDateType;
import org.apache.fineract.infrastructure.configuration.domain.ConfigurationDomainService;
import org.apache.fineract.infrastructure.core.domain.ActionContext;
import org.apache.fineract.infrastructure.core.service.ThreadLocalContextUtil;
import org.apache.fineract.organisation.monetary.domain.MoneyHelper;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Initializes MoneyHelper's static ConfigurationDomainService and ThreadLocalContextUtil business dates for unit tests.
 */
public final class MoneyHelperInitializer {

    private MoneyHelperInitializer() {}

    public static void initialize() {
        // Initialize MoneyHelper
        ConfigurationDomainService mockConfig = Mockito.mock(ConfigurationDomainService.class);
        Mockito.lenient().when(mockConfig.getRoundingMode()).thenReturn(6); // RoundingMode.HALF_EVEN.ordinal()
        ReflectionTestUtils.setField(MoneyHelper.class, "staticConfigurationDomainService", mockConfig);
        ReflectionTestUtils.setField(MoneyHelper.class, "roundingMode", null);

        // Initialize ThreadLocalContextUtil business dates
        ThreadLocalContextUtil.setActionContext(ActionContext.DEFAULT);
        HashMap<BusinessDateType, LocalDate> businessDates = new HashMap<>();
        businessDates.put(BusinessDateType.BUSINESS_DATE, LocalDate.now());
        businessDates.put(BusinessDateType.COB_DATE, LocalDate.now().minusDays(1));
        ThreadLocalContextUtil.setBusinessDates(businessDates);
    }
}
