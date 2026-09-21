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
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.apache.fineract.infrastructure.core.exception.GeneralPlatformDomainRuleException;
import org.apache.fineract.portfolio.charge.domain.Charge;
import org.apache.fineract.portfolio.charge.domain.ChargeCalculationType;
import org.apache.fineract.portfolio.charge.domain.ChargeTimeType;
import org.apache.fineract.portfolio.savings.SavingsPostingInterestPeriodType;
import org.apache.fineract.portfolio.savings.domain.SavingsAccount;
import org.apache.fineract.portfolio.savings.domain.SavingsAccountCharge;
import org.apache.fineract.portfolio.savings.domain.SavingsProduct;
import org.springframework.stereotype.Component;

@Component
public class AdvanclyChargeInterestRuleValidator {

    private final AdvanclyChargeInterestRuleRepository chargeInterestRuleRepository;

    public AdvanclyChargeInterestRuleValidator(final AdvanclyChargeInterestRuleRepository chargeInterestRuleRepository) {
        this.chargeInterestRuleRepository = chargeInterestRuleRepository;
    }

    public void validateRule(final Charge charge, final InterestBasisMode interestBasisMode,
            final CustomPeriodReapplyPolicy customPeriodReapplyPolicy) {
        if (charge == null) {
            throw new GeneralPlatformDomainRuleException("error.msg.advancly.charge.interest.rule.charge.required",
                    "An early-withdrawal interest rule must reference a charge.");
        }
        if (interestBasisMode == null) {
            throw new GeneralPlatformDomainRuleException("error.msg.advancly.charge.interest.rule.mode.required",
                    "An early-withdrawal interest rule must specify an interest basis mode.");
        }
        if (!charge.isSavingsCharge()) {
            throw new GeneralPlatformDomainRuleException("error.msg.advancly.charge.interest.rule.charge.must.apply.to.savings",
                    "An early-withdrawal interest charge must apply to savings.", charge.getId());
        }
        if (!charge.isActive() || charge.isDeleted()) {
            throw new GeneralPlatformDomainRuleException("error.msg.advancly.charge.interest.rule.charge.must.be.active",
                    "An early-withdrawal interest charge must be active.", charge.getId());
        }
        if (!charge.isPenalty()) {
            throw new GeneralPlatformDomainRuleException("error.msg.advancly.charge.interest.rule.charge.must.be.penalty",
                    "An early-withdrawal interest charge must be marked as a penalty.", charge.getId());
        }
        final ChargeCalculationType chargeCalculationType = ChargeCalculationType.fromInt(charge.getChargeCalculation());
        if (chargeCalculationType == null || !chargeCalculationType.isPercentageOfInterest()) {
            throw new GeneralPlatformDomainRuleException("error.msg.advancly.charge.interest.rule.charge.must.be.percent.of.interest",
                    "An early-withdrawal interest charge must use percent-of-interest calculation.", charge.getId());
        }
        final ChargeTimeType chargeTimeType = ChargeTimeType.fromInt(charge.getChargeTimeType());
        if (chargeTimeType == null || !chargeTimeType.isWithdrawalFee()) {
            throw new GeneralPlatformDomainRuleException("error.msg.advancly.charge.interest.rule.charge.must.be.withdrawal.related",
                    "An early-withdrawal interest charge must be withdrawal-related.", charge.getId());
        }
        if (interestBasisMode.isCustomPeriod() && customPeriodReapplyPolicy == null) {
            throw new GeneralPlatformDomainRuleException("error.msg.advancly.charge.interest.rule.custom.period.policy.required",
                    "A custom-period early-withdrawal interest charge must specify a reapply policy.", charge.getId());
        }
    }

    public void validateProductHasAtMostOneInterestCharge(final SavingsProduct product) {
        final Map<Long, Charge> chargesById = productChargesById(product);
        final List<AdvanclyChargeInterestRule> rules = findRules(chargesById.keySet());
        validateAtMostOneInterestCharge(product.getId(), rules);
        validateRuleDefinitions(rules);
        validateDailyPostingForCustomPeriod(product.getId(), product.interestPostingPeriodType(), rules);
    }

    public void validateProductHasAtMostOneInterestCharge(final Long savingsProductId, final Collection<Long> chargeIds) {
        final List<AdvanclyChargeInterestRule> rules = findRules(chargeIds);
        validateAtMostOneInterestCharge(savingsProductId, rules);
        validateRuleDefinitions(rules);
    }

