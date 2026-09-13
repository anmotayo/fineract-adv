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
package com.advancly.fineract.portfolio.savings.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.advancly.fineract.portfolio.savings.domain.DynamicDepositAccountRepository;
import java.time.LocalDate;
import java.util.List;
import org.apache.fineract.portfolio.savings.domain.SavingsAccount;
import org.apache.fineract.portfolio.savings.domain.SavingsAccountRepositoryWrapper;
import org.apache.fineract.portfolio.savings.domain.SavingsAccountStatusType;
import org.apache.fineract.portfolio.savings.service.SavingsAccountWritePlatformService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.batch.core.StepContribution;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.repeat.RepeatStatus;

@ExtendWith(MockitoExtension.class)
public class DynamicDepositPostInterestTaskletTest {

    @Mock
    private DynamicDepositAccountRepository dynamicDepositAccountRepository;
    @Mock
    private SavingsAccountRepositoryWrapper savingsAccountRepositoryWrapper;
    @Mock
    private SavingsAccountWritePlatformService savingsAccountWritePlatformService;
    @Mock
    private StepContribution stepContribution;
    @Mock
    private ChunkContext chunkContext;
    @Mock
    private SavingsAccount account1;
    @Mock
    private SavingsAccount account2;

    private DynamicDepositPostInterestTasklet underTest;

    @BeforeEach
    public void setUp() {
        underTest = new DynamicDepositPostInterestTasklet(dynamicDepositAccountRepository, savingsAccountRepositoryWrapper,
                savingsAccountWritePlatformService);
    }

    @Test
    public void postsInterestForEveryActiveDynamicDepositAccountThroughTheJpaEntityPath() throws Exception {
        when(dynamicDepositAccountRepository.findIdsByStatus(SavingsAccountStatusType.ACTIVE.getValue())).thenReturn(List.of(1L, 2L));
        when(savingsAccountRepositoryWrapper.findOneWithNotFoundDetection(1L)).thenReturn(account1);
        when(savingsAccountRepositoryWrapper.findOneWithNotFoundDetection(2L)).thenReturn(account2);

        final RepeatStatus result = underTest.execute(stepContribution, chunkContext);

        assertThat(result).isEqualTo(RepeatStatus.FINISHED);
        verify(savingsAccountWritePlatformService).postInterest(eq(account1), eq(false), any(LocalDate.class), eq(false));
        verify(savingsAccountWritePlatformService).postInterest(eq(account2), eq(false), any(LocalDate.class), eq(false));
        verify(savingsAccountRepositoryWrapper).saveAndFlush(account1);
        verify(savingsAccountRepositoryWrapper).saveAndFlush(account2);
    }

    @Test
    public void continuesWithRemainingAccountsWhenOneAccountFailsToPost() throws Exception {
        when(dynamicDepositAccountRepository.findIdsByStatus(SavingsAccountStatusType.ACTIVE.getValue())).thenReturn(List.of(1L, 2L));
        when(savingsAccountRepositoryWrapper.findOneWithNotFoundDetection(1L)).thenReturn(account1);
        when(savingsAccountRepositoryWrapper.findOneWithNotFoundDetection(2L)).thenReturn(account2);
        org.mockito.Mockito.doThrow(new RuntimeException("boom")).when(savingsAccountWritePlatformService).postInterest(eq(account1),
                anyBoolean(), any(LocalDate.class), anyBoolean());

        final RepeatStatus result = underTest.execute(stepContribution, chunkContext);

        assertThat(result).isEqualTo(RepeatStatus.FINISHED);
        verify(savingsAccountRepositoryWrapper, never()).saveAndFlush(account1);
        verify(savingsAccountWritePlatformService).postInterest(eq(account2), eq(false), any(LocalDate.class), eq(false));
        verify(savingsAccountRepositoryWrapper, times(1)).saveAndFlush(account2);
    }

    @Test
    public void doesNothingWhenNoActiveDynamicDepositAccountsExist() throws Exception {
        when(dynamicDepositAccountRepository.findIdsByStatus(SavingsAccountStatusType.ACTIVE.getValue())).thenReturn(List.of());

        final RepeatStatus result = underTest.execute(stepContribution, chunkContext);

        assertThat(result).isEqualTo(RepeatStatus.FINISHED);
        verify(savingsAccountRepositoryWrapper, never()).findOneWithNotFoundDetection(anyLong());
        verify(savingsAccountWritePlatformService, never()).postInterest(any(SavingsAccount.class), anyBoolean(), any(), anyBoolean());
    }
}
