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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.advancly.fineract.portfolio.savings.domain.AdvanclyChargeInterestRule;
import com.advancly.fineract.portfolio.savings.domain.AdvanclyChargeInterestRuleRepository;
import com.advancly.fineract.portfolio.savings.domain.CustomPeriodReapplyPolicy;
import com.advancly.fineract.portfolio.savings.domain.DepositInterestChargeApplicationRepository;
import com.advancly.fineract.portfolio.savings.helper.SavingsAccountTransactionHelper;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import org.apache.fineract.accounting.journalentry.service.JournalEntryWritePlatformService;
import org.apache.fineract.portfolio.charge.domain.Charge;
import org.apache.fineract.portfolio.savings.domain.SavingsAccount;
import org.apache.fineract.portfolio.savings.domain.SavingsAccountCharge;
import org.apache.fineract.portfolio.savings.domain.SavingsAccountRepositoryWrapper;
import org.apache.fineract.portfolio.savings.domain.SavingsAccountTransaction;
import org.apache.fineract.portfolio.savings.domain.SavingsAccountTransactionRepository;
import org.junit.jupiter.api.Test;

class AdvanclyInterestChargeApplicationServiceTest {

    @Test
    void oncePerSelectedPeriodAlreadyAppliedSkipsChargeInsteadOfRejectingWithdrawal() {
        final AdvanclyChargeInterestRuleRepository ruleRepository = mock(AdvanclyChargeInterestRuleRepository.class);
        final DepositInterestChargeApplicationRepository applicationRepository = mock(DepositInterestChargeApplicationRepository.class);
        final SavingsAccountTransactionRepository transactionRepository = mock(SavingsAccountTransactionRepository.class);
        final SavingsAccountRepositoryWrapper savingsAccountRepository = mock(SavingsAccountRepositoryWrapper.class);
        final SavingsAccountTransactionHelper transactionHelper = mock(SavingsAccountTransactionHelper.class);
        final JournalEntryWritePlatformService journalEntryWritePlatformService = mock(JournalEntryWritePlatformService.class);
        final CumulativeInterestForfeitureService cumulativeInterestForfeitureService = mock(CumulativeInterestForfeitureService.class);
        final AdvanclyInterestChargeApplicationService service = new AdvanclyInterestChargeApplicationService(ruleRepository,
                applicationRepository, transactionRepository, savingsAccountRepository, transactionHelper, journalEntryWritePlatformService,
                cumulativeInterestForfeitureService);

        final Long accountId = 11L;
        final Long savingsProductId = 22L;
        final Long chargeId = 33L;
        final LocalDate transactionDate = LocalDate.of(2031, 4, 18);
        final LocalDate selectedFromDate = LocalDate.of(2031, 4, 1);
        final LocalDate selectedToDate = LocalDate.of(2031, 4, 15);
        final SavingsAccount account = mock(SavingsAccount.class);
        final SavingsAccountTransaction withdrawalTransaction = mock(SavingsAccountTransaction.class);
        final AdvanclyChargeInterestRule rule = mock(AdvanclyChargeInterestRule.class);
        final SavingsAccountCharge accountCharge = mock(SavingsAccountCharge.class);
        final Charge charge = mock(Charge.class);

        when(withdrawalTransaction.isReversed()).thenReturn(false);
        when(withdrawalTransaction.getTransactionDate()).thenReturn(transactionDate);
        when(account.isEarlyWithdrawal(transactionDate)).thenReturn(true);
        when(account.getId()).thenReturn(accountId);
        when(account.productId()).thenReturn(savingsProductId);
        when(account.charges()).thenReturn(Set.of(accountCharge));
        when(account.getInterestPostingPeriodType()).thenReturn(1);
        when(ruleRepository.findBySavingsProductId(savingsProductId)).thenReturn(List.of(rule));
        when(rule.chargeId()).thenReturn(chargeId);
        when(rule.isCumulative()).thenReturn(false);
        when(rule.isCustomPeriod()).thenReturn(true);
        when(rule.customPeriodReapplyPolicy()).thenReturn(CustomPeriodReapplyPolicy.ONCE_PER_SELECTED_PERIOD);
        when(accountCharge.getCharge()).thenReturn(charge);
        when(accountCharge.isActive()).thenReturn(true);
        when(accountCharge.isPenaltyCharge()).thenReturn(true);
        when(charge.getId()).thenReturn(chargeId);
        when(applicationRepository.countActiveForSelectedPeriod(accountId, chargeId, selectedFromDate, selectedToDate)).thenReturn(1L);

        final SavingsAccountTransaction chargeTransaction = service.applyIfApplicable(account, withdrawalTransaction, true, null,
                selectedFromDate, selectedToDate, false);

        assertThat(chargeTransaction).isNull();
        verify(applicationRepository).countActiveForSelectedPeriod(accountId, chargeId, selectedFromDate, selectedToDate);
        verifyNoInteractions(transactionRepository, savingsAccountRepository, transactionHelper, journalEntryWritePlatformService,
                cumulativeInterestForfeitureService);
    }

