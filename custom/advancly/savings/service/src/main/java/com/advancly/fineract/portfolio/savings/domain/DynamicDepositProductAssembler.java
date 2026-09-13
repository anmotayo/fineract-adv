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
package com.advancly.fineract.portfolio.savings.domain;

import static com.advancly.fineract.portfolio.savings.DynamicDepositApiConstants.DYNAMIC_DEPOSIT_PRODUCT_RESOURCE_NAME;
import static com.advancly.fineract.portfolio.savings.DynamicDepositApiConstants.allowWithdrawalParamName;
import static com.advancly.fineract.portfolio.savings.DynamicDepositApiConstants.dynamicRateEnabledParamName;
import static com.advancly.fineract.portfolio.savings.DynamicDepositApiConstants.earlyWithdrawalPenaltyEnabledParamName;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.apache.fineract.accounting.common.AccountingRuleType;
import org.apache.fineract.infrastructure.core.api.JsonCommand;
import org.apache.fineract.infrastructure.core.data.ApiParameterError;
import org.apache.fineract.infrastructure.core.data.DataValidatorBuilder;
import org.apache.fineract.infrastructure.core.exception.PlatformApiDataValidationException;
import org.apache.fineract.organisation.monetary.domain.MonetaryCurrency;
import org.apache.fineract.portfolio.charge.domain.Charge;
import org.apache.fineract.portfolio.charge.domain.ChargeRepositoryWrapper;
import org.apache.fineract.portfolio.interestratechart.domain.InterestRateChart;
import org.apache.fineract.portfolio.interestratechart.service.InterestRateChartAssembler;
import org.apache.fineract.portfolio.savings.DepositAccountType;
import org.apache.fineract.portfolio.savings.DepositsApiConstants;
import org.apache.fineract.portfolio.savings.SavingsApiConstants;
import org.apache.fineract.portfolio.savings.SavingsCompoundingInterestPeriodType;
import org.apache.fineract.portfolio.savings.SavingsInterestCalculationDaysInYearType;
import org.apache.fineract.portfolio.savings.SavingsInterestCalculationType;
import org.apache.fineract.portfolio.savings.SavingsPeriodFrequencyType;
import org.apache.fineract.portfolio.savings.SavingsPostingInterestPeriodType;
import org.apache.fineract.portfolio.savings.domain.SavingsProductBaseAssembler;
import org.apache.fineract.portfolio.tax.domain.TaxGroup;
import org.apache.fineract.portfolio.tax.domain.TaxGroupRepositoryWrapper;
import org.springframework.stereotype.Component;

/**
 * Builds a {@link DynamicDepositProduct} from a create/update {@link JsonCommand}. Structurally mirrors
 * {@code DepositProductAssembler#assembleFixedDepositProduct}, scoped to this product's Phase-1 fields.
 */
@Component
public class DynamicDepositProductAssembler extends SavingsProductBaseAssembler {

    private final InterestRateChartAssembler chartAssembler;

    public DynamicDepositProductAssembler(final ChargeRepositoryWrapper chargeRepository,
            final TaxGroupRepositoryWrapper taxGroupRepository, final InterestRateChartAssembler chartAssembler) {
        super(chargeRepository, taxGroupRepository);
        this.chartAssembler = chartAssembler;
    }

