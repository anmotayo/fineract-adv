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

import org.apache.fineract.infrastructure.core.api.JsonCommand;
import org.apache.fineract.infrastructure.core.data.CommandProcessingResult;
import org.apache.fineract.portfolio.savings.domain.SavingsProduct;
import org.apache.fineract.portfolio.savings.domain.SavingsProductRepository;
import org.apache.fineract.portfolio.savings.exception.SavingsProductNotFoundException;
import org.apache.fineract.portfolio.savings.service.SavingsProductWritePlatformService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Adds Advancly charge-driven interest-rule validation around core Savings product writes. The legacy
 * {@code earlyWithdrawalCharge*} product-level selection is no longer part of the supported backend contract; a product
 * participates in early-withdrawal interest charging by attaching a charge that has an Advancly interest rule.
 */
@Service
@Primary
public class AdvanclySavingsProductWritePlatformService implements SavingsProductWritePlatformService {

    private final SavingsProductWritePlatformService delegate;
    private final SavingsProductRepository savingsProductRepository;
    private final AdvanclyChargeInterestRuleValidator chargeInterestRuleValidator;

    @Autowired
    public AdvanclySavingsProductWritePlatformService(
            @Qualifier("coreSavingsProductWritePlatformService") final SavingsProductWritePlatformService delegate,
            final SavingsProductRepository savingsProductRepository,
            final AdvanclyChargeInterestRuleValidator chargeInterestRuleValidator) {
        this.delegate = delegate;
        this.savingsProductRepository = savingsProductRepository;
        this.chargeInterestRuleValidator = chargeInterestRuleValidator;
    }

    AdvanclySavingsProductWritePlatformService(
            @Qualifier("coreSavingsProductWritePlatformService") final SavingsProductWritePlatformService delegate,
            final SavingsProductRepository savingsProductRepository) {
        this(delegate, savingsProductRepository, null);
    }

    @Transactional
    @Override
    public CommandProcessingResult create(final JsonCommand command) {
        final CommandProcessingResult result = this.delegate.create(command);
        reconcile(result.getResourceId(), command);
        return result;
    }

    @Transactional
    @Override
    public CommandProcessingResult update(final Long productId, final JsonCommand command) {
        final CommandProcessingResult result = this.delegate.update(productId, command);
        reconcile(productId, command);
        return result;
    }

    @Override
    public CommandProcessingResult delete(final Long productId) {
        return this.delegate.delete(productId);
    }

    private void reconcile(final Long productId, final JsonCommand command) {
        if (this.chargeInterestRuleValidator == null) {
            return;
        }
        final SavingsProduct product = this.savingsProductRepository.findById(productId)
                .orElseThrow(() -> new SavingsProductNotFoundException(productId));
        validateChargeDrivenRules(product);
    }

    private void validateChargeDrivenRules(final SavingsProduct product) {
        if (this.chargeInterestRuleValidator != null) {
            this.chargeInterestRuleValidator.validateProductHasAtMostOneInterestCharge(product);
        }
    }
}
