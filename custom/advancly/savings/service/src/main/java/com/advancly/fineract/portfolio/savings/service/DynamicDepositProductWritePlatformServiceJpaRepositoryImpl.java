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

import static com.advancly.fineract.portfolio.savings.DynamicDepositApiConstants.DYNAMIC_DEPOSIT_PRODUCT_RESOURCE_NAME;
import static com.advancly.fineract.portfolio.savings.DynamicDepositApiConstants.earlyWithdrawalChargeIdParamName;
import static com.advancly.fineract.portfolio.savings.DynamicDepositApiConstants.earlyWithdrawalChargeModeParamName;

import com.advancly.fineract.portfolio.savings.domain.DynamicDepositProduct;
import com.advancly.fineract.portfolio.savings.domain.DynamicDepositProductAssembler;
import com.advancly.fineract.portfolio.savings.domain.DynamicDepositProductRepository;
import com.advancly.fineract.portfolio.savings.domain.SavingsProductEarlyWithdrawalChargeRepository;
import com.advancly.fineract.portfolio.savings.exception.DynamicDepositProductNotFoundException;
import com.advancly.fineract.portfolio.savings.validator.DynamicDepositProductDataValidator;
import jakarta.persistence.PersistenceException;
import java.util.Map;
import java.util.Set;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.apache.fineract.accounting.producttoaccountmapping.service.ProductToGLAccountMappingWritePlatformService;
import org.apache.fineract.infrastructure.core.api.JsonCommand;
import org.apache.fineract.infrastructure.core.data.CommandProcessingResult;
import org.apache.fineract.infrastructure.core.data.CommandProcessingResultBuilder;
import org.apache.fineract.infrastructure.core.exception.ErrorHandler;
import org.apache.fineract.infrastructure.security.service.PlatformSecurityContext;
import org.apache.fineract.portfolio.charge.domain.Charge;
import org.apache.fineract.portfolio.interestratechart.service.InterestRateChartAssembler;
import org.apache.fineract.portfolio.savings.DepositAccountType;
import org.apache.fineract.portfolio.savings.SavingsApiConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Lean, self-contained write-side implementation for the {@code DYNAMICDEPOSITPRODUCT} entity. Structurally mirrors
 * {@code FixedDepositProductWritePlatformServiceJpaRepositoryImpl}, without GL-account-mapping (deferred - see the
 * implementation report).
 */
@Service
public class DynamicDepositProductWritePlatformServiceJpaRepositoryImpl implements DynamicDepositProductWritePlatformService {

    private static final Logger LOG = LoggerFactory.getLogger(DynamicDepositProductWritePlatformServiceJpaRepositoryImpl.class);

    private final PlatformSecurityContext context;
    private final DynamicDepositProductRepository dynamicDepositProductRepository;
    private final DynamicDepositProductDataValidator fromApiJsonDataValidator;
    private final DynamicDepositProductAssembler dynamicDepositProductAssembler;
    private final SavingsProductEarlyWithdrawalChargeRepository earlyWithdrawalChargeRepository;
    private final InterestRateChartAssembler chartAssembler;
    private final ProductToGLAccountMappingWritePlatformService accountMappingWritePlatformService;
    private final EarlyWithdrawalChargeReconciler earlyWithdrawalChargeReconciler;
    private final AdvanclyChargeInterestRuleValidator chargeInterestRuleValidator;

    public DynamicDepositProductWritePlatformServiceJpaRepositoryImpl(final PlatformSecurityContext context,
            final DynamicDepositProductRepository dynamicDepositProductRepository,
            final DynamicDepositProductDataValidator fromApiJsonDataValidator,
            final DynamicDepositProductAssembler dynamicDepositProductAssembler,
            final SavingsProductEarlyWithdrawalChargeRepository earlyWithdrawalChargeRepository,
            final InterestRateChartAssembler chartAssembler,
            final ProductToGLAccountMappingWritePlatformService accountMappingWritePlatformService,
            final EarlyWithdrawalChargeReconciler earlyWithdrawalChargeReconciler,
            final AdvanclyChargeInterestRuleValidator chargeInterestRuleValidator) {
        this.context = context;
        this.dynamicDepositProductRepository = dynamicDepositProductRepository;
        this.fromApiJsonDataValidator = fromApiJsonDataValidator;
        this.dynamicDepositProductAssembler = dynamicDepositProductAssembler;
        this.earlyWithdrawalChargeRepository = earlyWithdrawalChargeRepository;
        this.chartAssembler = chartAssembler;
        this.accountMappingWritePlatformService = accountMappingWritePlatformService;
        this.earlyWithdrawalChargeReconciler = earlyWithdrawalChargeReconciler;
        this.chargeInterestRuleValidator = chargeInterestRuleValidator;
    }

    @Transactional
    @Override
    public CommandProcessingResult create(final JsonCommand command) {
        try {
            this.context.authenticatedUser();
            this.fromApiJsonDataValidator.validateForCreate(command.json());

            final DynamicDepositProduct product = this.dynamicDepositProductAssembler.assembleDynamicDepositProduct(command);
            validateChargeDrivenRules(product);

            this.dynamicDepositProductRepository.saveAndFlush(product);
            this.accountMappingWritePlatformService.createSavingProductToGLAccountMapping(product.getId(), command,
                    DepositAccountType.DYNAMIC_DEPOSIT);
            reconcileEarlyWithdrawalChargeSelection(product, command);

            return new CommandProcessingResultBuilder() //
                    .withEntityId(product.getId()) //
                    .build();
        } catch (final DataAccessException e) {
            handleDataIntegrityIssues(command, e.getMostSpecificCause(), e);
            return CommandProcessingResult.empty();
        } catch (final PersistenceException dve) {
            handleDataIntegrityIssues(command, ExceptionUtils.getRootCause(dve.getCause()), dve);
            return CommandProcessingResult.empty();
        }
    }

