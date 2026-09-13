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

import java.util.List;
import org.apache.fineract.infrastructure.jobs.service.jobname.JobNameData;
import org.apache.fineract.infrastructure.jobs.service.jobname.JobNameProvider;
import org.apache.fineract.infrastructure.jobs.service.jobname.SimpleJobNameProvider;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.launch.support.RunIdIncrementer;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * Registers the {@link DynamicDepositPostInterestTasklet} as a standalone Spring Batch job (Task 7, Step 5).
 *
 * <p>
 * Mirrors {@code PostInterestForSavingConfig}'s Job/Step shape, and (like {@code custom/acme/loan/job}'s
 * {@code AcmeNoopJobConfiguration}/{@code AcmeJobNameConfig}) deliberately does not add an entry to core's
 * {@code org.apache.fineract.infrastructure.jobs.service.JobName} enum - that enum lives in {@code fineract-provider}
 * and this custom module already follows the pattern of not modifying core enums for custom-module-only concerns.
 *
 * <p>
 * Two distinct names are involved, and both must be registered for the job to actually be runnable end to end:
 * <ul>
 * <li>{@link #JOB_NAME}, an enum-style identifier, is the name the Spring Batch {@code Job}/{@code Step} beans are
 * registered under - this is what {@code JobLocator} looks jobs up by at execution time.</li>
 * <li>{@link #JOB_DISPLAY_NAME}, a human-readable name, is what gets stored in the {@code job} table's {@code name}
 * column (see the Step 6 changeset) and shown in the admin UI.</li>
 * </ul>
 * {@code JobRegisterServiceImpl#executeJob} resolves a scheduled/triggered job by looking up its human-readable
 * {@code job.name} via {@code JobNameService}, which delegates to every registered {@code JobNameProvider} bean to
 * translate that human-readable name back to the enum-style name, then hands that to {@code JobLocator}. Without a
 * {@code JobNameProvider} bean pairing the two names (as registered below), that lookup would throw
 * {@code IllegalArgumentException("Job not found by name: ...")} whenever this job is triggered - confirmed by reading
 * {@code JobRegisterServiceImpl} and the existing {@code AcmeJobNameConfig} example in this codebase.
 */
@Configuration
public class DynamicDepositPostInterestConfig {

    private static final String JOB_NAME = "POST_INTEREST_FOR_DYNAMIC_DEPOSIT";
    private static final String JOB_DISPLAY_NAME = "Post Interest For Dynamic Deposit";

    @Autowired
    private JobRepository jobRepository;
    @Autowired
    private PlatformTransactionManager transactionManager;

    @Bean
    protected Step dynamicDepositPostInterestStep(DynamicDepositPostInterestTasklet dynamicDepositPostInterestTasklet) {
        return new StepBuilder(JOB_NAME, jobRepository).tasklet(dynamicDepositPostInterestTasklet, transactionManager).build();
    }

    @Bean
    public Job dynamicDepositPostInterestJob(DynamicDepositPostInterestTasklet dynamicDepositPostInterestTasklet) {
        return new JobBuilder(JOB_NAME, jobRepository).start(dynamicDepositPostInterestStep(dynamicDepositPostInterestTasklet))
                .incrementer(new RunIdIncrementer()).build();
    }

    @Bean
    public JobNameProvider dynamicDepositJobNameProvider() {
        return new SimpleJobNameProvider(List.of(new JobNameData(JOB_NAME, JOB_DISPLAY_NAME)));
    }
}
