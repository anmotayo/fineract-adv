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

import com.advancly.fineract.portfolio.savings.data.SimulatedRateData;
import com.advancly.fineract.portfolio.savings.domain.DepositAccountDynamicRateHistory;
import com.advancly.fineract.portfolio.savings.domain.DepositAccountDynamicRateHistoryRepository;
import com.advancly.fineract.portfolio.savings.domain.DynamicDepositAccount;
import com.advancly.fineract.portfolio.savings.domain.DynamicDepositAccountRepository;
import com.advancly.fineract.portfolio.savings.domain.DynamicDepositRateHistoryEventType;
import com.advancly.fineract.portfolio.savings.exception.DynamicDepositAccountNotFoundException;
import com.advancly.fineract.portfolio.savings.service.DynamicDepositRateResolutionService.ResolvedRate;
import java.math.BigDecimal;
import lombok.RequiredArgsConstructor;
import org.apache.fineract.portfolio.savings.domain.DepositAccountTermAndPreClosure;
import org.apache.fineract.portfolio.savings.domain.SavingsAccountTransaction;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Builds the transient rate-history row for the interest-calculation preview: what
 * {@link DynamicDepositRateHistoryService#recordPrincipalChangeEvent} would record for a simulated principal change,
 * minus the save. Nothing here writes.
 *
 * The {@code account} passed in is the detached, simulation-carrying instance (see
 * {@link InterestCalculationReadPlatformServiceImpl}); its lazy {@code chart} and product can't be read outside a
 * session, so rate resolution runs against a freshly loaded instance inside this read-only transaction. That instance
 * is only read from and never mutated.
 */
@Service
@RequiredArgsConstructor
public class DynamicDepositRatePreviewService {

    private final DynamicDepositAccountRepository accountRepository;
    private final DepositAccountDynamicRateHistoryRepository rateHistoryRepository;
    private final DynamicDepositRateHistoryService rateHistoryService;

    public record RatePreview(DepositAccountDynamicRateHistory row, SimulatedRateData data) {
    }

    /**
     * @return the preview, or {@code null} if the account has no term details to resolve against.
     */
    @Transactional(readOnly = true)
    public RatePreview preview(final DynamicDepositAccount account, final SavingsAccountTransaction simulatedTransaction,
            final DynamicDepositRateHistoryEventType eventType) {
        final DynamicDepositAccount managed = this.accountRepository.findById(account.getId())
                .orElseThrow(() -> new DynamicDepositAccountNotFoundException(account.getId()));
        final DepositAccountTermAndPreClosure term = managed.accountTermAndPreClosure();
        if (term == null) {
            return null;
        }

        final long priorRowCount = this.rateHistoryRepository.countByAccountId(account.getId());
        // Invested amount comes from the detached account because that's the one carrying the simulated transactions.
        final BigDecimal investedAmountAfter = this.rateHistoryService.computeInvestedAmountAsOf(account, simulatedTransaction);
        final ResolvedRate rate = this.rateHistoryService.resolveRate(managed, term, investedAmountAfter,
                simulatedTransaction.getTransactionDate(), priorRowCount);

        final DepositAccountDynamicRateHistory row = DepositAccountDynamicRateHistory.createNew(account, simulatedTransaction,
                simulatedTransaction.getTransactionDate(), eventType, investedAmountAfter, term.depositPeriod(),
                term.depositPeriodFrequencyType() == null ? null : term.depositPeriodFrequencyType().getValue(), rate.interestRateChartId(),
                rate.interestRateSlabId(), rate.baseAnnualInterestRate(), rate.annualInterestRate(), rate.source());

        // getNominalAnnualInterestRate() is kept equal to the latest active history row's rate
        // (reconcileFollowingRows).
        final BigDecimal previousRate = managed.getNominalAnnualInterestRate();
        final boolean rateChanged = previousRate == null || rate.annualInterestRate().compareTo(previousRate) != 0;
        final SimulatedRateData data = new SimulatedRateData(rate.annualInterestRate(), rate.baseAnnualInterestRate(), rate.source().name(),
                rate.interestRateChartId(), rate.interestRateSlabId(), simulatedTransaction.getTransactionDate(), investedAmountAfter,
                previousRate, rateChanged);
        return new RatePreview(row, data);
    }
}
