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
import java.math.BigDecimal;
import java.math.MathContext;
import java.time.LocalDate;
import lombok.RequiredArgsConstructor;
import org.apache.fineract.infrastructure.configuration.domain.ConfigurationDomainService;
import org.apache.fineract.infrastructure.core.service.DateUtils;
import org.apache.fineract.organisation.monetary.domain.MoneyHelper;
import org.apache.fineract.portfolio.savings.domain.DepositAccountTermAndPreClosure;
import org.apache.fineract.portfolio.savings.domain.SavingsAccountStatusType;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Stores a Dynamic Deposit's gross maturity amount at activation; not kept up to date afterwards. The projection
 * overwrites daily balances and summary, so the engine is re-run up to today to restore them.
 */
@Component
@RequiredArgsConstructor
public class DynamicDepositMaturityAmountService {

    private final ConfigurationDomainService configurationDomainService;

    /** Returns whether the stored amount changed; false if the account is inactive or has no maturity date. */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean storeProjectedMaturityAmount(final DynamicDepositAccount account) {
        final DepositAccountTermAndPreClosure term = account.accountTermAndPreClosure();
        final LocalDate maturityDate = account.maturityDate();
        if (term == null || maturityDate == null || !SavingsAccountStatusType.ACTIVE.hasStateOf(account.getStatus())) {
            return false;
        }

        final MathContext mc = new MathContext(15, MoneyHelper.getRoundingMode());
        final boolean isSavingsInterestPostingAtCurrentPeriodEnd = this.configurationDomainService
                .isSavingsInterestPostingAtCurrentPeriodEnd();
        final Integer financialYearBeginningMonth = this.configurationDomainService.retrieveFinancialYearBeginningMonth();
        final boolean isInterestTransfer = false;
        final boolean backdatedTxnsAllowedTill = false;
        final boolean postReversals = false;

        account.calculateInterestUsing(mc, maturityDate, isInterestTransfer, isSavingsInterestPostingAtCurrentPeriodEnd,
                financialYearBeginningMonth, null, backdatedTxnsAllowedTill, postReversals);
        final BigDecimal maturityAmount = account.grossMaturityAmountAfterProjection();
        account.calculateInterestUsing(mc, DateUtils.getBusinessLocalDate(), isInterestTransfer, isSavingsInterestPostingAtCurrentPeriodEnd,
                financialYearBeginningMonth, null, backdatedTxnsAllowedTill, postReversals);

        term.updateMaturityDetails(maturityAmount, maturityDate);
        return true;
    }
}
