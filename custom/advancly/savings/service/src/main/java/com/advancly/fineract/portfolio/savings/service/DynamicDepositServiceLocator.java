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

import com.advancly.fineract.portfolio.savings.domain.DepositAccountDynamicRateHistoryRepository;
import com.advancly.fineract.portfolio.savings.domain.SavingsAccountInterestChargeRepository;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import org.springframework.beans.BeansException;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.stereotype.Component;

/**
 * Bridges plain JPA entities (which are not Spring-managed and cannot be {@code @Autowired}) to the
 * {@link DynamicDepositRateHistoryService} bean.
 *
 * Needed specifically because a few write paths call {@code DynamicDepositAccount#deposit(...)}/{@code #withdraw(...)}
 * /{@code #undoTransaction(Long)} directly with no domain-service layer in between - notably
 * {@code SavingsAccountWritePlatformServiceJpaRepositoryImpl#adjustSavingsTransaction} (replacement transaction) and
 * backdated account transfers (via the core {@code SavingsAccountDomainServiceJpa}, bypassing the Advancly wrapper).
 * Those paths cannot be reached from {@code AdvanclySavingsAccountDomainService} (which handles the optimized
 * append-path and reversal cases via ordinary constructor injection instead - see that class).
 */
@Component
public class DynamicDepositServiceLocator implements ApplicationContextAware {

    private static ApplicationContext applicationContext;

    @SuppressFBWarnings("ST_WRITE_TO_STATIC_FROM_INSTANCE_METHOD")
    @Override
    public void setApplicationContext(final ApplicationContext applicationContext) throws BeansException {
        DynamicDepositServiceLocator.applicationContext = applicationContext;
    }

    public static DynamicDepositRateHistoryService rateHistoryService() {
        return applicationContext.getBean(DynamicDepositRateHistoryService.class);
    }

    public static DepositAccountDynamicRateHistoryRepository rateHistoryRepository() {
        return applicationContext.getBean(DepositAccountDynamicRateHistoryRepository.class);
    }

    public static DynamicDepositInterestWithdrawalService interestWithdrawalService() {
        return applicationContext.getBean(DynamicDepositInterestWithdrawalService.class);
    }

    public static DynamicDepositEarlyWithdrawalChargeService earlyWithdrawalChargeService() {
        return applicationContext.getBean(DynamicDepositEarlyWithdrawalChargeService.class);
    }

    public static SavingsAccountInterestChargeRepository interestChargeRepository() {
        return applicationContext.getBean(SavingsAccountInterestChargeRepository.class);
    }
}
