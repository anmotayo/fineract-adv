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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.advancly.fineract.portfolio.savings.domain.SavingsAccountInterestChargeRepository;
import com.advancly.fineract.portfolio.savings.testutil.MoneyHelperInitializer;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.apache.fineract.infrastructure.security.service.PlatformSecurityContext;
import org.apache.fineract.portfolio.savings.DepositAccountType;
import org.apache.fineract.portfolio.savings.data.SavingsAccountData;
import org.apache.fineract.portfolio.savings.data.SavingsAccountDynamicRateData;
import org.apache.fineract.portfolio.savings.data.SavingsAccountSummaryData;
import org.apache.fineract.portfolio.savings.domain.SavingsAccount;
import org.apache.fineract.portfolio.savings.domain.SavingsAccountTransactionRepository;
import org.apache.fineract.portfolio.savings.service.SavingsAccountReadPlatformService;
import org.apache.fineract.portfolio.savings.service.SavingsAccountWritePlatformService;
import org.apache.fineract.useradministration.domain.AppUser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Covers Dynamic Deposit scheduled posting after removal of the old entity side-loop. Dynamic Deposit accounts are now
 * normal DTO batch members: the poster only bulk-loads their rate history and then calls the same
 * {@link SavingsAccountWritePlatformService#postInterest(SavingsAccountData, boolean, LocalDate, boolean)} path used by
 * the rest of the scheduled chunk.
 */
class AdvanclySavingsSchedularInterestPosterDynamicDepositTest {

    private SavingsAccountWritePlatformService writePlatformService;
    private JdbcTemplate jdbcTemplate;
    private SavingsAccountReadPlatformService readPlatformService;
    private PlatformSecurityContext securityContext;
    private SavingsAccountInterestChargeRepository interestChargeRepository;
    private SavingsAccountTransactionRepository savingsAccountTransactionRepository;
    private DynamicDepositScheduledRateHistoryReadPlatformService dynamicRateHistoryReadPlatformService;

    @BeforeEach
    void setUp() {
        MoneyHelperInitializer.initialize();
        this.writePlatformService = mock(SavingsAccountWritePlatformService.class);
        this.jdbcTemplate = mock(JdbcTemplate.class);
        this.readPlatformService = mock(SavingsAccountReadPlatformService.class);
        this.securityContext = mock(PlatformSecurityContext.class);
        this.interestChargeRepository = mock(SavingsAccountInterestChargeRepository.class);
        this.savingsAccountTransactionRepository = mock(SavingsAccountTransactionRepository.class);
        this.dynamicRateHistoryReadPlatformService = mock(DynamicDepositScheduledRateHistoryReadPlatformService.class);
        stubAuthenticatedUser();
    }

    @Test
    void attachesDynamicDepositRateHistoryInBulkAndPostsThroughTheDtoPath() throws Exception {
        final SavingsAccountData dynamicAccountOne = accountData(101L, DepositAccountType.DYNAMIC_DEPOSIT);
        final SavingsAccountData plainSavingsAccount = accountData(303L, DepositAccountType.SAVINGS_DEPOSIT);
        final SavingsAccountData dynamicAccountTwo = accountData(202L, DepositAccountType.DYNAMIC_DEPOSIT);
        stubPostedWithoutNewTransactions(dynamicAccountOne);
        stubPostedWithoutNewTransactions(plainSavingsAccount);
        stubPostedWithoutNewTransactions(dynamicAccountTwo);

        final List<SavingsAccountDynamicRateData> accountOneHistory = List
                .of(new SavingsAccountDynamicRateData(101L, LocalDate.of(2031, 1, 1), new BigDecimal("12.50")));
        final List<SavingsAccountDynamicRateData> accountTwoHistory = List
                .of(new SavingsAccountDynamicRateData(202L, LocalDate.of(2031, 1, 1), new BigDecimal("9.75")));
        when(this.dynamicRateHistoryReadPlatformService.retrieveByAccountIds(any()))
                .thenReturn(Map.of(101L, accountOneHistory, 202L, accountTwoHistory));

        final AdvanclySavingsSchedularInterestPoster poster = newPoster();
        poster.setSavingAccounts(List.of(dynamicAccountOne, plainSavingsAccount, dynamicAccountTwo));
        poster.setBackdatedTxnsAllowedTill(false);

        poster.postInterest();

        verify(this.dynamicRateHistoryReadPlatformService).retrieveByAccountIds(argThat(accountIds -> {
            assertThat(accountIds).containsExactly(101L, 202L);
            return true;
        }));
        verify(dynamicAccountOne).setDynamicRateHistory(accountOneHistory);
        verify(dynamicAccountTwo).setDynamicRateHistory(accountTwoHistory);
        verify(plainSavingsAccount, never()).setDynamicRateHistory(anyList());

        verify(this.writePlatformService).postInterest(eq(dynamicAccountOne), eq(false), isNull(), eq(false));
        verify(this.writePlatformService).postInterest(eq(plainSavingsAccount), eq(false), isNull(), eq(false));
        verify(this.writePlatformService).postInterest(eq(dynamicAccountTwo), eq(false), isNull(), eq(false));
        verify(this.writePlatformService, never()).postInterest(any(SavingsAccount.class), anyBoolean(), any(LocalDate.class),
                anyBoolean());
    }

    @Test
    void skipsBulkRateHistoryLoadingWhenTheChunkHasNoDynamicDepositAccounts() throws Exception {
        final SavingsAccountData plainSavingsAccount = accountData(303L, DepositAccountType.SAVINGS_DEPOSIT);
        stubPostedWithoutNewTransactions(plainSavingsAccount);

        final AdvanclySavingsSchedularInterestPoster poster = newPoster();
        poster.setSavingAccounts(List.of(plainSavingsAccount));
        poster.setBackdatedTxnsAllowedTill(false);

        poster.postInterest();

        verifyNoInteractions(this.dynamicRateHistoryReadPlatformService);
        verify(this.writePlatformService).postInterest(eq(plainSavingsAccount), eq(false), isNull(), eq(false));
        verify(this.writePlatformService, never()).postInterest(any(SavingsAccount.class), anyBoolean(), any(LocalDate.class),
                anyBoolean());
    }

    private AdvanclySavingsSchedularInterestPoster newPoster() {
        return new AdvanclySavingsSchedularInterestPoster(this.writePlatformService, this.jdbcTemplate, this.readPlatformService,
                this.securityContext, this.interestChargeRepository, this.savingsAccountTransactionRepository,
                this.dynamicRateHistoryReadPlatformService);
    }

    private SavingsAccountData accountData(final Long accountId, final DepositAccountType depositAccountType) {
        final SavingsAccountData accountData = mock(SavingsAccountData.class);
        when(accountData.getId()).thenReturn(accountId);
        when(accountData.getDepositTypeId()).thenReturn(depositAccountType.getValue());
        when(accountData.getSummary()).thenReturn(mock(SavingsAccountSummaryData.class));
        when(accountData.getSavingsAccountTransactionData()).thenReturn(new ArrayList<>());
        return accountData;
    }

    private void stubPostedWithoutNewTransactions(final SavingsAccountData accountData) {
        when(this.writePlatformService.postInterest(accountData, false, null, false)).thenReturn(accountData);
        when(this.interestChargeRepository.findPendingByAccountIdUpTo(eq(accountData.getId()), any(LocalDate.class))).thenReturn(List.of());
    }

    private void stubAuthenticatedUser() {
        final AppUser appUser = mock(AppUser.class);
        when(appUser.getId()).thenReturn(1L);
        when(this.securityContext.authenticatedUser()).thenReturn(appUser);
    }
}
