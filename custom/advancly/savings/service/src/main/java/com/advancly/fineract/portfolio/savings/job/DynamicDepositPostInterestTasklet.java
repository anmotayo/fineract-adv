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
import org.apache.fineract.portfolio.savings.domain.SavingsAccountAssembler;
import org.apache.fineract.portfolio.savings.domain.SavingsAccountStatusType;
import org.apache.fineract.portfolio.savings.service.SavingsAccountWritePlatformService;
import org.springframework.batch.core.StepContribution;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.repeat.RepeatStatus;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

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
 *
 * <p>
 * Two things this class must get right that are easy to get subtly wrong:
 * <ul>
 * <li>Accounts are loaded via {@link SavingsAccountAssembler#assembleFrom(Long, boolean)} - not a bare repository
 * {@code findById} - because {@code assembleFrom} is what calls {@code SavingsAccount#setHelpers(...)}, populating the
 * {@code @Transient savingsHelper}/{@code savingsAccountTransactionSummaryWrapper} fields that Task 3's
 * {@code calculateInterestUsing}/{@code postInterest} overrides dereference immediately. Every other place in this
 * codebase that loads a {@code SavingsAccount} and then calls those methods goes through this same assembler (or its
 * {@code setHelpers} convenience method) first - e.g.
 * {@code SavingsAccountWritePlatformServiceJpaRepositoryImpl#initiateSavingsTransfer}. A bare
 * {@code SavingsAccountRepositoryWrapper.findOneWithNotFoundDetection} load (this class's first implementation attempt)
 * leaves those fields {@code null}, so the very first line of the overridden {@code calculateInterestUsing} NPEs for
 * every account - silently, since it's caught below and logged per-account, leaving the job reporting overall success
 * having posted nothing for anyone.</li>
 * <li>Each account is posted in its own new transaction ({@code PROPAGATION_REQUIRES_NEW}), not inside the shared
 * transaction Spring Batch's {@code TaskletStep} already opened around {@code execute(...)}. Without this,
 * {@code SavingsAccountWritePlatformService#postInterest}'s own {@code @Transactional(REQUIRED)} joins that same shared
 * transaction; when it throws for one account, Spring marks the whole shared transaction rollback-only (Spring's
 * default {@code globalRollbackOnParticipationFailure}, not overridden anywhere in this repo), so catching the
 * exception here and continuing the loop does not actually save any other account - the step fails at commit with
 * {@code UnexpectedRollbackException} and every posting from the run is discarded, not just the failed one's. Running
 * each account's work through its own {@code REQUIRES_NEW} transaction, committed before the next account is even
 * attempted, is what makes the per-account {@code try/catch} below actually isolate failures.</li>
 * </ul>
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class DynamicDepositPostInterestTasklet implements Tasklet {

    private final DynamicDepositAccountRepository dynamicDepositAccountRepository;
    private final SavingsAccountAssembler savingsAccountAssembler;
    private final SavingsAccountWritePlatformService savingsAccountWritePlatformService;
    private final PlatformTransactionManager transactionManager;

    @Override
    public RepeatStatus execute(final StepContribution contribution, final ChunkContext chunkContext) {
        final List<Long> activeAccountIds = this.dynamicDepositAccountRepository
                .findIdsByStatus(SavingsAccountStatusType.ACTIVE.getValue());
        final LocalDate today = DateUtils.getBusinessLocalDate();

        final TransactionTemplate transactionTemplate = new TransactionTemplate(this.transactionManager);
        transactionTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);

        for (final Long accountId : activeAccountIds) {
            try {
                transactionTemplate.executeWithoutResult(status -> postInterestFor(accountId, today));
            } catch (final RuntimeException e) {
                log.error("Dynamic Deposit scheduled interest posting failed for account {}", accountId, e);
            }
        }
        return RepeatStatus.FINISHED;
    }

    private void postInterestFor(final Long accountId, final LocalDate today) {
        final SavingsAccount account = this.savingsAccountAssembler.assembleFrom(accountId, false);
        this.savingsAccountWritePlatformService.postInterest(account, false, today, false);
    }
}
