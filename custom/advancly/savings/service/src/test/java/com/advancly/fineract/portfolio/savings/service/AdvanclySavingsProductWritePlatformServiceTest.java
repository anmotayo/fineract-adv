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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.Optional;
import org.apache.fineract.infrastructure.core.api.JsonCommand;
import org.apache.fineract.infrastructure.core.data.CommandProcessingResult;
import org.apache.fineract.infrastructure.core.data.CommandProcessingResultBuilder;
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
    private AdvanclyChargeInterestRuleValidator chargeInterestRuleValidator;
    @Mock
    private JsonCommand command;
    @Mock
    private SavingsProduct product;

    private AdvanclySavingsProductWritePlatformService service;

    @BeforeEach
    void setUp() {
        service = new AdvanclySavingsProductWritePlatformService(delegate, savingsProductRepository, chargeInterestRuleValidator);
    }

    @Test
    void createDelegatesThenValidatesChargeDrivenRules() {
        final CommandProcessingResult result = new CommandProcessingResultBuilder().withEntityId(9L).build();
        when(delegate.create(command)).thenReturn(result);
        when(savingsProductRepository.findById(9L)).thenReturn(Optional.of(product));

        final CommandProcessingResult actual = service.create(command);

        assertThat(actual.getResourceId()).isEqualTo(9L);
        verify(chargeInterestRuleValidator).validateProductHasAtMostOneInterestCharge(product);
    }

    @Test
    void updateDelegatesThenValidatesChargeDrivenRules() {
        final Long productId = 9L;
        final CommandProcessingResult result = new CommandProcessingResultBuilder().withEntityId(9L).build();
        when(delegate.update(productId, command)).thenReturn(result);
        when(savingsProductRepository.findById(9L)).thenReturn(java.util.Optional.of(product));

        final CommandProcessingResult actual = service.update(productId, command);

        assertThat(actual.getResourceId()).isEqualTo(productId);
        verify(chargeInterestRuleValidator).validateProductHasAtMostOneInterestCharge(product);
    }

    @Test
    void createWithLegacyTestConstructorOnlyDelegates() {
        final AdvanclySavingsProductWritePlatformService serviceWithoutValidator = new AdvanclySavingsProductWritePlatformService(delegate,
                savingsProductRepository);
        final CommandProcessingResult result = new CommandProcessingResultBuilder().withEntityId(9L).build();
        when(delegate.create(command)).thenReturn(result);

        final CommandProcessingResult actual = serviceWithoutValidator.create(command);

        assertThat(actual.getResourceId()).isEqualTo(9L);
        verify(savingsProductRepository, never()).findById(any());
        verifyNoInteractions(chargeInterestRuleValidator);
    }

    @Test
    void deleteIsAPurePassthroughToTheDelegate() {
        final Long productId = 9L;
        final CommandProcessingResult result = new CommandProcessingResultBuilder().withEntityId(productId).build();
        when(delegate.delete(productId)).thenReturn(result);

        final CommandProcessingResult actual = service.delete(productId);

        assertThat(actual).isSameAs(result);
        verify(delegate).delete(productId);
        verifyNoInteractions(savingsProductRepository, chargeInterestRuleValidator);
    }
}
