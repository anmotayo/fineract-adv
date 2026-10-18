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

import com.advancly.fineract.portfolio.savings.domain.DynamicDepositAccount;
import com.advancly.fineract.portfolio.savings.domain.DynamicDepositAccountRepository;
import com.advancly.fineract.portfolio.savings.exception.DynamicDepositAccountNotFoundException;
import com.advancly.fineract.portfolio.savings.service.DynamicDepositRateHistoryService.AccountRecompute;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Re-derives the invested amount, and the rate resolved from it, of existing Dynamic Deposit accounts - see
 * {@link DynamicDepositRateHistoryService#recompute}. A dry run reports what would change and writes nothing; applying
 * changes stored figures, so it should follow a dry run that has been read.
 *
 * Each account is recomputed in its own transaction, so one account failing leaves every other as it was and an
 * interrupted run can simply be run again.
 */
@Service
public class DynamicDepositInvestedAmountRecomputeService {

    /**
     * @param accountsChecked
     *            how many accounts were examined
     * @param accounts
     *            only those whose figures change (or changed)
     */
    public record Result(boolean dryRun, int accountsChecked, int accountsChanged, List<AccountRecompute> accounts) {
    }

    private final DynamicDepositAccountRepository accountRepository;
    private final DynamicDepositRateHistoryService rateHistoryService;
    private final PlatformTransactionManager transactionManager;

    public DynamicDepositInvestedAmountRecomputeService(final DynamicDepositAccountRepository accountRepository,
            final DynamicDepositRateHistoryService rateHistoryService, final PlatformTransactionManager transactionManager) {
        this.accountRepository = accountRepository;
        this.rateHistoryService = rateHistoryService;
        this.transactionManager = transactionManager;
    }

    /**
     * @param accountIds
     *            the accounts to recompute; empty means every Dynamic Deposit account
     */
    public Result run(final Collection<Long> accountIds, final boolean dryRun) {
        final List<Long> ids = accountIds == null || accountIds.isEmpty()
                ? this.accountRepository.findAll().stream().map(DynamicDepositAccount::getId).toList()
                : List.copyOf(accountIds);

        final TransactionTemplate transaction = new TransactionTemplate(this.transactionManager);
        transaction.setReadOnly(dryRun);
        final List<AccountRecompute> changed = new ArrayList<>();
        for (final Long id : ids) {
            final AccountRecompute recompute = transaction.execute(status -> {
                final DynamicDepositAccount account = this.accountRepository.findById(id)
                        .orElseThrow(() -> new DynamicDepositAccountNotFoundException(id));
                return this.rateHistoryService.recompute(account, !dryRun);
            });
            if (recompute != null && recompute.changed()) {
                changed.add(recompute);
            }
        }
        return new Result(dryRun, ids.size(), changed.size(), changed);
    }
}
