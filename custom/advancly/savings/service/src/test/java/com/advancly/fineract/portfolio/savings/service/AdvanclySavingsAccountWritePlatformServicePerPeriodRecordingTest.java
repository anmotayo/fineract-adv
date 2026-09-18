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
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.advancly.fineract.portfolio.savings.domain.EarlyWithdrawalChargeMode;
import com.advancly.fineract.portfolio.savings.domain.SavingsProductEarlyWithdrawalCharge;
import com.advancly.fineract.portfolio.savings.domain.SavingsProductEarlyWithdrawalChargeRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.apache.fineract.infrastructure.core.api.JsonCommand;
import org.apache.fineract.portfolio.savings.domain.SavingsAccount;
import org.apache.fineract.portfolio.savings.domain.SavingsAccountTransaction;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AdvanclySavingsAccountWritePlatformServicePerPeriodRecordingTest {

    @Mock
    private SavingsProductEarlyWithdrawalChargeRepository productEarlyWithdrawalChargeRepository;
    @Mock
    private DynamicDepositEarlyWithdrawalChargeService earlyWithdrawalChargeService;
    @Mock
    private SavingsAccount account;
    @Mock
    private SavingsAccountTransaction withdrawalTransaction;
    @Mock
    private JsonCommand command;

    // Constructed with the full real constructor and other collaborators mocked/null where this test's path
    // (isEarlyForForfeiture -> isPerPeriodMode -> recordIfApplicable) never touches them. See the class's own existing
    // AdvanclySavingsAccountWritePlatformServiceCumulativeForfeitureTest for the established fixture pattern this test
    // should follow instead of hand-rolling a second one.
    @InjectMocks
    private AdvanclySavingsAccountWritePlatformService service;

    @Test
    void recordsAPendingChargeOnAPerPeriodEarlyWithdrawal() {
        when(account.productId()).thenReturn(5L);
        when(account.isEarlyWithdrawal(any(LocalDate.class))).thenReturn(true);
        when(productEarlyWithdrawalChargeRepository.findBySavingsProductId(5L)).thenReturn(
                List.of(SavingsProductEarlyWithdrawalCharge.createNew(5L, 3L, EarlyWithdrawalChargeMode.PER_PERIOD)));

        service.recordPerPeriodChargeIfApplicable(account, LocalDate.of(2026, 1, 15), command, withdrawalTransaction);

        verify(earlyWithdrawalChargeService).recordIfApplicable(eq(account), eq(withdrawalTransaction), eq(false), isNull());
    }

    @Test
    void requestFlagForcesPlainSavingsPerPeriodRecordingAndPassesPercentageOverride() {
        when(account.productId()).thenReturn(5L);
        when(account.isEarlyWithdrawal(any(LocalDate.class))).thenReturn(false);
        when(command.booleanPrimitiveValueOfParameterNamed("applyEarlyWithdrawalCharge")).thenReturn(true);
        when(command.parameterExists("earlyWithdrawalChargePercentage")).thenReturn(true);
        when(command.bigDecimalValueOfParameterNamed("earlyWithdrawalChargePercentage")).thenReturn(new BigDecimal("12.5"));
        when(productEarlyWithdrawalChargeRepository.findBySavingsProductId(5L)).thenReturn(
                List.of(SavingsProductEarlyWithdrawalCharge.createNew(5L, 3L, EarlyWithdrawalChargeMode.PER_PERIOD)));

        service.recordPerPeriodChargeIfApplicable(account, LocalDate.of(2026, 1, 15), command, withdrawalTransaction);

        verify(earlyWithdrawalChargeService).recordIfApplicable(eq(account), eq(withdrawalTransaction), eq(true),
                eq(new BigDecimal("12.5")));
    }

    @Test
    void doesNotRecordWhenModeIsCumulative() {
        when(account.productId()).thenReturn(5L);
        when(account.isEarlyWithdrawal(any(LocalDate.class))).thenReturn(true);
        when(productEarlyWithdrawalChargeRepository.findBySavingsProductId(5L)).thenReturn(
                List.of(SavingsProductEarlyWithdrawalCharge.createNew(5L, 3L, EarlyWithdrawalChargeMode.CUMULATIVE)));

        service.recordPerPeriodChargeIfApplicable(account, LocalDate.of(2026, 1, 15), command, withdrawalTransaction);

        verify(earlyWithdrawalChargeService, never()).recordIfApplicable(any(), any(), anyBoolean(), any());
    }
}
