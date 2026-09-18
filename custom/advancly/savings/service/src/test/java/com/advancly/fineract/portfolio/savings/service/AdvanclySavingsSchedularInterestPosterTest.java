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
package com.advancly.fineract.portfolio.savings.service;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import java.util.Collections;
import org.apache.fineract.infrastructure.security.service.PlatformSecurityContext;
import org.apache.fineract.portfolio.savings.data.SavingsAccountData;
import org.apache.fineract.portfolio.savings.service.SavingsAccountReadPlatformService;
import org.apache.fineract.portfolio.savings.service.SavingsAccountWritePlatformService;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class AdvanclySavingsSchedularInterestPosterTest {

    @Test
    void doesNothingWhenThereAreNoSavingsAccounts() throws Exception {
        final SavingsAccountWritePlatformService writePlatformService = mock(SavingsAccountWritePlatformService.class);
        final JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        final SavingsAccountReadPlatformService readPlatformService = mock(SavingsAccountReadPlatformService.class);
        final PlatformSecurityContext securityContext = mock(PlatformSecurityContext.class);

        final AdvanclySavingsSchedularInterestPoster poster = new AdvanclySavingsSchedularInterestPoster(writePlatformService, jdbcTemplate,
                readPlatformService, securityContext);
        poster.setSavingAccounts(Collections.<SavingsAccountData>emptyList());
        poster.setBackdatedTxnsAllowedTill(false);

        poster.postInterest();

        verifyNoInteractions(writePlatformService, jdbcTemplate, securityContext);
    }
}
