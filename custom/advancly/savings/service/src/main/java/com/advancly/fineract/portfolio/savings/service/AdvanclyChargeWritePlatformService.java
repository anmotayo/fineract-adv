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

import com.advancly.fineract.portfolio.savings.domain.AdvanclyChargeInterestRule;
import com.advancly.fineract.portfolio.savings.domain.AdvanclyChargeInterestRuleRepository;
import com.advancly.fineract.portfolio.savings.domain.CustomPeriodReapplyPolicy;
import com.advancly.fineract.portfolio.savings.domain.InterestBasisMode;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.apache.fineract.infrastructure.core.api.JsonCommand;
import org.apache.fineract.infrastructure.core.data.CommandProcessingResult;
import org.apache.fineract.infrastructure.core.data.CommandProcessingResultBuilder;
import org.apache.fineract.portfolio.charge.api.ChargesApiConstants;
import org.apache.fineract.portfolio.charge.domain.Charge;
import org.apache.fineract.portfolio.charge.domain.ChargeRepository;
import org.apache.fineract.portfolio.charge.exception.ChargeNotFoundException;
import org.apache.fineract.portfolio.charge.service.ChargeWritePlatformService;
import org.springframework.transaction.annotation.Transactional;

@RequiredArgsConstructor
public class AdvanclyChargeWritePlatformService implements ChargeWritePlatformService {

    private final ChargeWritePlatformService delegate;
    private final ChargeRepository chargeRepository;
    private final AdvanclyChargeInterestRuleRepository chargeInterestRuleRepository;
    private final AdvanclyChargeInterestRuleValidator chargeInterestRuleValidator;

    @Override
    @Transactional
    public CommandProcessingResult createCharge(final JsonCommand command) {
        final CommandProcessingResult result = this.delegate.createCharge(command);
        final Map<String, Object> ruleChanges = syncInterestChargeRule(result.getResourceId(), command);
        return withRuleChanges(result, result.getResourceId(), ruleChanges);
    }

    @Override
    @Transactional
    public CommandProcessingResult updateCharge(final Long chargeId, final JsonCommand command) {
        final CommandProcessingResult result = this.delegate.updateCharge(chargeId, command);
        final Map<String, Object> ruleChanges = syncInterestChargeRule(chargeId, command);
        return withRuleChanges(result, chargeId, ruleChanges);
    }

    @Override
    @Transactional
    public CommandProcessingResult deleteCharge(final Long chargeId) {
        final CommandProcessingResult result = this.delegate.deleteCharge(chargeId);
        this.chargeInterestRuleRepository.findByChargeId(chargeId).ifPresent(this.chargeInterestRuleRepository::delete);
        return result;
    }

    private Map<String, Object> syncInterestChargeRule(final Long chargeId, final JsonCommand command) {
        final Map<String, Object> changes = new LinkedHashMap<>();
        final boolean interestBasisModePassed = command.parameterExists(ChargesApiConstants.interestBasisModeParamName);
        final boolean customPeriodPolicyPassed = command.parameterExists(ChargesApiConstants.customPeriodReapplyPolicyParamName);

        final Charge charge = this.chargeRepository.findById(chargeId).orElseThrow(() -> new ChargeNotFoundException(chargeId));
        final Optional<AdvanclyChargeInterestRule> existingRule = this.chargeInterestRuleRepository.findByChargeId(chargeId);
        if (!interestBasisModePassed && !customPeriodPolicyPassed) {
            existingRule.ifPresent(rule -> this.chargeInterestRuleValidator.validateRule(charge, rule.interestBasisMode(),
                    rule.customPeriodReapplyPolicy()));
            return changes;
        }

        if (interestBasisModePassed && !command.hasParameterValue(ChargesApiConstants.interestBasisModeParamName)) {
            existingRule.ifPresent(this.chargeInterestRuleRepository::delete);
            changes.put(ChargesApiConstants.interestBasisModeParamName, null);
            changes.put(ChargesApiConstants.customPeriodReapplyPolicyParamName, null);
            return changes;
        }

        final InterestBasisMode interestBasisMode = interestBasisMode(command, existingRule);
        if (interestBasisMode == null) {
            return changes;
        }

        final CustomPeriodReapplyPolicy customPeriodReapplyPolicy = customPeriodReapplyPolicy(command, existingRule, interestBasisMode);
        this.chargeInterestRuleValidator.validateRule(charge, interestBasisMode, customPeriodReapplyPolicy);

        final AdvanclyChargeInterestRule rule = existingRule
                .orElseGet(() -> AdvanclyChargeInterestRule.createNew(charge, interestBasisMode, customPeriodReapplyPolicy));
        final boolean changed = existingRule.isEmpty() || rule.interestBasisMode() != interestBasisMode
                || rule.customPeriodReapplyPolicy() != customPeriodReapplyPolicy;
        rule.update(interestBasisMode, customPeriodReapplyPolicy);
        this.chargeInterestRuleRepository.save(rule);

        if (changed) {
            changes.put(ChargesApiConstants.interestBasisModeParamName, interestBasisMode.getValue());
            changes.put(ChargesApiConstants.customPeriodReapplyPolicyParamName,
                    customPeriodReapplyPolicy == null ? null : customPeriodReapplyPolicy.getValue());
        }
        return changes;
    }

    private InterestBasisMode interestBasisMode(final JsonCommand command, final Optional<AdvanclyChargeInterestRule> existingRule) {
        if (command.hasParameterValue(ChargesApiConstants.interestBasisModeParamName)) {
            return InterestBasisMode.fromInt(command.integerValueOfParameterNamed(ChargesApiConstants.interestBasisModeParamName));
        }
        return existingRule.map(AdvanclyChargeInterestRule::interestBasisMode).orElse(null);
    }

    private CustomPeriodReapplyPolicy customPeriodReapplyPolicy(final JsonCommand command,
            final Optional<AdvanclyChargeInterestRule> existingRule, final InterestBasisMode interestBasisMode) {
        if (!interestBasisMode.isCustomPeriod()) {
            return null;
        }
        if (command.hasParameterValue(ChargesApiConstants.customPeriodReapplyPolicyParamName)) {
            return CustomPeriodReapplyPolicy
                    .fromInt(command.integerValueOfParameterNamed(ChargesApiConstants.customPeriodReapplyPolicyParamName));
        }
        return existingRule.map(AdvanclyChargeInterestRule::customPeriodReapplyPolicy).orElse(null);
    }

    private CommandProcessingResult withRuleChanges(final CommandProcessingResult result, final Long chargeId,
            final Map<String, Object> ruleChanges) {
        if (ruleChanges.isEmpty()) {
            return result;
        }
        final Map<String, Object> changes = new LinkedHashMap<>();
        if (result.getChanges() != null) {
            changes.putAll(result.getChanges());
        }
        changes.putAll(ruleChanges);
        return new CommandProcessingResultBuilder().withCommandId(result.getCommandId()).withEntityId(chargeId).with(changes).build();
    }
}
