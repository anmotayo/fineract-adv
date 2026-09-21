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
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.advancly.fineract.portfolio.savings.domain.DynamicDepositProduct;
import com.advancly.fineract.portfolio.savings.domain.DynamicDepositProductAssembler;
import com.advancly.fineract.portfolio.savings.domain.DynamicDepositProductRepository;
import com.advancly.fineract.portfolio.savings.testutil.MoneyHelperInitializer;
import com.advancly.fineract.portfolio.savings.validator.DynamicDepositProductDataValidator;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.apache.fineract.accounting.common.AccountingRuleType;
import org.apache.fineract.accounting.producttoaccountmapping.service.ProductToGLAccountMappingWritePlatformService;
import org.apache.fineract.infrastructure.core.api.JsonCommand;
import org.apache.fineract.infrastructure.security.service.PlatformSecurityContext;
import org.apache.fineract.organisation.monetary.domain.MonetaryCurrency;
import org.apache.fineract.portfolio.charge.domain.Charge;
import org.apache.fineract.portfolio.interestratechart.service.InterestRateChartAssembler;
import org.apache.fineract.portfolio.savings.DepositAccountType;
import org.apache.fineract.portfolio.savings.SavingsApiConstants;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class DynamicDepositProductWritePlatformServiceJpaRepositoryImplTest {

    private static final Long PRODUCT_ID = 42L;

    private PlatformSecurityContext context;
    private DynamicDepositProductRepository dynamicDepositProductRepository;
    private DynamicDepositProductDataValidator fromApiJsonDataValidator;
    private DynamicDepositProductAssembler dynamicDepositProductAssembler;
    private InterestRateChartAssembler chartAssembler;
    private ProductToGLAccountMappingWritePlatformService accountMappingWritePlatformService;
    private AdvanclyChargeInterestRuleValidator chargeInterestRuleValidator;
    private DynamicDepositProductWritePlatformServiceJpaRepositoryImpl service;

    @BeforeEach
    void setUp() {
        MoneyHelperInitializer.initialize();

        this.context = mock(PlatformSecurityContext.class);
        this.dynamicDepositProductRepository = mock(DynamicDepositProductRepository.class);
        this.fromApiJsonDataValidator = mock(DynamicDepositProductDataValidator.class);
        this.dynamicDepositProductAssembler = mock(DynamicDepositProductAssembler.class);
        this.chartAssembler = mock(InterestRateChartAssembler.class);
        this.accountMappingWritePlatformService = mock(ProductToGLAccountMappingWritePlatformService.class);
        this.chargeInterestRuleValidator = mock(AdvanclyChargeInterestRuleValidator.class);

        lenient().when(this.accountMappingWritePlatformService.updateSavingsProductToGLAccountMapping(any(), any(), anyBoolean(), anyInt(),
                eq(DepositAccountType.DYNAMIC_DEPOSIT))).thenReturn(new HashMap<>());

        this.service = new DynamicDepositProductWritePlatformServiceJpaRepositoryImpl(this.context, this.dynamicDepositProductRepository,
                this.fromApiJsonDataValidator, this.dynamicDepositProductAssembler, this.chartAssembler,
                this.accountMappingWritePlatformService, this.chargeInterestRuleValidator);
    }

    @Test
    void updatingAProductValidatesChargeDrivenRules() {
        final DynamicDepositProduct product = mock(DynamicDepositProduct.class);
        lenient().when(product.getId()).thenReturn(PRODUCT_ID);
        lenient().when(product.update(any())).thenReturn(new HashMap<>());
        lenient().when(product.getAccountingType()).thenReturn(AccountingRuleType.NONE.getValue());
        lenient().when(this.dynamicDepositProductRepository.findById(PRODUCT_ID)).thenReturn(Optional.of(product));

        final JsonCommand command = mock(JsonCommand.class);

        this.service.update(PRODUCT_ID, command);

        verify(this.chargeInterestRuleValidator).validateProductHasAtMostOneInterestCharge(product);
    }

    @Test
    void updatingAProductReplacesSubmittedDynamicDepositProductCharges() {
        final DynamicDepositProduct product = mock(DynamicDepositProduct.class);
        final Charge existingCharge = mock(Charge.class);
        final Charge submittedCharge = mock(Charge.class);
        final Set<Charge> submittedCharges = Set.of(submittedCharge);
        final Map<String, Object> changes = new HashMap<>();
        changes.put(SavingsApiConstants.chargesParamName, "[{\"id\":99}]");

        lenient().when(product.getId()).thenReturn(PRODUCT_ID);
        when(product.update(any())).thenReturn(changes);
        when(product.charges()).thenReturn(Set.of(existingCharge));
        when(product.currency()).thenReturn(new MonetaryCurrency("USD", 2, 0));
        lenient().when(product.getAccountingType()).thenReturn(AccountingRuleType.NONE.getValue());
        when(this.dynamicDepositProductRepository.findById(PRODUCT_ID)).thenReturn(Optional.of(product));
        when(this.dynamicDepositProductAssembler.assembleListOfSavingsProductCharges(any(), eq("USD"),
                eq(SavingsApiConstants.chargesParamName), eq(DepositAccountType.DYNAMIC_DEPOSIT))).thenReturn(submittedCharges);

        final JsonCommand command = mock(JsonCommand.class);

        this.service.update(PRODUCT_ID, command);

        verify(product).setCharges(submittedCharges);
    }
}
