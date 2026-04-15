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

import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.HashMap;
import org.apache.fineract.infrastructure.businessdate.domain.BusinessDateType;
import org.apache.fineract.infrastructure.core.domain.ActionContext;
import org.apache.fineract.infrastructure.core.domain.FineractPlatformTenant;
import org.apache.fineract.infrastructure.core.service.ThreadLocalContextUtil;
import org.apache.fineract.organisation.monetary.domain.MoneyHelper;

/**
 * Initializes MoneyHelper's tenant rounding mode and ThreadLocalContextUtil business dates for unit tests.
 */
public final class MoneyHelperInitializer {

    private static final String TEST_TENANT = "default";

    private MoneyHelperInitializer() {}

    public static void initialize() {
        // Set up tenant context
        FineractPlatformTenant tenant = FineractPlatformTenant.builder().id(1L).tenantIdentifier(TEST_TENANT).name("Default")
                .timezoneId("UTC").build();
        ThreadLocalContextUtil.setTenant(tenant);

        // Initialize MoneyHelper rounding mode for the test tenant
        MoneyHelper.initializeTenantRoundingMode(TEST_TENANT, RoundingMode.HALF_EVEN.ordinal());

        // Initialize ThreadLocalContextUtil business dates
        ThreadLocalContextUtil.setActionContext(ActionContext.DEFAULT);
        HashMap<BusinessDateType, LocalDate> businessDates = new HashMap<>();
        businessDates.put(BusinessDateType.BUSINESS_DATE, LocalDate.now());
        businessDates.put(BusinessDateType.COB_DATE, LocalDate.now().minusDays(1));
        ThreadLocalContextUtil.setBusinessDates(businessDates);
    }
}
