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

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.advancly.fineract.portfolio.savings.domain.DynamicDepositAccountRepository;
import com.advancly.fineract.portfolio.savings.domain.SavingsAccountInterestChargeRepository;
import java.time.LocalDate;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import org.apache.fineract.infrastructure.businessdate.domain.BusinessDateType;
import org.apache.fineract.infrastructure.core.domain.FineractPlatformTenant;
import org.apache.fineract.infrastructure.core.service.ThreadLocalContextUtil;
import org.apache.fineract.infrastructure.event.business.service.BusinessEventNotifierService;
import org.apache.fineract.infrastructure.security.service.PlatformSecurityContext;
import org.apache.fineract.portfolio.savings.domain.SavingsAccount;
import org.apache.fineract.portfolio.savings.domain.SavingsAccountAssembler;
import org.apache.fineract.portfolio.savings.domain.SavingsAccountStatusType;
import org.apache.fineract.portfolio.savings.domain.SavingsAccountTransactionRepository;
import org.apache.fineract.portfolio.savings.service.SavingsAccountReadPlatformService;
import org.apache.fineract.portfolio.savings.service.SavingsAccountWritePlatformService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * Covers Task 9's migration of Dynamic Deposit's scheduled posting (previously the standalone
 * {@code DynamicDepositPostInterestTasklet}) into {@link AdvanclySavingsSchedularInterestPoster}, and the static
 * once-per-business-date claim guard ({@code DYNAMIC_DEPOSIT_CLAIMED_FOR_DATE}) that makes this safe even though
 * {@code PostInterestForSavingTasklet} creates a fresh poster instance per worker thread per batch within a single job
 * run.
 *
 * <p>
 * Each test uses its own tenant/date key so the shared static claim guard (deliberately scoped across the whole JVM,
 * not per-instance - see the poster's javadoc) cannot leak state between test methods regardless of execution order.
 */
class AdvanclySavingsSchedularInterestPosterDynamicDepositTest {

    @AfterEach
    void tearDown() {
        ThreadLocalContextUtil.reset();
    }

    @Test
    void postsEachActiveDynamicDepositAccountExactlyOncePerRunEvenAcrossMultiplePosterInstances() throws Exception {
        setTenantAndBusinessDate("tenant-posts-once", LocalDate.of(2031, 5, 12));

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

        when(dynamicDepositAccountRepository.findIdsByStatus(SavingsAccountStatusType.ACTIVE.getValue())).thenReturn(List.of(101L));
        final SavingsAccount account = mock(SavingsAccount.class);
        when(savingsAccountAssembler.assembleFrom(101L, false)).thenReturn(account);

        // Two separate poster instances, as PostInterestForSavingTasklet creates per thread per batch within one job
        // run.
        final AdvanclySavingsSchedularInterestPoster poster1 = new AdvanclySavingsSchedularInterestPoster(writePlatformService,
                jdbcTemplate, readPlatformService, securityContext, interestChargeRepository, savingsAccountTransactionRepository,
                dynamicDepositAccountRepository, savingsAccountAssembler, transactionManager, businessEventNotifierService);
        final AdvanclySavingsSchedularInterestPoster poster2 = new AdvanclySavingsSchedularInterestPoster(writePlatformService,
                jdbcTemplate, readPlatformService, securityContext, interestChargeRepository, savingsAccountTransactionRepository,
                dynamicDepositAccountRepository, savingsAccountAssembler, transactionManager, businessEventNotifierService);
        poster1.setSavingAccounts(Collections.emptyList());
        poster1.setBackdatedTxnsAllowedTill(false);
        poster2.setSavingAccounts(Collections.emptyList());
        poster2.setBackdatedTxnsAllowedTill(false);

        poster1.postInterest();
        poster2.postInterest();

        verify(writePlatformService, times(1)).postInterest(eq(account), eq(false), any(), eq(false));
        // The fetch itself must only happen once too - not just the downstream posting - since a second, wasted
        // fetch would still be a correctness smell even if nothing came of it.
        verify(dynamicDepositAccountRepository, times(1)).findIdsByStatus(SavingsAccountStatusType.ACTIVE.getValue());
    }

    /**
     * Exercises the release-on-failure branch of {@code postDynamicDepositAccountsOnce()}: a {@code RuntimeException}
     * while fetching the account id list must release the claim, so a later {@code postInterest()} call on the SAME
     * business date attempts Dynamic Deposit posting again - unlike a per-account failure inside the loop, which (per
     * the poster's javadoc) deliberately does NOT release the claim.
     */
    @Test
    void releasesTheClaimWhenFetchingAccountIdsFailsSoThatASameDayRetryAttemptsDynamicDepositAgain() throws Exception {
        setTenantAndBusinessDate("tenant-fetch-retry", LocalDate.of(2031, 5, 13));

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

        // First attempt fails while fetching the account id list itself (before the per-account loop's own
        // isolation even begins); the second attempt succeeds with no active accounts.
        when(dynamicDepositAccountRepository.findIdsByStatus(SavingsAccountStatusType.ACTIVE.getValue()))
                .thenThrow(new RuntimeException("simulated failure fetching Dynamic Deposit account ids")).thenReturn(List.of());

        final AdvanclySavingsSchedularInterestPoster poster = new AdvanclySavingsSchedularInterestPoster(writePlatformService, jdbcTemplate,
                readPlatformService, securityContext, interestChargeRepository, savingsAccountTransactionRepository,
                dynamicDepositAccountRepository, savingsAccountAssembler, transactionManager, businessEventNotifierService);
        poster.setSavingAccounts(Collections.emptyList());
        poster.setBackdatedTxnsAllowedTill(false);

        // The first call's RuntimeException propagates out of postInterest() uncalled-for by any try/catch at that
        // call site - postDynamicDepositAccountsOnce() is the very first statement in postInterest(), so this also
        // means the plain-Savings charge-application logic below it never even runs for this call. That is the
        // deliberate, documented tradeoff this task's brief specifies, not a bug.
        assertThatThrownBy(poster::postInterest).isInstanceOf(RuntimeException.class)
                .hasMessage("simulated failure fetching Dynamic Deposit account ids");

        // If the claim had NOT been released, this second call would skip the fetch entirely (findIdsByStatus called
        // only once total) and postInterest() would return normally instead of reaching the fetch again.
        poster.postInterest();

        verify(dynamicDepositAccountRepository, times(2)).findIdsByStatus(SavingsAccountStatusType.ACTIVE.getValue());
    }

    @Test
    void isolatesTheOncePerDayClaimByTenant() throws Exception {
        final LocalDate businessDate = LocalDate.of(2031, 5, 14);

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

        when(dynamicDepositAccountRepository.findIdsByStatus(SavingsAccountStatusType.ACTIVE.getValue())).thenReturn(List.of(101L))
                .thenReturn(List.of(202L));
        final SavingsAccount tenantAAccount = mock(SavingsAccount.class);
        final SavingsAccount tenantBAccount = mock(SavingsAccount.class);
        when(savingsAccountAssembler.assembleFrom(101L, false)).thenReturn(tenantAAccount);
        when(savingsAccountAssembler.assembleFrom(202L, false)).thenReturn(tenantBAccount);

        final AdvanclySavingsSchedularInterestPoster tenantAPoster1 = new AdvanclySavingsSchedularInterestPoster(writePlatformService,
                jdbcTemplate, readPlatformService, securityContext, interestChargeRepository, savingsAccountTransactionRepository,
                dynamicDepositAccountRepository, savingsAccountAssembler, transactionManager, businessEventNotifierService);
        final AdvanclySavingsSchedularInterestPoster tenantAPoster2 = new AdvanclySavingsSchedularInterestPoster(writePlatformService,
                jdbcTemplate, readPlatformService, securityContext, interestChargeRepository, savingsAccountTransactionRepository,
                dynamicDepositAccountRepository, savingsAccountAssembler, transactionManager, businessEventNotifierService);
        final AdvanclySavingsSchedularInterestPoster tenantBPoster1 = new AdvanclySavingsSchedularInterestPoster(writePlatformService,
                jdbcTemplate, readPlatformService, securityContext, interestChargeRepository, savingsAccountTransactionRepository,
                dynamicDepositAccountRepository, savingsAccountAssembler, transactionManager, businessEventNotifierService);
        final AdvanclySavingsSchedularInterestPoster tenantBPoster2 = new AdvanclySavingsSchedularInterestPoster(writePlatformService,
                jdbcTemplate, readPlatformService, securityContext, interestChargeRepository, savingsAccountTransactionRepository,
                dynamicDepositAccountRepository, savingsAccountAssembler, transactionManager, businessEventNotifierService);

        tenantAPoster1.setSavingAccounts(Collections.emptyList());
        tenantAPoster1.setBackdatedTxnsAllowedTill(false);
        tenantAPoster2.setSavingAccounts(Collections.emptyList());
        tenantAPoster2.setBackdatedTxnsAllowedTill(false);
        tenantBPoster1.setSavingAccounts(Collections.emptyList());
        tenantBPoster1.setBackdatedTxnsAllowedTill(false);
        tenantBPoster2.setSavingAccounts(Collections.emptyList());
        tenantBPoster2.setBackdatedTxnsAllowedTill(false);

        setTenantAndBusinessDate("tenant-a", businessDate);
        tenantAPoster1.postInterest();
        tenantAPoster2.postInterest();

        setTenantAndBusinessDate("tenant-b", businessDate);
        tenantBPoster1.postInterest();
        tenantBPoster2.postInterest();

        verify(dynamicDepositAccountRepository, times(2)).findIdsByStatus(SavingsAccountStatusType.ACTIVE.getValue());
        verify(writePlatformService, times(1)).postInterest(eq(tenantAAccount), eq(false), any(), eq(false));
        verify(writePlatformService, times(1)).postInterest(eq(tenantBAccount), eq(false), any(), eq(false));
    }

    private static void setTenantAndBusinessDate(final String tenantIdentifier, final LocalDate businessDate) {
        ThreadLocalContextUtil.setTenant(FineractPlatformTenant.builder().id(1L).tenantIdentifier(tenantIdentifier).name(tenantIdentifier)
                .timezoneId("UTC").build());
        final HashMap<BusinessDateType, LocalDate> businessDates = new HashMap<>();
        businessDates.put(BusinessDateType.BUSINESS_DATE, businessDate);
        ThreadLocalContextUtil.setBusinessDates(businessDates);
    }
}