    public DynamicDepositProduct assembleDynamicDepositProduct(final JsonCommand command) {

        final String name = command.stringValueOfParameterNamed(SavingsApiConstants.nameParamName);
        final String shortName = command.stringValueOfParameterNamed(SavingsApiConstants.shortNameParamName);
        final String description = command.stringValueOfParameterNamed(SavingsApiConstants.descriptionParamName);

        final String currencyCode = command.stringValueOfParameterNamed(SavingsApiConstants.currencyCodeParamName);
        final Integer digitsAfterDecimal = command.integerValueOfParameterNamed(SavingsApiConstants.digitsAfterDecimalParamName);
        final Integer inMultiplesOf = command.integerValueOfParameterNamed(SavingsApiConstants.inMultiplesOfParamName);
        final MonetaryCurrency currency = new MonetaryCurrency(currencyCode, digitsAfterDecimal, inMultiplesOf);

        BigDecimal interestRate = command.bigDecimalValueOfParameterNamed(SavingsApiConstants.nominalAnnualInterestRateParamName);

        final SavingsCompoundingInterestPeriodType interestCompoundingPeriodType = SavingsCompoundingInterestPeriodType
                .fromInt(command.integerValueOfParameterNamed(SavingsApiConstants.interestCompoundingPeriodTypeParamName));
        final SavingsPostingInterestPeriodType interestPostingPeriodType = SavingsPostingInterestPeriodType
                .fromInt(command.integerValueOfParameterNamed(SavingsApiConstants.interestPostingPeriodTypeParamName));
        final SavingsInterestCalculationType interestCalculationType = SavingsInterestCalculationType
                .fromInt(command.integerValueOfParameterNamed(SavingsApiConstants.interestCalculationTypeParamName));
        final SavingsInterestCalculationDaysInYearType interestCalculationDaysInYearType = SavingsInterestCalculationDaysInYearType
                .fromInt(command.integerValueOfParameterNamed(SavingsApiConstants.interestCalculationDaysInYearTypeParamName));

        final Integer lockinPeriodFrequency = command
                .integerValueOfParameterNamedDefaultToNullIfZero(SavingsApiConstants.lockinPeriodFrequencyParamName);
        SavingsPeriodFrequencyType lockinPeriodFrequencyType = null;
        final Integer lockinPeriodFrequencyTypeValue = command
                .integerValueOfParameterNamed(SavingsApiConstants.lockinPeriodFrequencyTypeParamName);
        if (lockinPeriodFrequencyTypeValue != null) {
            lockinPeriodFrequencyType = SavingsPeriodFrequencyType.fromInt(lockinPeriodFrequencyTypeValue);
        }

        final BigDecimal minBalanceForInterestCalculation = command
                .bigDecimalValueOfParameterNamedDefaultToNullIfZero(SavingsApiConstants.minBalanceForInterestCalculationParamName);

        final AccountingRuleType accountingRuleType = AccountingRuleType
                .fromInt(command.integerValueOfParameterNamed(DepositsApiConstants.accountingRuleParamName));

        final Set<Charge> charges = assembleListOfSavingsProductCharges(command, currencyCode, SavingsApiConstants.chargesParamName,
                DepositAccountType.DYNAMIC_DEPOSIT);

        final List<ApiParameterError> dataValidationErrors = new ArrayList<>();
        final DataValidatorBuilder baseDataValidator = new DataValidatorBuilder(dataValidationErrors)
                .resource(DYNAMIC_DEPOSIT_PRODUCT_RESOURCE_NAME);
        final Set<InterestRateChart> charts = assembleListOfCharts(command, currency.getCode(), baseDataValidator);
        if (!dataValidationErrors.isEmpty()) {
            throw new PlatformApiDataValidationException(dataValidationErrors);
        }

        if (interestRate == null) {
            interestRate = BigDecimal.ZERO;
        }

        final boolean withHoldTax = command.booleanPrimitiveValueOfParameterNamed(SavingsApiConstants.withHoldTaxParamName);
        final TaxGroup taxGroup = assembleTaxGroup(command);

        final Boolean allowWithdrawalValue = command.booleanObjectValueOfParameterNamed(allowWithdrawalParamName);
        final boolean allowWithdrawal = allowWithdrawalValue == null || allowWithdrawalValue;
        final Boolean dynamicRateEnabledValue = command.booleanObjectValueOfParameterNamed(dynamicRateEnabledParamName);
        final boolean dynamicRateEnabled = dynamicRateEnabledValue != null && dynamicRateEnabledValue;
        // Defaults to false: an existing product that never sends the flag keeps the pre-Phase-4 behaviour of never
        // levying an early-withdrawal penalty.
        final Boolean earlyWithdrawalPenaltyEnabledValue = command
                .booleanObjectValueOfParameterNamed(earlyWithdrawalPenaltyEnabledParamName);
        final boolean earlyWithdrawalPenaltyEnabled = earlyWithdrawalPenaltyEnabledValue != null && earlyWithdrawalPenaltyEnabledValue;

        return DynamicDepositProduct.createNew(name, shortName, description, currency, interestRate, interestCompoundingPeriodType,
                interestPostingPeriodType, interestCalculationType, interestCalculationDaysInYearType, lockinPeriodFrequency,
                lockinPeriodFrequencyType, accountingRuleType, charges, charts, minBalanceForInterestCalculation, withHoldTax, taxGroup,
                allowWithdrawal, dynamicRateEnabled, earlyWithdrawalPenaltyEnabled);
    }

    private Set<InterestRateChart> assembleListOfCharts(final JsonCommand command, final String currencyCode,
            final DataValidatorBuilder baseDataValidator) {
        final Set<InterestRateChart> charts = new HashSet<>();
        if (command.parameterExists(DepositsApiConstants.chartsParamName)) {
            final JsonArray chartsArray = command.arrayOfParameterNamed(DepositsApiConstants.chartsParamName);
            if (chartsArray != null) {
                for (int i = 0; i < chartsArray.size(); i++) {
                    final JsonObject interestRateChartElement = chartsArray.get(i).getAsJsonObject();
                    final InterestRateChart chart = this.chartAssembler.assembleFrom(interestRateChartElement, currencyCode,
                            baseDataValidator);
                    charts.add(chart);
                }
            }
        }
        return charts;
    }
}
