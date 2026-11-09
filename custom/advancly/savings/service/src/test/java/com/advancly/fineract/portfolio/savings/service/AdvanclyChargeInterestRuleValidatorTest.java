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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.advancly.fineract.portfolio.savings.domain.AdvanclyChargeInterestRule;
import com.advancly.fineract.portfolio.savings.domain.AdvanclyChargeInterestRuleRepository;
import com.advancly.fineract.portfolio.savings.domain.InterestBasisMode;
import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
import org.apache.fineract.infrastructure.core.exception.GeneralPlatformDomainRuleException;
import org.apache.fineract.portfolio.charge.domain.Charge;
import org.apache.fineract.portfolio.charge.domain.ChargeCalculationType;
import org.apache.fineract.portfolio.charge.domain.ChargeTimeType;
import org.apache.fineract.portfolio.savings.domain.SavingsProduct;
import org.junit.jupiter.api.Test;

class AdvanclyChargeInterestRuleValidatorTest {

    @Test
    void resolvesTheRuleBearingChargeAttachedToAProduct() {
        final AdvanclyChargeInterestRuleRepository repository = mock(AdvanclyChargeInterestRuleRepository.class);
        final AdvanclyChargeInterestRuleValidator validator = new AdvanclyChargeInterestRuleValidator(repository);
        final SavingsProduct product = mock(SavingsProduct.class);
        final Charge charge = validRuleCharge(7L);
        final AdvanclyChargeInterestRule rule = AdvanclyChargeInterestRule.createNew(charge, InterestBasisMode.CUMULATIVE, null);

        when(product.getId()).thenReturn(42L);
        when(product.charges()).thenReturn(Set.of(charge));
        when(repository.findByChargeIdIn(Set.of(7L))).thenReturn(List.of(rule));

        assertThat(validator.resolveSingleInterestCharge(product)).isSameAs(charge);
    }

    @Test
    void rejectsAProductRuleWhoseChargeNoLongerHasTheRequiredShape() {
        final AdvanclyChargeInterestRuleRepository repository = mock(AdvanclyChargeInterestRuleRepository.class);
        final AdvanclyChargeInterestRuleValidator validator = new AdvanclyChargeInterestRuleValidator(repository);
        final SavingsProduct product = mock(SavingsProduct.class);
        final Charge charge = validRuleCharge(7L);
        final AdvanclyChargeInterestRule rule = AdvanclyChargeInterestRule.createNew(charge, InterestBasisMode.CUMULATIVE, null);

        when(product.getId()).thenReturn(42L);
        when(product.charges()).thenReturn(Set.of(charge));
        when(product.interestPostingPeriodType()).thenReturn(null);
        when(charge.isActive()).thenReturn(false);
        when(repository.findByChargeIdIn(Set.of(7L))).thenReturn(List.of(rule));

        assertThatThrownBy(() -> validator.validateProductHasAtMostOneInterestCharge(product))
                .isInstanceOf(GeneralPlatformDomainRuleException.class)
                .hasMessageContaining("early-withdrawal interest charge must be active");
    }

    private Charge validRuleCharge(final Long id) {
        final Charge charge = mock(Charge.class);
        lenient().when(charge.getId()).thenReturn(id);
        lenient().when(charge.getAmount()).thenReturn(BigDecimal.TEN);
        lenient().when(charge.isSavingsCharge()).thenReturn(true);
        lenient().when(charge.isActive()).thenReturn(true);
        lenient().when(charge.isDeleted()).thenReturn(false);
        lenient().when(charge.isPenalty()).thenReturn(true);
        lenient().when(charge.getChargeCalculation()).thenReturn(ChargeCalculationType.PERCENT_OF_INTEREST.getValue());
        lenient().when(charge.getChargeTimeType()).thenReturn(ChargeTimeType.WITHDRAWAL_FEE.getValue());
        return charge;
    }
}
