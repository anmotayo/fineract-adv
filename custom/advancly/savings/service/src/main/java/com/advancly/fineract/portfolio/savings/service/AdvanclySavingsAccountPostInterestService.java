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
import java.math.MathContext;
import java.time.LocalDate;
import org.apache.fineract.portfolio.savings.domain.SavingsAccount;
import org.apache.fineract.portfolio.savings.domain.SavingsAccountTransactionSummaryWrapper;
import org.apache.fineract.portfolio.savings.service.SavingsAccountPostInterestServiceImpl;

/**
 * Core's post-interest logic no longer lives on {@link SavingsAccount}, so a dynamic deposit can't override it. This
 * service sends dynamic deposits to their own posting (rate sub-periods regrouped into core posting periods) and leaves
 * every other account to core.
 */
public class AdvanclySavingsAccountPostInterestService extends SavingsAccountPostInterestServiceImpl {

    public AdvanclySavingsAccountPostInterestService(final SavingsAccountTransactionSummaryWrapper summaryWrapper) {
        super(summaryWrapper);
    }

    @Override
    public void postInterest(final SavingsAccount account, final MathContext mc, final LocalDate postingDate,
            final boolean isInterestTransfer, final boolean isSavingsInterestPostingAtCurrentPeriodEnd,
            final Integer financialYearBeginningMonth, final LocalDate postInterestOnDate, final boolean backdatedTxnsAllowedTill,
            final boolean postReversals) {
        if (account instanceof DynamicDepositAccount dynamicDepositAccount) {
            dynamicDepositAccount.postInterestForDynamicDeposit(mc, postingDate, isInterestTransfer,
                    isSavingsInterestPostingAtCurrentPeriodEnd, financialYearBeginningMonth, postInterestOnDate, backdatedTxnsAllowedTill,
                    postReversals);
            return;
        }
        super.postInterest(account, mc, postingDate, isInterestTransfer, isSavingsInterestPostingAtCurrentPeriodEnd,
                financialYearBeginningMonth, postInterestOnDate, backdatedTxnsAllowedTill, postReversals);
    }
}
