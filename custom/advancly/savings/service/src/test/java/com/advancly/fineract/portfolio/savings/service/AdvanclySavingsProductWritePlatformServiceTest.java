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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.advancly.fineract.portfolio.savings.domain.DepositProductDynamicDetail;
import com.advancly.fineract.portfolio.savings.domain.DepositProductDynamicDetailRepository;
import java.util.Collections;
import java.util.Optional;
import org.apache.fineract.infrastructure.core.api.JsonCommand;
import org.apache.fineract.infrastructure.core.data.CommandProcessingResult;
import org.apache.fineract.infrastructure.core.data.CommandProcessingResultBuilder;
import org.apache.fineract.portfolio.savings.SavingsCompoundingInterestPeriodType;
import org.apache.fineract.portfolio.savings.domain.SavingsProduct;
import org.apache.fineract.portfolio.savings.domain.SavingsProductRepository;
import org.apache.fineract.portfolio.savings.service.SavingsProductWritePlatformService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AdvanclySavingsProductWritePlatformServiceTest {

    @Mock
    private SavingsProductWritePlatformService delegate;
    @Mock
    private SavingsProductRepository savingsProductRepository;
    @Mock
    private DepositProductDynamicDetailRepository productDynamicDetailRepository;
    @Mock
    private EarlyWithdrawalChargeReconciler earlyWithdrawalChargeReconciler;
    @Mock
    private JsonCommand command;
    @Mock
    private SavingsProduct product;

    private AdvanclySavingsProductWritePlatformService service;

    @BeforeEach
    void setUp() {
        service = new AdvanclySavingsProductWritePlatformService(delegate, savingsProductRepository, productDynamicDetailRepository,
                earlyWithdrawalChargeReconciler);
    }

    @Test
    void createDelegatesThenSkipsReconciliationWhenNoEarlyWithdrawalParametersGiven() {
        final CommandProcessingResult result = new CommandProcessingResultBuilder().withEntityId(9L).build();
        when(delegate.create(command)).thenReturn(result);
        when(command.parameterExists(any())).thenReturn(false);
        when(productDynamicDetailRepository.findByProductId(9L)).thenReturn(Optional.empty());

        final CommandProcessingResult actual = service.create(command);

        assertThat(actual.getResourceId()).isEqualTo(9L);
        verify(productDynamicDetailRepository, never()).saveAndFlush(any());
        verify(earlyWithdrawalChargeReconciler, never()).reconcile(any(), any(), org.mockito.ArgumentMatchers.anyBoolean(), any(), any(),
                any(), any(), any());
    }

    @Test
    void createReconcilesWhenEarlyWithdrawalPenaltyEnabledIsGiven() {
        final CommandProcessingResult result = new CommandProcessingResultBuilder().withEntityId(9L).build();
        when(delegate.create(command)).thenReturn(result);
        when(command.parameterExists("earlyWithdrawalPenaltyEnabled")).thenReturn(true);
        when(command.booleanPrimitiveValueOfParameterNamed("earlyWithdrawalPenaltyEnabled")).thenReturn(true);
        when(productDynamicDetailRepository.findByProductId(9L)).thenReturn(Optional.empty());
        when(savingsProductRepository.findById(9L)).thenReturn(java.util.Optional.of(product));
        when(product.charges()).thenReturn(Collections.emptySet());
        when(product.interestCompoundingPeriodType()).thenReturn(SavingsCompoundingInterestPeriodType.NO_COMPOUNDING_SIMPLE_INTEREST);

        service.create(command);

        verify(productDynamicDetailRepository).saveAndFlush(any(DepositProductDynamicDetail.class));
        verify(earlyWithdrawalChargeReconciler).reconcile(eq(9L), eq(command), eq(true), any(), any(), eq("earlyWithdrawalChargeId"),
                eq("earlyWithdrawalChargeMode"),
                eq(org.apache.fineract.portfolio.savings.SavingsApiConstants.SAVINGS_PRODUCT_RESOURCE_NAME));
    }
}
