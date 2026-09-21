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
import static org.mockito.Mockito.when;

import org.apache.fineract.infrastructure.core.api.JsonCommand;
import org.apache.fineract.infrastructure.core.data.CommandProcessingResult;
import org.apache.fineract.infrastructure.core.data.CommandProcessingResultBuilder;
import org.apache.fineract.portfolio.savings.data.SavingsAccountDataDTO;
import org.apache.fineract.portfolio.savings.domain.SavingsAccount;
import org.apache.fineract.portfolio.savings.domain.SavingsAccountRepositoryWrapper;
import org.apache.fineract.portfolio.savings.service.SavingsApplicationProcessWritePlatformService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AdvanclySavingsApplicationProcessWritePlatformServiceTest {

    private SavingsApplicationProcessWritePlatformService delegate;
    private SavingsAccountRepositoryWrapper savingsAccountRepository;
    private AdvanclyChargeInterestRuleValidator chargeInterestRuleValidator;
    private AdvanclySavingsApplicationProcessWritePlatformService service;

    @BeforeEach
    void setUp() {
        this.delegate = mock(SavingsApplicationProcessWritePlatformService.class);
        this.savingsAccountRepository = mock(SavingsAccountRepositoryWrapper.class);
        this.chargeInterestRuleValidator = mock(AdvanclyChargeInterestRuleValidator.class);
        this.service = new AdvanclySavingsApplicationProcessWritePlatformService(this.delegate, this.savingsAccountRepository,
                this.chargeInterestRuleValidator);
    }

    @Test
    void submitApplicationValidatesTheFinalPersistedAccount() {
        final JsonCommand command = mock(JsonCommand.class);
        final SavingsAccount account = mock(SavingsAccount.class);
        final CommandProcessingResult result = new CommandProcessingResultBuilder().withEntityId(11L).withSavingsId(11L).build();
        when(this.delegate.submitApplication(command)).thenReturn(result);
        when(this.savingsAccountRepository.findOneWithNotFoundDetection(11L)).thenReturn(account);

        final CommandProcessingResult actual = this.service.submitApplication(command);

        assertThat(actual).isSameAs(result);
        verify(this.chargeInterestRuleValidator).validateAccountUsesDailyPostingForCustomPeriodInterestCharge(account);
    }

    @Test
    void modifyApplicationValidatesTheFinalPersistedAccount() {
        final Long savingsId = 12L;
        final JsonCommand command = mock(JsonCommand.class);
        final SavingsAccount account = mock(SavingsAccount.class);
        final CommandProcessingResult result = new CommandProcessingResultBuilder().withEntityId(savingsId).withSavingsId(savingsId)
                .build();
        when(this.delegate.modifyApplication(savingsId, command)).thenReturn(result);
        when(this.savingsAccountRepository.findOneWithNotFoundDetection(savingsId)).thenReturn(account);

        final CommandProcessingResult actual = this.service.modifyApplication(savingsId, command);

        assertThat(actual).isSameAs(result);
        verify(this.chargeInterestRuleValidator).validateAccountUsesDailyPostingForCustomPeriodInterestCharge(account);
    }

    @Test
    void createActiveApplicationValidatesTheCreatedAccount() {
        final SavingsAccountDataDTO dto = mock(SavingsAccountDataDTO.class);
        final SavingsAccount account = mock(SavingsAccount.class);
        final CommandProcessingResult result = new CommandProcessingResultBuilder().withSavingsId(13L).build();
        when(this.delegate.createActiveApplication(dto)).thenReturn(result);
        when(this.savingsAccountRepository.findOneWithNotFoundDetection(13L)).thenReturn(account);

        final CommandProcessingResult actual = this.service.createActiveApplication(dto);

        assertThat(actual).isSameAs(result);
        verify(this.chargeInterestRuleValidator).validateAccountUsesDailyPostingForCustomPeriodInterestCharge(account);
    }
}
