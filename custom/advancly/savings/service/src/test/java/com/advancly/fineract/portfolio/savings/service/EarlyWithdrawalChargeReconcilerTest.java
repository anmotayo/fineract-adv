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

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.advancly.fineract.portfolio.savings.domain.SavingsProductEarlyWithdrawalCharge;
import com.advancly.fineract.portfolio.savings.domain.SavingsProductEarlyWithdrawalChargeRepository;
import java.util.Collections;
import java.util.List;
import org.apache.fineract.infrastructure.core.api.JsonCommand;
import org.apache.fineract.portfolio.charge.domain.Charge;
import org.apache.fineract.portfolio.charge.domain.ChargeCalculationType;
import org.apache.fineract.portfolio.savings.SavingsCompoundingInterestPeriodType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class EarlyWithdrawalChargeReconcilerTest {

    @Mock
    private SavingsProductEarlyWithdrawalChargeRepository earlyWithdrawalChargeRepository;
    @Mock
    private JsonCommand command;
    @Mock
    private Charge charge;

    @Test
    void writesANewSelectionWhenNoneExistedBefore() {
        when(earlyWithdrawalChargeRepository.findBySavingsProductId(1L)).thenReturn(Collections.emptyList());
        when(command.parameterExists("earlyWithdrawalChargeId")).thenReturn(true);
        when(command.longValueOfParameterNamed("earlyWithdrawalChargeId")).thenReturn(7L);
        when(command.parameterExists("earlyWithdrawalChargeMode")).thenReturn(false);
        when(charge.getId()).thenReturn(7L);
        when(charge.isActive()).thenReturn(true);
        when(charge.isPenalty()).thenReturn(true);
        when(charge.getChargeCalculation()).thenReturn(ChargeCalculationType.PERCENT_OF_INTEREST.getValue());

        final EarlyWithdrawalChargeReconciler reconciler = new EarlyWithdrawalChargeReconciler(earlyWithdrawalChargeRepository);
        reconciler.reconcile(1L, command, true, List.of(charge), SavingsCompoundingInterestPeriodType.NO_COMPOUNDING_SIMPLE_INTEREST,
                "earlyWithdrawalChargeId", "earlyWithdrawalChargeMode", "testResource");

        verify(earlyWithdrawalChargeRepository).deleteAll(Collections.emptyList());
        verify(earlyWithdrawalChargeRepository).saveAndFlush(any(SavingsProductEarlyWithdrawalCharge.class));
    }

    @Test
    void writesNothingWhenPenaltyDisabledAndNoChargeRequested() {
        when(earlyWithdrawalChargeRepository.findBySavingsProductId(1L)).thenReturn(Collections.emptyList());
        when(command.parameterExists("earlyWithdrawalChargeId")).thenReturn(false);

        final EarlyWithdrawalChargeReconciler reconciler = new EarlyWithdrawalChargeReconciler(earlyWithdrawalChargeRepository);
        reconciler.reconcile(1L, command, false, Collections.emptyList(),
                SavingsCompoundingInterestPeriodType.NO_COMPOUNDING_SIMPLE_INTEREST, "earlyWithdrawalChargeId", "earlyWithdrawalChargeMode", "testResource");

        verify(earlyWithdrawalChargeRepository).deleteAll(Collections.emptyList());
        verify(earlyWithdrawalChargeRepository, org.mockito.Mockito.never()).saveAndFlush(any());
    }
}
