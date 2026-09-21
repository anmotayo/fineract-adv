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
import static com.advancly.fineract.portfolio.savings.DynamicDepositApiConstants.earlyWithdrawalPenaltyEnabledParamName;

import com.advancly.fineract.portfolio.savings.domain.DepositProductDynamicDetail;
import com.advancly.fineract.portfolio.savings.domain.DepositProductDynamicDetailRepository;
import java.util.Optional;
import org.apache.fineract.infrastructure.core.api.JsonCommand;
import org.apache.fineract.infrastructure.core.data.CommandProcessingResult;
import org.apache.fineract.portfolio.savings.SavingsApiConstants;
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
 * Widens early-withdrawal-charge configuration (implementation plan Phase 2, Section 1) from Dynamic-Deposit-only to
 * any plain Savings product, reusing the same {@code m_deposit_product_dynamic_detail} /
 * {@code m_savings_product_early_withdrawal_charge} rows Dynamic Deposit already writes. Delegates the actual product
 * create/update entirely to core; only adds the reconciliation step afterward, keyed off whether the request mentions
 * any of the three early-withdrawal parameters (so a product that never touches this feature is completely unaffected).
 */
@Service
@Primary
public class AdvanclySavingsProductWritePlatformService implements SavingsProductWritePlatformService {

    private final SavingsProductWritePlatformService delegate;
    private final SavingsProductRepository savingsProductRepository;
    private final DepositProductDynamicDetailRepository productDynamicDetailRepository;
    private final EarlyWithdrawalChargeReconciler earlyWithdrawalChargeReconciler;
    private final AdvanclyChargeInterestRuleValidator chargeInterestRuleValidator;

    @Autowired
    public AdvanclySavingsProductWritePlatformService(
            @Qualifier("coreSavingsProductWritePlatformService") final SavingsProductWritePlatformService delegate,
            final SavingsProductRepository savingsProductRepository,
            final DepositProductDynamicDetailRepository productDynamicDetailRepository,
            final EarlyWithdrawalChargeReconciler earlyWithdrawalChargeReconciler,
            final AdvanclyChargeInterestRuleValidator chargeInterestRuleValidator) {
        this.delegate = delegate;
        this.savingsProductRepository = savingsProductRepository;
        this.productDynamicDetailRepository = productDynamicDetailRepository;
        this.earlyWithdrawalChargeReconciler = earlyWithdrawalChargeReconciler;
        this.chargeInterestRuleValidator = chargeInterestRuleValidator;
    }

    AdvanclySavingsProductWritePlatformService(
            @Qualifier("coreSavingsProductWritePlatformService") final SavingsProductWritePlatformService delegate,
            final SavingsProductRepository savingsProductRepository,
            final DepositProductDynamicDetailRepository productDynamicDetailRepository,
            final EarlyWithdrawalChargeReconciler earlyWithdrawalChargeReconciler) {
        this(delegate, savingsProductRepository, productDynamicDetailRepository, earlyWithdrawalChargeReconciler, null);
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
        final boolean mentionsEarlyWithdrawal = command.parameterExists(earlyWithdrawalPenaltyEnabledParamName)
                || command.parameterExists(earlyWithdrawalChargeIdParamName) || command.parameterExists(earlyWithdrawalChargeModeParamName);
        final Optional<DepositProductDynamicDetail> existing = this.productDynamicDetailRepository.findByProductId(productId);
        if (!mentionsEarlyWithdrawal && existing.isEmpty() && this.chargeInterestRuleValidator == null) {
            return;
        }
        final SavingsProduct product = this.savingsProductRepository.findById(productId)
                .orElseThrow(() -> new SavingsProductNotFoundException(productId));
        validateChargeDrivenRules(product);
        if (!mentionsEarlyWithdrawal && existing.isEmpty()) {
            return;
        }

        final DepositProductDynamicDetail productDetail;
        if (existing.isPresent()) {
            productDetail = existing.get();
            productDetail.update(command);
            this.productDynamicDetailRepository.saveAndFlush(productDetail);
        } else {
            final boolean earlyWithdrawalPenaltyEnabled = command.parameterExists(earlyWithdrawalPenaltyEnabledParamName)
                    && command.booleanPrimitiveValueOfParameterNamed(earlyWithdrawalPenaltyEnabledParamName);
            productDetail = DepositProductDynamicDetail.createNew(product, false, false, earlyWithdrawalPenaltyEnabled);
            this.productDynamicDetailRepository.saveAndFlush(productDetail);
        }

        this.earlyWithdrawalChargeReconciler.reconcile(productId, command, productDetail.isEarlyWithdrawalPenaltyEnabled(),
                product.charges(), product.interestCompoundingPeriodType(), earlyWithdrawalChargeIdParamName,
                earlyWithdrawalChargeModeParamName, SavingsApiConstants.SAVINGS_PRODUCT_RESOURCE_NAME);
    }

    private void validateChargeDrivenRules(final SavingsProduct product) {
        if (this.chargeInterestRuleValidator != null) {
            this.chargeInterestRuleValidator.validateProductHasAtMostOneInterestCharge(product);
        }
    }
}