    public Charge resolveSingleInterestCharge(final SavingsProduct product) {
        final Map<Long, Charge> chargesById = productChargesById(product);
        final List<AdvanclyChargeInterestRule> rules = findRules(chargesById.keySet());
        validateAtMostOneInterestCharge(product == null ? null : product.getId(), rules);
        validateRuleDefinitions(rules);
        if (rules.size() != 1) {
            return null;
        }
        return chargesById.get(rules.get(0).chargeId());
    }

    public void validateAccountUsesDailyPostingForCustomPeriodInterestCharge(final Long savingsProductId,
            final Collection<SavingsAccountCharge> accountCharges, final SavingsPostingInterestPeriodType interestPostingPeriodType) {
        final Set<Long> chargeIds = accountCharges == null ? Set.of()
                : accountCharges.stream().map(SavingsAccountCharge::getCharge).filter(charge -> charge != null).map(Charge::getId)
                        .collect(Collectors.toSet());
        final List<AdvanclyChargeInterestRule> rules = findAccountRules(savingsProductId, chargeIds);
        validateAtMostOneInterestCharge(savingsProductId, rules);
        validateRuleDefinitions(rules);
        validateDailyPostingForCustomPeriod(savingsProductId, interestPostingPeriodType, rules);
    }

    public void validateAccountUsesDailyPostingForCustomPeriodInterestCharge(final SavingsAccount account) {
        if (account == null) {
            return;
        }
        validateAccountUsesDailyPostingForCustomPeriodInterestCharge(account.productId(), account.charges(),
                SavingsPostingInterestPeriodType.fromInt(account.getInterestPostingPeriodType()));
    }

    private List<AdvanclyChargeInterestRule> findRules(final Collection<Long> chargeIds) {
        if (chargeIds == null || chargeIds.isEmpty()) {
            return List.of();
        }
        return this.chargeInterestRuleRepository.findByChargeIdIn(chargeIds);
    }

    private Map<Long, Charge> productChargesById(final SavingsProduct product) {
        if (product == null || product.charges() == null) {
            return Map.of();
        }
        return product.charges().stream().filter(charge -> charge != null && charge.getId() != null).collect(
                Collectors.toMap(Charge::getId, charge -> charge, (first, second) -> first, LinkedHashMap::new));
    }

    private List<AdvanclyChargeInterestRule> findAccountRules(final Long savingsProductId, final Collection<Long> accountChargeIds) {
        final Map<Long, AdvanclyChargeInterestRule> rulesByChargeId = new LinkedHashMap<>();
        for (final AdvanclyChargeInterestRule rule : findRules(accountChargeIds)) {
            rulesByChargeId.put(rule.chargeId(), rule);
        }
        for (final AdvanclyChargeInterestRule rule : this.chargeInterestRuleRepository.findBySavingsProductId(savingsProductId)) {
            rulesByChargeId.putIfAbsent(rule.chargeId(), rule);
        }
        return List.copyOf(rulesByChargeId.values());
    }

    private void validateRuleDefinitions(final List<AdvanclyChargeInterestRule> rules) {
        for (final AdvanclyChargeInterestRule rule : rules) {
            validateRule(rule.charge(), rule.interestBasisMode(), rule.customPeriodReapplyPolicy());
        }
    }

    private void validateAtMostOneInterestCharge(final Long savingsProductId, final List<AdvanclyChargeInterestRule> rules) {
        if (rules.size() > 1) {
            throw new GeneralPlatformDomainRuleException(
                    "error.msg.advancly.savings.product.only.one.early.withdrawal.interest.charge.allowed",
                    "Only one early-withdrawal interest charge can be mapped to a savings product.", savingsProductId);
        }
    }

    private void validateDailyPostingForCustomPeriod(final Long savingsProductId,
            final SavingsPostingInterestPeriodType interestPostingPeriodType, final List<AdvanclyChargeInterestRule> rules) {
        if (rules.stream().anyMatch(AdvanclyChargeInterestRule::isCustomPeriod)
                && interestPostingPeriodType != SavingsPostingInterestPeriodType.DAILY) {
            throw new GeneralPlatformDomainRuleException(
                    "error.msg.advancly.savings.product.custom.period.early.withdrawal.interest.charge.requires.daily.posting",
                    "A custom-period early-withdrawal interest charge requires daily interest posting.", savingsProductId);
        }
    }
}