    @Test
    void cumulativeRulesDelegateToTheForfeitureService() {
        final AdvanclyChargeInterestRuleRepository ruleRepository = mock(AdvanclyChargeInterestRuleRepository.class);
        final DepositInterestChargeApplicationRepository applicationRepository = mock(DepositInterestChargeApplicationRepository.class);
        final SavingsAccountTransactionRepository transactionRepository = mock(SavingsAccountTransactionRepository.class);
        final SavingsAccountRepositoryWrapper savingsAccountRepository = mock(SavingsAccountRepositoryWrapper.class);
        final SavingsAccountTransactionHelper transactionHelper = mock(SavingsAccountTransactionHelper.class);
        final JournalEntryWritePlatformService journalEntryWritePlatformService = mock(JournalEntryWritePlatformService.class);
        final CumulativeInterestForfeitureService cumulativeInterestForfeitureService = mock(CumulativeInterestForfeitureService.class);
        final AdvanclyInterestChargeApplicationService service = new AdvanclyInterestChargeApplicationService(ruleRepository,
                applicationRepository, transactionRepository, savingsAccountRepository, transactionHelper, journalEntryWritePlatformService,
                cumulativeInterestForfeitureService);

        final Long savingsProductId = 22L;
        final Long chargeId = 33L;
        final LocalDate transactionDate = LocalDate.of(2031, 4, 18);
        final BigDecimal percentageOverride = new BigDecimal("75");
        final SavingsAccount account = mock(SavingsAccount.class);
        final SavingsAccountTransaction withdrawalTransaction = mock(SavingsAccountTransaction.class);
        final SavingsAccountTransaction forfeitureTransaction = mock(SavingsAccountTransaction.class);
        final AdvanclyChargeInterestRule rule = mock(AdvanclyChargeInterestRule.class);
        final SavingsAccountCharge accountCharge = mock(SavingsAccountCharge.class);
        final Charge charge = mock(Charge.class);

        when(withdrawalTransaction.isReversed()).thenReturn(false);
        when(withdrawalTransaction.getTransactionDate()).thenReturn(transactionDate);
        when(account.isEarlyWithdrawal(transactionDate)).thenReturn(true);
        when(account.productId()).thenReturn(savingsProductId);
        when(account.charges()).thenReturn(Set.of(accountCharge));
        when(ruleRepository.findBySavingsProductId(savingsProductId)).thenReturn(List.of(rule));
        when(rule.chargeId()).thenReturn(chargeId);
        when(rule.isCumulative()).thenReturn(true);
        when(accountCharge.getCharge()).thenReturn(charge);
        when(accountCharge.isActive()).thenReturn(true);
        when(accountCharge.isPenaltyCharge()).thenReturn(true);
        when(charge.getId()).thenReturn(chargeId);
        when(cumulativeInterestForfeitureService.forfeitIfApplicable(account, withdrawalTransaction, transactionDate, true, false,
                percentageOverride)).thenReturn(forfeitureTransaction);

        final SavingsAccountTransaction chargeTransaction = service.applyIfApplicable(account, withdrawalTransaction, true,
                percentageOverride, null, null, true);

        assertThat(chargeTransaction).isSameAs(forfeitureTransaction);
        verify(cumulativeInterestForfeitureService).forfeitIfApplicable(account, withdrawalTransaction, transactionDate, true, false,
                percentageOverride);
        verifyNoInteractions(applicationRepository, transactionRepository, savingsAccountRepository, transactionHelper,
                journalEntryWritePlatformService);
    }
}
