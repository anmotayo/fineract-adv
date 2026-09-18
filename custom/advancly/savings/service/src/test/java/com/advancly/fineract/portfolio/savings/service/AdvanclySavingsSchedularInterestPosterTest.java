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
import static org.mockito.Mockito.when;

import com.advancly.fineract.portfolio.savings.domain.DynamicDepositAccountRepository;
import com.advancly.fineract.portfolio.savings.domain.SavingsAccountInterestChargeRepository;
import com.advancly.fineract.portfolio.savings.testutil.MoneyHelperInitializer;
import java.util.Collections;
import java.util.List;
import org.apache.fineract.infrastructure.event.business.service.BusinessEventNotifierService;
import org.apache.fineract.infrastructure.security.service.PlatformSecurityContext;
import org.apache.fineract.portfolio.savings.data.SavingsAccountData;
import org.apache.fineract.portfolio.savings.domain.SavingsAccountAssembler;
import org.apache.fineract.portfolio.savings.domain.SavingsAccountStatusType;
import org.apache.fineract.portfolio.savings.domain.SavingsAccountTransactionRepository;
import org.apache.fineract.portfolio.savings.service.SavingsAccountReadPlatformService;
import org.apache.fineract.portfolio.savings.service.SavingsAccountWritePlatformService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

class AdvanclySavingsSchedularInterestPosterTest {

    @BeforeEach
    void setUp() {
        // Also satisfies postInterest()'s new (Task 9) unconditional postDynamicDepositAccountsOnce() call at the top
        // of the method, which needs a business date - see MoneyHelperInitializer's javadoc.
        MoneyHelperInitializer.initialize();
    }

    @Test
    void doesNothingWhenThereAreNoSavingsAccounts() throws Exception {
        final SavingsAccountWritePlatformService writePlatformService = mock(SavingsAccountWritePlatformService.class);
        final JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        final SavingsAccountReadPlatformService readPlatformService = mock(SavingsAccountReadPlatformService.class);
        final PlatformSecurityContext securityContext = mock(PlatformSecurityContext.class);
        final SavingsAccountInterestChargeRepository interestChargeRepository = mock(SavingsAccountInterestChargeRepository.class);
        final SavingsAccountTransactionRepository savingsAccountTransactionRepository = mock(SavingsAccountTransactionRepository.class);
        final DynamicDepositAccountRepository dynamicDepositAccountRepository = mock(DynamicDepositAccountRepository.class);
        final SavingsAccountAssembler savingsAccountAssembler = mock(SavingsAccountAssembler.class);
        final PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
        final BusinessEventNotifierService businessEventNotifierService = mock(BusinessEventNotifierService.class);
        // Task 9's DD guard is static and keyed by business date, so it may already be claimed by another test in
        // this JVM sharing the same (MoneyHelperInitializer-provided) business date - in that case this stub simply
        // goes unconsulted, which is harmless. Either way, no Dynamic Deposit account exists for this test.
        when(dynamicDepositAccountRepository.findIdsByStatus(SavingsAccountStatusType.ACTIVE.getValue())).thenReturn(List.of());

        final AdvanclySavingsSchedularInterestPoster poster = new AdvanclySavingsSchedularInterestPoster(writePlatformService, jdbcTemplate,
                readPlatformService, securityContext, interestChargeRepository, savingsAccountTransactionRepository,
                dynamicDepositAccountRepository, savingsAccountAssembler, transactionManager, businessEventNotifierService);
        poster.setSavingAccounts(Collections.<SavingsAccountData>emptyList());
        poster.setBackdatedTxnsAllowedTill(false);

        poster.postInterest();

        verifyNoInteractions(writePlatformService, jdbcTemplate, securityContext, interestChargeRepository,
                savingsAccountTransactionRepository);
        // No Dynamic Deposit accounts either, regardless of whether the guard let this call through.
        verifyNoInteractions(savingsAccountAssembler, businessEventNotifierService);
    }
}
