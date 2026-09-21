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

import static com.advancly.fineract.portfolio.savings.DynamicDepositApiConstants.earlyWithdrawalChargeIdParamName;
import static com.advancly.fineract.portfolio.savings.DynamicDepositApiConstants.earlyWithdrawalChargeModeParamName;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.advancly.fineract.portfolio.savings.domain.DynamicDepositProduct;
import com.advancly.fineract.portfolio.savings.domain.DynamicDepositProductAssembler;
import com.advancly.fineract.portfolio.savings.domain.DynamicDepositProductRepository;
import com.advancly.fineract.portfolio.savings.domain.EarlyWithdrawalChargeMode;
import com.advancly.fineract.portfolio.savings.domain.SavingsProductEarlyWithdrawalCharge;
import com.advancly.fineract.portfolio.savings.domain.SavingsProductEarlyWithdrawalChargeRepository;
import com.advancly.fineract.portfolio.savings.testutil.MoneyHelperInitializer;
import com.advancly.fineract.portfolio.savings.validator.DynamicDepositProductDataValidator;
import java.util.HashMap;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.apache.fineract.accounting.common.AccountingRuleType;
import org.apache.fineract.accounting.producttoaccountmapping.service.ProductToGLAccountMappingWritePlatformService;
import org.apache.fineract.infrastructure.core.api.JsonCommand;
import org.apache.fineract.infrastructure.security.service.PlatformSecurityContext;
import org.apache.fineract.portfolio.charge.domain.Charge;
import org.apache.fineract.portfolio.charge.domain.ChargeCalculationType;
import org.apache.fineract.portfolio.charge.domain.ChargeTimeType;
import org.apache.fineract.portfolio.interestratechart.service.InterestRateChartAssembler;
import org.apache.fineract.portfolio.savings.DepositAccountType;
import org.apache.fineract.portfolio.savings.SavingsCompoundingInterestPeriodType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class DynamicDepositProductWritePlatformServiceJpaRepositoryImplTest {

    private static final Long PRODUCT_ID = 42L;
    private static final Long CHARGE_ID = 7L;

    private PlatformSecurityContext context;
    private DynamicDepositProductRepository dynamicDepositProductRepository;
    private DynamicDepositProductDataValidator fromApiJsonDataValidator;
    private DynamicDepositProductAssembler dynamicDepositProductAssembler;
    private SavingsProductEarlyWithdrawalChargeRepository earlyWithdrawalChargeRepository;
    private InterestRateChartAssembler chartAssembler;
    private ProductToGLAccountMappingWritePlatformService accountMappingWritePlatformService;
    private EarlyWithdrawalChargeReconciler earlyWithdrawalChargeReconciler;
    private AdvanclyChargeInterestRuleValidator chargeInterestRuleValidator;
    private DynamicDepositProductWritePlatformServiceJpaRepositoryImpl service;

    @BeforeEach
    void setUp() {
        MoneyHelperInitializer.initialize();

        this.context = mock(PlatformSecurityContext.class);
        this.dynamicDepositProductRepository = mock(DynamicDepositProductRepository.class);
        this.fromApiJsonDataValidator = mock(DynamicDepositProductDataValidator.class);
        this.dynamicDepositProductAssembler = mock(DynamicDepositProductAssembler.class);
        this.earlyWithdrawalChargeRepository = mock(SavingsProductEarlyWithdrawalChargeRepository.class);
        this.chartAssembler = mock(InterestRateChartAssembler.class);
        this.accountMappingWritePlatformService = mock(ProductToGLAccountMappingWritePlatformService.class);
        this.earlyWithdrawalChargeReconciler = new EarlyWithdrawalChargeReconciler(this.earlyWithdrawalChargeRepository);
        this.chargeInterestRuleValidator = mock(AdvanclyChargeInterestRuleValidator.class);

        lenient().when(this.accountMappingWritePlatformService.updateSavingsProductToGLAccountMapping(any(), any(), anyBoolean(), anyInt(),
                eq(DepositAccountType.DYNAMIC_DEPOSIT))).thenReturn(new HashMap<>());

        this.service = new DynamicDepositProductWritePlatformServiceJpaRepositoryImpl(this.context, this.dynamicDepositProductRepository,
                this.fromApiJsonDataValidator, this.dynamicDepositProductAssembler, this.earlyWithdrawalChargeRepository,
                this.chartAssembler, this.accountMappingWritePlatformService, this.earlyWithdrawalChargeReconciler,
                this.chargeInterestRuleValidator);
    }

    @Test
    void updatingAProductWithoutSendingEarlyWithdrawalChargeModeKeepsItsExistingCumulativeMode() {
        final Charge charge = charge(CHARGE_ID, true, true, ChargeCalculationType.PERCENT_OF_INTEREST);
        final DynamicDepositProduct product = mock(DynamicDepositProduct.class);
        lenient().when(product.getId()).thenReturn(PRODUCT_ID);
        lenient().when(product.update(any())).thenReturn(new HashMap<>());
        lenient().when(product.isEarlyWithdrawalPenaltyEnabled()).thenReturn(true);
        lenient().when(product.charges()).thenReturn(Set.of(charge));
        lenient().when(product.interestCompoundingPeriodType())
                .thenReturn(SavingsCompoundingInterestPeriodType.NO_COMPOUNDING_SIMPLE_INTEREST);
        lenient().when(product.getAccountingType()).thenReturn(AccountingRuleType.NONE.getValue());
        lenient().when(this.dynamicDepositProductRepository.findById(PRODUCT_ID)).thenReturn(Optional.of(product));

        final List<SavingsProductEarlyWithdrawalCharge> existingRows = List
                .of(SavingsProductEarlyWithdrawalCharge.createNew(PRODUCT_ID, CHARGE_ID, EarlyWithdrawalChargeMode.CUMULATIVE));
        lenient().when(this.earlyWithdrawalChargeRepository.findBySavingsProductId(PRODUCT_ID)).thenReturn(existingRows);

        // The update request touches only unrelated fields: neither earlyWithdrawalChargeId nor
        // earlyWithdrawalChargeMode is present in the payload at all.
        final JsonCommand command = mock(JsonCommand.class);
        lenient().when(command.parameterExists(earlyWithdrawalChargeIdParamName)).thenReturn(false);
        lenient().when(command.parameterExists(earlyWithdrawalChargeModeParamName)).thenReturn(false);

        this.service.update(PRODUCT_ID, command);

        verify(this.chargeInterestRuleValidator).validateProductHasAtMostOneInterestCharge(product);
        final ArgumentCaptor<SavingsProductEarlyWithdrawalCharge> captor = ArgumentCaptor
                .forClass(SavingsProductEarlyWithdrawalCharge.class);
        verify(this.earlyWithdrawalChargeRepository).saveAndFlush(captor.capture());
        assertThat(captor.getValue().mode()).isEqualTo(EarlyWithdrawalChargeMode.CUMULATIVE);
    }

    private Charge charge(final Long id, final boolean active, final boolean penalty, final ChargeCalculationType calculationType) {
        final Charge charge = mock(Charge.class);
        lenient().when(charge.getId()).thenReturn(id);
        lenient().when(charge.isActive()).thenReturn(active);
        lenient().when(charge.isPenalty()).thenReturn(penalty);
        lenient().when(charge.getChargeCalculation()).thenReturn(calculationType.getValue());
        lenient().when(charge.getChargeTimeType()).thenReturn(ChargeTimeType.WITHDRAWAL_FEE.getValue());
        return charge;
    }
}
