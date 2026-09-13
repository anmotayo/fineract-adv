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

import com.advancly.fineract.portfolio.savings.domain.DynamicDepositAccountRepository;
import java.time.LocalDate;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.fineract.infrastructure.core.service.DateUtils;
import org.apache.fineract.portfolio.savings.domain.SavingsAccount;
import org.apache.fineract.portfolio.savings.domain.SavingsAccountRepositoryWrapper;
import org.apache.fineract.portfolio.savings.domain.SavingsAccountStatusType;
import org.apache.fineract.portfolio.savings.service.SavingsAccountWritePlatformService;
import org.springframework.batch.core.StepContribution;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.repeat.RepeatStatus;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Scheduled counterpart to the command-driven postInterest path (Task 4) for Dynamic Deposit accounts, kept entirely
 * separate from the shared {@code PostInterestForSavingTasklet}/{@code SavingsSchedularInterestPosterTask} bulk job -
 * that job's DTO-based calculation path never dispatches into {@code DynamicDepositAccount#calculateInterestUsing}, so
 * Dynamic Deposit accounts are excluded from it (Task 7 Step 2) and posted here instead, through the JPA-entity path
 * which does dispatch into the override.
 *
 * <p>
 * Deliberately simple (no multi-threaded queueing like {@code PostInterestForSavingTasklet}) - Dynamic Deposit is a
 * new, low-volume product type, so a straightforward per-account loop is appropriate here.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class DynamicDepositPostInterestTasklet implements Tasklet {

    private final DynamicDepositAccountRepository dynamicDepositAccountRepository;
    private final SavingsAccountRepositoryWrapper savingsAccountRepositoryWrapper;
    private final SavingsAccountWritePlatformService savingsAccountWritePlatformService;

    @Override
    @Transactional
    public RepeatStatus execute(final StepContribution contribution, final ChunkContext chunkContext) {
        final List<Long> activeAccountIds = this.dynamicDepositAccountRepository
                .findIdsByStatus(SavingsAccountStatusType.ACTIVE.getValue());
        final LocalDate today = DateUtils.getBusinessLocalDate();
        for (final Long accountId : activeAccountIds) {
            try {
                final SavingsAccount account = this.savingsAccountRepositoryWrapper.findOneWithNotFoundDetection(accountId);
                this.savingsAccountWritePlatformService.postInterest(account, false, today, false);
                this.savingsAccountRepositoryWrapper.saveAndFlush(account);
            } catch (final RuntimeException e) {
                log.error("Dynamic Deposit scheduled interest posting failed for account {}", accountId, e);
            }
        }
        return RepeatStatus.FINISHED;
    }
}
