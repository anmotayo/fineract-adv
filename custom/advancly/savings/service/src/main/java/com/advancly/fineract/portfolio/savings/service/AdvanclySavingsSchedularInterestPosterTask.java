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

import org.apache.fineract.portfolio.savings.service.SavingsSchedularInterestPosterTask;

/**
 * Task 7 skeleton: a plain subclass of core's {@link SavingsSchedularInterestPosterTask} with zero behavior change. The
 * only reason this class exists (rather than reusing the core task type directly) is so that
 * {@code AdvanclySavingsAutoConfiguration} can construct it with an {@link AdvanclySavingsSchedularInterestPoster}
 * specifically - the type Task 8 will add plain-Savings per-period-charge logic to - while still satisfying core's
 * {@code @ConditionalOnMissingBean(SavingsSchedularInterestPosterTask.class)} check in {@code SavingsConfiguration}.
 *
 * <p>
 * All of {@code call()}, {@code setSavingAccounts(...)} and {@code setBackdatedTxnsAllowedTill(...)} are inherited
 * unchanged from core; {@code call()} invokes {@code interestPoster.postInterest()} polymorphically, so it already
 * dispatches to {@link AdvanclySavingsSchedularInterestPoster#postInterest()} at runtime without needing to be
 * overridden here.
 */
public class AdvanclySavingsSchedularInterestPosterTask extends SavingsSchedularInterestPosterTask {

    public AdvanclySavingsSchedularInterestPosterTask(final AdvanclySavingsSchedularInterestPoster interestPoster) {
        super(interestPoster);
    }
}
