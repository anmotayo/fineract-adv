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
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.apache.fineract.infrastructure.core.data.EnumOptionData;
import org.apache.fineract.portfolio.charge.data.ChargeData;
import org.apache.fineract.portfolio.charge.domain.ChargeTimeType;
import org.apache.fineract.portfolio.charge.service.ChargeReadPlatformService;

@RequiredArgsConstructor
public class AdvanclyChargeReadPlatformService implements ChargeReadPlatformService {

    private final ChargeReadPlatformService delegate;
    private final AdvanclyChargeInterestRuleRepository chargeInterestRuleRepository;

    @Override
    public List<ChargeData> retrieveAllCharges() {
        return enrichCharges(this.delegate.retrieveAllCharges());
    }

    @Override
    public List<ChargeData> retrieveAllChargesForCurrency(final String currencyCode) {
        return enrichCharges(this.delegate.retrieveAllChargesForCurrency(currencyCode));
    }

    @Override
    public ChargeData retrieveCharge(final Long chargeId) {
        return enrichCharge(this.delegate.retrieveCharge(chargeId));
    }

    @Override
    public ChargeData retrieveNewChargeDetails() {
        return withRuleOptions(this.delegate.retrieveNewChargeDetails());
    }

    @Override
    public List<ChargeData> retrieveAllChargesApplicableToClients() {
        return enrichCharges(this.delegate.retrieveAllChargesApplicableToClients());
    }

    @Override
    public List<ChargeData> retrieveLoanApplicableFees() {
        return enrichCharges(this.delegate.retrieveLoanApplicableFees());
    }

    @Override
    public List<ChargeData> retrieveLoanAccountApplicableCharges(final Long loanId, final ChargeTimeType[] excludeChargeTimes) {
        return enrichCharges(this.delegate.retrieveLoanAccountApplicableCharges(loanId, excludeChargeTimes));
    }

    @Override
    public List<ChargeData> retrieveLoanProductApplicableCharges(final Long loanProductId, final ChargeTimeType[] excludeChargeTimes) {
        return enrichCharges(this.delegate.retrieveLoanProductApplicableCharges(loanProductId, excludeChargeTimes));
    }

    @Override
    public List<ChargeData> retrieveLoanApplicablePenalties() {
        return enrichCharges(this.delegate.retrieveLoanApplicablePenalties());
    }

    @Override
    public List<ChargeData> retrieveLoanProductCharges(final Long loanProductId) {
        return enrichCharges(this.delegate.retrieveLoanProductCharges(loanProductId));
    }

    @Override
    public List<ChargeData> retrieveLoanProductCharges(final Long loanProductId, final ChargeTimeType chargeTime) {
        return enrichCharges(this.delegate.retrieveLoanProductCharges(loanProductId, chargeTime));
    }

    @Override
    public List<ChargeData> retrieveSavingsProductApplicableCharges(final boolean feeChargesOnly) {
        return enrichCharges(this.delegate.retrieveSavingsProductApplicableCharges(feeChargesOnly));
    }

    @Override
    public List<ChargeData> retrieveSavingsApplicablePenalties() {
        return enrichCharges(this.delegate.retrieveSavingsApplicablePenalties());
    }

    @Override
    public List<ChargeData> retrieveSavingsProductCharges(final Long savingsProductId) {
        return enrichCharges(this.delegate.retrieveSavingsProductCharges(savingsProductId));
    }

    @Override
    public List<ChargeData> retrieveSavingsAccountApplicableCharges(final Long savingsId) {
        return enrichCharges(this.delegate.retrieveSavingsAccountApplicableCharges(savingsId));
    }

    @Override
    public List<ChargeData> retrieveSharesApplicableCharges() {
        return enrichCharges(this.delegate.retrieveSharesApplicableCharges());
    }

    @Override
    public List<ChargeData> retrieveShareProductCharges(final Long shareProductId) {
        return enrichCharges(this.delegate.retrieveShareProductCharges(shareProductId));
    }

    private List<ChargeData> enrichCharges(final List<ChargeData> charges) {
        if (charges == null || charges.isEmpty()) {
            return charges;
        }

        final List<Long> chargeIds = charges.stream().map(ChargeData::getId).toList();
        final Map<Long, AdvanclyChargeInterestRule> rulesByChargeId = this.chargeInterestRuleRepository.findByChargeIdIn(chargeIds).stream()
                .collect(Collectors.toMap(AdvanclyChargeInterestRule::chargeId, Function.identity()));

        return charges.stream().map(charge -> enrichCharge(charge, Optional.ofNullable(rulesByChargeId.get(charge.getId())))).toList();
    }

    private ChargeData enrichCharge(final ChargeData charge) {
        if (charge == null) {
            return null;
        }
        return enrichCharge(charge, this.chargeInterestRuleRepository.findByChargeId(charge.getId()));
    }

    private ChargeData enrichCharge(final ChargeData charge, final Optional<AdvanclyChargeInterestRule> rule) {
        final var builder = charge.toBuilder();
        rule.ifPresentOrElse(
                chargeInterestRule -> builder.interestBasisMode(toEnumOptionData(chargeInterestRule.interestBasisMode()))
                        .customPeriodReapplyPolicy(toEnumOptionData(chargeInterestRule.customPeriodReapplyPolicy())),
                () -> builder.interestBasisMode(null).customPeriodReapplyPolicy(null));
        return builder.build();
    }

    private ChargeData withRuleOptions(final ChargeData charge) {
        return charge.toBuilder().interestBasisModeOptions(interestBasisModeOptions())
                .customPeriodReapplyPolicyOptions(customPeriodReapplyPolicyOptions()).build();
    }

    private static List<EnumOptionData> interestBasisModeOptions() {
        return List.of(toEnumOptionData(InterestBasisMode.CUMULATIVE), toEnumOptionData(InterestBasisMode.CUSTOM_PERIOD));
    }

    private static List<EnumOptionData> customPeriodReapplyPolicyOptions() {
        return List.of(toEnumOptionData(CustomPeriodReapplyPolicy.ONCE_PER_SELECTED_PERIOD),
                toEnumOptionData(CustomPeriodReapplyPolicy.UNTIL_SELECTED_PERIOD_INTEREST_EXHAUSTED));
    }

    private static EnumOptionData toEnumOptionData(final InterestBasisMode mode) {
        if (mode == null) {
            return null;
        }
        return switch (mode) {
            case CUMULATIVE -> new EnumOptionData((long) mode.getValue(), "advancly.interestBasisMode.cumulative", "Cumulative");
            case CUSTOM_PERIOD -> new EnumOptionData((long) mode.getValue(), "advancly.interestBasisMode.customPeriod", "Custom Period");
        };
    }

    private static EnumOptionData toEnumOptionData(final CustomPeriodReapplyPolicy policy) {
        if (policy == null) {
            return null;
        }
        return switch (policy) {
            case ONCE_PER_SELECTED_PERIOD -> new EnumOptionData((long) policy.getValue(),
                    "advancly.customPeriodReapplyPolicy.oncePerSelectedPeriod", "Once Per Selected Period");
            case UNTIL_SELECTED_PERIOD_INTEREST_EXHAUSTED -> new EnumOptionData((long) policy.getValue(),
                    "advancly.customPeriodReapplyPolicy.untilSelectedPeriodInterestExhausted", "Until Selected Period Interest Exhausted");
        };
    }
}
