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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.advancly.fineract.portfolio.savings.domain.DynamicDepositAccount;
import com.advancly.fineract.portfolio.savings.domain.DynamicDepositAccountRepository;
import com.advancly.fineract.portfolio.savings.service.DynamicDepositRateHistoryService.AccountRecompute;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;

class DynamicDepositInvestedAmountRecomputeServiceTest {

    private DynamicDepositAccountRepository repository;
    private DynamicDepositRateHistoryService rateHistoryService;
    private DynamicDepositInvestedAmountRecomputeService service;
    private DynamicDepositAccount first;
    private DynamicDepositAccount second;

    @BeforeEach
    void setUp() {
        this.repository = mock(DynamicDepositAccountRepository.class);
        this.rateHistoryService = mock(DynamicDepositRateHistoryService.class);
        this.service = new DynamicDepositInvestedAmountRecomputeService(this.repository, this.rateHistoryService,
                mock(PlatformTransactionManager.class));
        this.first = account(1L);
        this.second = account(2L);
        when(this.repository.findById(1L)).thenReturn(Optional.of(this.first));
        when(this.repository.findById(2L)).thenReturn(Optional.of(this.second));
        when(this.rateHistoryService.recompute(this.first, false)).thenReturn(recompute(1L, "-24.93", "0"));
        when(this.rateHistoryService.recompute(this.second, false)).thenReturn(recompute(2L, "1000", "1000"));
        when(this.rateHistoryService.recompute(this.first, true)).thenReturn(recompute(1L, "-24.93", "0"));
    }

    @Test
    void aDryRunReportsWhatWouldChangeAndAppliesNothing() {
        final DynamicDepositInvestedAmountRecomputeService.Result result = this.service.run(List.of(1L, 2L), true);

        assertThat(result.dryRun()).isTrue();
        assertThat(result.accountsChecked()).isEqualTo(2);
        assertThat(result.accountsChanged()).isEqualTo(1);
        assertThat(result.accounts()).extracting(AccountRecompute::accountId).containsExactly(1L);
        verify(this.rateHistoryService, never()).recompute(this.first, true);
        verify(this.rateHistoryService, never()).recompute(this.second, true);
    }

    @Test
    void applyingCorrectsEachAccountAndReportsTheOnesThatChanged() {
        when(this.rateHistoryService.recompute(this.second, true)).thenReturn(recompute(2L, "1000", "1000"));

        final DynamicDepositInvestedAmountRecomputeService.Result result = this.service.run(List.of(1L, 2L), false);

        assertThat(result.dryRun()).isFalse();
        assertThat(result.accountsChanged()).isEqualTo(1);
        verify(this.rateHistoryService).recompute(this.first, true);
        verify(this.rateHistoryService).recompute(this.second, true);
    }

    @Test
    void noIdsMeansEveryDynamicDepositAccount() {
        when(this.repository.findAll()).thenReturn(List.of(this.first, this.second));

        final DynamicDepositInvestedAmountRecomputeService.Result result = this.service.run(List.of(), true);

        assertThat(result.accountsChecked()).isEqualTo(2);
    }

    @Test
    void anUnknownAccountIsAnError() {
        when(this.repository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> this.service.run(List.of(99L), true)).isInstanceOf(RuntimeException.class);
        verify(this.rateHistoryService, never()).recompute(org.mockito.ArgumentMatchers.any(), anyBoolean());
    }

    private static DynamicDepositAccount account(final Long id) {
        final DynamicDepositAccount account = mock(DynamicDepositAccount.class);
        when(account.getId()).thenReturn(id);
        return account;
    }

    private static AccountRecompute recompute(final Long id, final String stored, final String corrected) {
        return new AccountRecompute(id, "00000000" + id, "ACTIVE", new BigDecimal(stored), new BigDecimal(corrected), BigDecimal.TEN,
                BigDecimal.TEN, List.of());
    }
}
