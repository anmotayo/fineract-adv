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

import java.math.BigDecimal;
import java.util.HashSet;
import java.util.Set;
import org.apache.fineract.infrastructure.core.api.JsonCommand;
import org.apache.fineract.infrastructure.core.data.CommandProcessingResult;
import org.apache.fineract.infrastructure.core.data.CommandProcessingResultBuilder;
import org.apache.fineract.portfolio.charge.domain.Charge;
import org.apache.fineract.portfolio.charge.domain.ChargeCalculationType;
import org.apache.fineract.portfolio.charge.domain.ChargeTimeType;
import org.apache.fineract.portfolio.savings.data.SavingsAccountDataDTO;
import org.apache.fineract.portfolio.savings.domain.SavingsAccount;
import org.apache.fineract.portfolio.savings.domain.SavingsAccountCharge;
import org.apache.fineract.portfolio.savings.domain.SavingsAccountRepositoryWrapper;
import org.apache.fineract.portfolio.savings.domain.SavingsProduct;
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
    void submitApplicationAttachesTheProductEarlyWithdrawalChargeWhenMissing() {
        final JsonCommand command = mock(JsonCommand.class);
        final SavingsAccount account = mock(SavingsAccount.class);
        final SavingsProduct product = mock(SavingsProduct.class);
        final Charge earlyWithdrawalCharge = percentOfInterestChargeDefinition(21L, new BigDecimal("7.5"));
        final Set<SavingsAccountCharge> charges = new HashSet<>();
        final CommandProcessingResult result = new CommandProcessingResultBuilder().withEntityId(11L).withSavingsId(11L).build();
        when(this.delegate.submitApplication(command)).thenReturn(result);
        when(this.savingsAccountRepository.findOneWithNotFoundDetection(11L)).thenReturn(account);
        when(account.savingsProduct()).thenReturn(product);
        when(account.charges()).thenReturn(charges);
        when(this.chargeInterestRuleValidator.resolveSingleInterestCharge(product)).thenReturn(earlyWithdrawalCharge);

        final CommandProcessingResult actual = this.service.submitApplication(command);

        assertThat(actual).isSameAs(result);
        assertThat(charges).hasSize(1);
        final SavingsAccountCharge attachedCharge = charges.iterator().next();
        assertThat(attachedCharge.getCharge()).isSameAs(earlyWithdrawalCharge);
        assertThat(attachedCharge.getPercentage()).isEqualByComparingTo("7.5");
        assertThat(attachedCharge.isPenaltyCharge()).isTrue();
        verify(this.savingsAccountRepository).saveAndFlush(account);
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

    private Charge percentOfInterestChargeDefinition(final Long id, final BigDecimal percentage) {
        final Charge charge = mock(Charge.class);
        when(charge.getId()).thenReturn(id);
        when(charge.getAmount()).thenReturn(percentage);
        when(charge.getChargeCalculation()).thenReturn(ChargeCalculationType.PERCENT_OF_INTEREST.getValue());
        when(charge.getChargeTimeType()).thenReturn(ChargeTimeType.WITHDRAWAL_FEE.getValue());
        when(charge.isPenalty()).thenReturn(true);
        return charge;
    }
}
