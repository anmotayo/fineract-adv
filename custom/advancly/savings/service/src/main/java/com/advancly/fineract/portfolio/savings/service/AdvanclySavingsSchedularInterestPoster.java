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

import org.apache.fineract.infrastructure.jobs.exception.JobExecutionException;
import org.apache.fineract.infrastructure.security.service.PlatformSecurityContext;
import org.apache.fineract.portfolio.savings.service.SavingsAccountReadPlatformService;
import org.apache.fineract.portfolio.savings.service.SavingsAccountWritePlatformService;
import org.apache.fineract.portfolio.savings.service.SavingsSchedularInterestPoster;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Task 7 skeleton: a plain subclass of core's {@link SavingsSchedularInterestPoster} with zero behavior change.
 * {@link #postInterest()} simply delegates to {@code super.postInterest()} for now. This class exists purely to
 * establish the override point and prove the bean wiring works (see {@code AdvanclySavingsAutoConfiguration}) before
 * Task 8 replaces the body of {@link #postInterest()} with a full reimplementation that applies pending per-period
 * early-withdrawal charges.
 *
 * <p>
 * Core's fields ({@code savingsAccountWritePlatformService}, {@code jdbcTemplate},
 * {@code savingsAccountReadPlatformService}, {@code platformSecurityContext}) and its batch-update machinery
 * ({@code batchUpdate}, {@code batchUpdateJournalEntries}, etc.) are all {@code private}, so this subclass has no
 * access to them and does not attempt to duplicate them here - that is exactly why Task 8 will need to re-implement
 * whatever of that machinery it needs, rather than reuse it via inheritance.
 */
public class AdvanclySavingsSchedularInterestPoster extends SavingsSchedularInterestPoster {

    public AdvanclySavingsSchedularInterestPoster(final SavingsAccountWritePlatformService savingsAccountWritePlatformService,
            final JdbcTemplate jdbcTemplate, final SavingsAccountReadPlatformService savingsAccountReadPlatformService,
            final PlatformSecurityContext platformSecurityContext) {
        super(savingsAccountWritePlatformService, jdbcTemplate, savingsAccountReadPlatformService, platformSecurityContext);
    }

    @Override
    @Transactional(isolation = Isolation.READ_UNCOMMITTED, rollbackFor = Exception.class)
    public void postInterest() throws JobExecutionException {
        super.postInterest();
    }
}
