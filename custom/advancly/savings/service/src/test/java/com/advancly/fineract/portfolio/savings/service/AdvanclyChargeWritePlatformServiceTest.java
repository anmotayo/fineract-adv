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

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.advancly.fineract.portfolio.savings.domain.AdvanclyChargeInterestRule;
import com.advancly.fineract.portfolio.savings.domain.AdvanclyChargeInterestRuleRepository;
import com.advancly.fineract.portfolio.savings.domain.InterestBasisMode;
import java.util.Optional;
import org.apache.fineract.infrastructure.core.api.JsonCommand;
import org.apache.fineract.infrastructure.core.data.CommandProcessingResult;
import org.apache.fineract.infrastructure.core.data.CommandProcessingResultBuilder;
import org.apache.fineract.portfolio.charge.api.ChargesApiConstants;
import org.apache.fineract.portfolio.charge.domain.Charge;
import org.apache.fineract.portfolio.charge.domain.ChargeRepository;
import org.apache.fineract.portfolio.charge.service.ChargeWritePlatformService;
import org.junit.jupiter.api.Test;

class AdvanclyChargeWritePlatformServiceTest {

    @Test
    void updateRevalidatesExistingRuleWhenRuleFieldsAreOmitted() {
        final ChargeWritePlatformService delegate = mock(ChargeWritePlatformService.class);
        final ChargeRepository chargeRepository = mock(ChargeRepository.class);
        final AdvanclyChargeInterestRuleRepository ruleRepository = mock(AdvanclyChargeInterestRuleRepository.class);
        final AdvanclyChargeInterestRuleValidator ruleValidator = mock(AdvanclyChargeInterestRuleValidator.class);
        final AdvanclyChargeWritePlatformService service = new AdvanclyChargeWritePlatformService(delegate, chargeRepository,
                ruleRepository, ruleValidator);
        final Long chargeId = 7L;
        final JsonCommand command = mock(JsonCommand.class);
        final Charge charge = mock(Charge.class);
        final AdvanclyChargeInterestRule rule = mock(AdvanclyChargeInterestRule.class);

        when(delegate.updateCharge(chargeId, command))
                .thenReturn(new CommandProcessingResultBuilder().withEntityId(chargeId).build());
        when(command.parameterExists(ChargesApiConstants.interestBasisModeParamName)).thenReturn(false);
        when(command.parameterExists(ChargesApiConstants.customPeriodReapplyPolicyParamName)).thenReturn(false);
        when(chargeRepository.findById(chargeId)).thenReturn(Optional.of(charge));
        when(ruleRepository.findByChargeId(chargeId)).thenReturn(Optional.of(rule));
        when(rule.interestBasisMode()).thenReturn(InterestBasisMode.CUMULATIVE);
        when(rule.customPeriodReapplyPolicy()).thenReturn(null);

        final CommandProcessingResult result = service.updateCharge(chargeId, command);

        verify(ruleValidator).validateRule(charge, InterestBasisMode.CUMULATIVE, null);
        verify(delegate).updateCharge(chargeId, command);
        org.assertj.core.api.Assertions.assertThat(result.getResourceId()).isEqualTo(chargeId);
    }
}
