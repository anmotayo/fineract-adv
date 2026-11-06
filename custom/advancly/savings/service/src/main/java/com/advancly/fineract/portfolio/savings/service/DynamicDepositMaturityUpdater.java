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
import lombok.RequiredArgsConstructor;
import org.apache.fineract.infrastructure.core.exception.GeneralPlatformDomainRuleException;
import org.apache.fineract.portfolio.savings.DepositAccountType;
import org.apache.fineract.portfolio.savings.domain.SavingsAccount;
import org.apache.fineract.portfolio.savings.domain.SavingsAccountAssembler;
import org.apache.fineract.portfolio.savings.service.DepositAccountMaturityUpdater;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Brings Dynamic Deposits into the "Update Deposit Accounts Maturity Details" job, which core runs for Fixed and
 * Recurring Deposits only. An account that has reached its maturity date is matured: its final interest (and any tax
 * configured to be withheld at maturity) is posted on the maturity date and it stops taking transactions, leaving only
 * its closure. See {@link AdvanclyDepositAccountDomainService#matureAccount}.
 */
@Component
@RequiredArgsConstructor
public class DynamicDepositMaturityUpdater implements DepositAccountMaturityUpdater {

    private final SavingsAccountAssembler savingsAccountAssembler;
    private final AdvanclyDepositAccountDomainService depositAccountDomainService;

    @Override
    public boolean supports(final DepositAccountType depositAccountType) {
        return depositAccountType != null && depositAccountType.isDynamicDeposit();
    }

    @Override
    @Transactional
    public void updateMaturity(final Long depositAccountId) {
        final boolean backdatedTxnsAllowedTill = false;
        final SavingsAccount account = this.savingsAccountAssembler.assembleFrom(depositAccountId, backdatedTxnsAllowedTill);
        if (!(account instanceof DynamicDepositAccount dynamicDepositAccount)) {
            throw new GeneralPlatformDomainRuleException("error.msg.dynamicdepositaccount.maturity.not.a.dynamic.deposit",
                    "Account " + depositAccountId + " is not a Dynamic Deposit account.", depositAccountId);
        }
        this.depositAccountDomainService.matureAccount(dynamicDepositAccount);
    }
}