    @Transactional
    @Override
    public CommandProcessingResult update(final Long productId, final JsonCommand command) {
        try {
            this.context.authenticatedUser();
            this.fromApiJsonDataValidator.validateForUpdate(command.json());

            final DynamicDepositProduct product = this.dynamicDepositProductRepository.findById(productId)
                    .orElseThrow(() -> new DynamicDepositProductNotFoundException(productId));
            product.setHelpers(this.chartAssembler);

            final Map<String, Object> changes = product.update(command);

            if (changes.containsKey(SavingsApiConstants.chargesParamName)) {
                final Set<Charge> savingsProductCharges = this.dynamicDepositProductAssembler.assembleListOfSavingsProductCharges(command,
                        product.currency().getCode(), SavingsApiConstants.chargesParamName, DepositAccountType.DYNAMIC_DEPOSIT);
                final boolean updated = product.update(savingsProductCharges, null);
                if (!updated) {
                    changes.remove(SavingsApiConstants.chargesParamName);
                }
            }

            if (changes.containsKey(SavingsApiConstants.taxGroupIdParamName)) {
                product.setTaxGroup(this.dynamicDepositProductAssembler.assembleTaxGroup(command));
            }

            validateChargeDrivenRules(product);

            final boolean accountingTypeChanged = changes.containsKey(SavingsApiConstants.accountingRuleParamName);
            final Map<String, Object> accountingMappingChanges = this.accountMappingWritePlatformService
                    .updateSavingsProductToGLAccountMapping(product.getId(), command, accountingTypeChanged, product.getAccountingType(),
                            DepositAccountType.DYNAMIC_DEPOSIT);
            changes.putAll(accountingMappingChanges);

            if (!changes.isEmpty()) {
                this.dynamicDepositProductRepository.saveAndFlush(product);
            }

            // Reconciled unconditionally and AFTER the charges block above, so the selection is always validated
            // against the product's current m_savings_product_charge set. An update that removes or deactivates the
            // selected charge while the penalty is still enabled therefore fails loudly rather than leaving the
            // product in a state where early withdrawal silently charges nothing.
            reconcileEarlyWithdrawalChargeSelection(product, command);

            return new CommandProcessingResultBuilder() //
                    .withEntityId(product.getId()) //
                    .with(changes).build();
        } catch (final DataAccessException e) {
            handleDataIntegrityIssues(command, e.getMostSpecificCause(), e);
            return CommandProcessingResult.empty();
        } catch (final PersistenceException dve) {
            handleDataIntegrityIssues(command, ExceptionUtils.getRootCause(dve.getCause()), dve);
            return CommandProcessingResult.empty();
        }
    }

    @Transactional
    @Override
    public CommandProcessingResult delete(final Long productId) {
        this.context.authenticatedUser();
        final DynamicDepositProduct product = this.dynamicDepositProductRepository.findById(productId)
                .orElseThrow(() -> new DynamicDepositProductNotFoundException(productId));

        this.dynamicDepositProductRepository.delete(product);

        return new CommandProcessingResultBuilder() //
                .withEntityId(product.getId()) //
                .build();
    }

    /**
     * Applies the request's early-withdrawal penalty charge selection (implementation plan Section 2) with
     * replace-rather-than-append semantics, which is the primary enforcement of "only one active early-withdrawal
     * charge per dynamic deposit product". When the request omits {@code earlyWithdrawalChargeId} or
     * {@code earlyWithdrawalChargeMode} entirely, the currently-stored value is re-used instead, so a partial product
     * update never silently drops or downgrades either one.
     */
    private void reconcileEarlyWithdrawalChargeSelection(final DynamicDepositProduct product, final JsonCommand command) {
        this.earlyWithdrawalChargeReconciler.reconcile(product.getId(), command, product.isEarlyWithdrawalPenaltyEnabled(),
                product.charges(), product.interestCompoundingPeriodType(), earlyWithdrawalChargeIdParamName,
                earlyWithdrawalChargeModeParamName, DYNAMIC_DEPOSIT_PRODUCT_RESOURCE_NAME);
    }

    private void validateChargeDrivenRules(final DynamicDepositProduct product) {
        this.chargeInterestRuleValidator.validateProductHasAtMostOneInterestCharge(product);
    }

    private void handleDataIntegrityIssues(final JsonCommand command, final Throwable realCause, final Exception dae) {
        String msgCode = "error.msg.dynamicdepositproduct";
        String msg = "Unknown data integrity issue with dynamic deposit product.";
        String param = null;
        Object[] msgArgs;
        final Throwable checkEx = realCause == null ? dae : realCause;
        if (checkEx.getMessage() != null && checkEx.getMessage().contains("sp_unq_name")) {
            final String name = command.stringValueOfParameterNamed("name");
            msgCode += ".duplicate.name";
            msg = "Dynamic deposit product with name `" + name + "` already exists";
            param = "name";
            msgArgs = new Object[] { name, dae };
        } else if (checkEx.getMessage() != null && checkEx.getMessage().contains("sp_unq_short_name")) {
            final String shortName = command.stringValueOfParameterNamed("shortName");
            msgCode += ".duplicate.short.name";
            msg = "Dynamic deposit product with short name `" + shortName + "` already exists";
            param = "shortName";
            msgArgs = new Object[] { shortName, dae };
        } else {
            msgCode += ".unknown.data.integrity.issue";
            msgArgs = new Object[] { dae };
        }
        LOG.error("Error occurred.", dae);
        throw ErrorHandler.getMappable(dae, msgCode, msg, param, msgArgs);
    }
}
