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
import static org.apache.fineract.portfolio.interestratechart.InterestRateChartApiConstants.deleteParamName;
import static org.apache.fineract.portfolio.interestratechart.InterestRateChartApiConstants.idParamName;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import jakarta.persistence.CascadeType;
import jakarta.persistence.DiscriminatorValue;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinTable;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Transient;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.fineract.accounting.common.AccountingRuleType;
import org.apache.fineract.infrastructure.core.api.JsonCommand;
import org.apache.fineract.infrastructure.core.data.ApiParameterError;
import org.apache.fineract.infrastructure.core.data.DataValidatorBuilder;
import org.apache.fineract.infrastructure.core.exception.PlatformApiDataValidationException;
import org.apache.fineract.organisation.monetary.domain.MonetaryCurrency;
import org.apache.fineract.portfolio.charge.domain.Charge;
import org.apache.fineract.portfolio.interestratechart.InterestRateChartApiConstants;
import org.apache.fineract.portfolio.interestratechart.domain.InterestRateChart;
import org.apache.fineract.portfolio.interestratechart.service.InterestRateChartAssembler;
import org.apache.fineract.portfolio.savings.DepositsApiConstants;
import org.apache.fineract.portfolio.savings.SavingsCompoundingInterestPeriodType;
import org.apache.fineract.portfolio.savings.SavingsInterestCalculationDaysInYearType;
import org.apache.fineract.portfolio.savings.SavingsInterestCalculationType;
import org.apache.fineract.portfolio.savings.SavingsPeriodFrequencyType;
import org.apache.fineract.portfolio.savings.SavingsPostingInterestPeriodType;
import org.apache.fineract.portfolio.savings.domain.SavingsProduct;
import org.apache.fineract.portfolio.tax.domain.TaxGroup;

/**
 * The Dynamic Deposit product (deposit_type_enum = 500): a fixed-tenor deposit product whose interest rate can be
 * resolved dynamically, per account, from an {@link InterestRateChart} based on the invested amount and the account's
 * fixed tenor. See {@code DynamicDepositProductAssembler} for how accounts are created from this product, and
 * {@code DepositProductDynamicDetail} for the allow-withdrawal / dynamic-rate-enabled configuration.
 *
 * Deliberately lean for Phase 1: unlike {@code FixedDepositProduct}, this class does not compose a
 * {@code DepositProductTermAndPreClosure} - that core entity's {@code product} field is hard-typed to
 * {@code FixedDepositProduct} (with an explicit cast in its constructor), so it cannot be reused here without a further
 * core change. Preclosure-penalty configuration is out of scope for Phase 1 in any case (see the implementation plan).
 * The account's fixed tenor and current invested amount instead live on the reused, generic
 * {@code DepositAccountTermAndPreClosure} (account-level), matching business rule 1 in the plan ("the tenor remains
 * fixed during top-up/withdrawal").
 */
@Entity
@DiscriminatorValue("500")
public class DynamicDepositProduct extends SavingsProduct {

    @OneToMany(fetch = FetchType.LAZY, cascade = CascadeType.ALL)
    @JoinTable(name = "m_deposit_product_interest_rate_chart", joinColumns = @JoinColumn(name = "deposit_product_id"), inverseJoinColumns = @JoinColumn(name = "interest_rate_chart_id", unique = true))
    protected Set<InterestRateChart> charts;

    @OneToOne(mappedBy = "product", cascade = CascadeType.ALL)
    private DepositProductDynamicDetail dynamicDetail;

    @Transient
    private InterestRateChartAssembler chartAssembler;

    protected DynamicDepositProduct() {
        //
    }

    public static DynamicDepositProduct createNew(final String name, final String shortName, final String description,
            final MonetaryCurrency currency, final BigDecimal interestRate,
            final SavingsCompoundingInterestPeriodType interestCompoundingPeriodType,
            final SavingsPostingInterestPeriodType interestPostingPeriodType, final SavingsInterestCalculationType interestCalculationType,
            final SavingsInterestCalculationDaysInYearType interestCalculationDaysInYearType, final Integer lockinPeriodFrequency,
            final SavingsPeriodFrequencyType lockinPeriodFrequencyType, final AccountingRuleType accountingRuleType,
            final Set<Charge> charges, final Set<InterestRateChart> charts, final BigDecimal minBalanceForInterestCalculation,
            final boolean withHoldTax, final TaxGroup taxGroup, final boolean allowWithdrawal, final boolean dynamicRateEnabled,
            final boolean earlyWithdrawalPenaltyEnabled) {

        final BigDecimal minRequiredOpeningBalance = null;
        final boolean withdrawalFeeApplicableForTransfer = false;
        final boolean allowOverdraft = false;
        final BigDecimal overdraftLimit = null;

        final DynamicDepositProduct product = new DynamicDepositProduct(name, shortName, description, currency, interestRate,
                interestCompoundingPeriodType, interestPostingPeriodType, interestCalculationType, interestCalculationDaysInYearType,
                minRequiredOpeningBalance, lockinPeriodFrequency, lockinPeriodFrequencyType, withdrawalFeeApplicableForTransfer,
                accountingRuleType, charges, charts, allowOverdraft, overdraftLimit, minBalanceForInterestCalculation, withHoldTax,
                taxGroup);

        final DepositProductDynamicDetail dynamicDetail = DepositProductDynamicDetail.createNew(product, allowWithdrawal,
                dynamicRateEnabled, earlyWithdrawalPenaltyEnabled);
        product.dynamicDetail = dynamicDetail;

        return product;
    }

    protected DynamicDepositProduct(final String name, final String shortName, final String description, final MonetaryCurrency currency,
            final BigDecimal interestRate, final SavingsCompoundingInterestPeriodType interestCompoundingPeriodType,
            final SavingsPostingInterestPeriodType interestPostingPeriodType, final SavingsInterestCalculationType interestCalculationType,
            final SavingsInterestCalculationDaysInYearType interestCalculationDaysInYearType, final BigDecimal minRequiredOpeningBalance,
            final Integer lockinPeriodFrequency, final SavingsPeriodFrequencyType lockinPeriodFrequencyType,
            final boolean withdrawalFeeApplicableForTransfer, final AccountingRuleType accountingRuleType, final Set<Charge> charges,
            final Set<InterestRateChart> charts, final boolean allowOverdraft, final BigDecimal overdraftLimit,
            final BigDecimal minBalanceForInterestCalculation, final boolean withHoldTax, final TaxGroup taxGroup) {

        super(name, shortName, description, currency, interestRate, interestCompoundingPeriodType, interestPostingPeriodType,
                interestCalculationType, interestCalculationDaysInYearType, minRequiredOpeningBalance, lockinPeriodFrequency,
                lockinPeriodFrequencyType, withdrawalFeeApplicableForTransfer, accountingRuleType, charges, allowOverdraft, overdraftLimit,
                minBalanceForInterestCalculation, withHoldTax, taxGroup, null);

        if (charts != null) {
            this.charts = charts;
        }
    }

    public void addChart(final InterestRateChart newChart) {
        setOfCharts().add(newChart);
    }

    public Set<InterestRateChart> setOfCharts() {
        if (this.charts == null) {
            this.charts = new HashSet<>();
        }
        return this.charts;
    }

    @Override
    public InterestRateChart findChart(final Long chartId) {
        for (final InterestRateChart chart : setOfCharts()) {
            if (chart.getId().equals(chartId)) {
                return chart;
            }
        }
        return null;
    }

    @Override
    public InterestRateChart applicableChart(final LocalDate target) {
        for (final InterestRateChart chart : setOfCharts()) {
            if (chart.isApplicableChartFor(target)) {
                return chart;
            }
        }
        return null;
    }

    public DepositProductDynamicDetail dynamicDetail() {
        return this.dynamicDetail;
    }

    public boolean isAllowWithdrawal() {
        return this.dynamicDetail != null && this.dynamicDetail.isAllowWithdrawal();
    }

    public boolean isDynamicRateEnabled() {
        return this.dynamicDetail != null && this.dynamicDetail.isDynamicRateEnabled();
    }

    public boolean isEarlyWithdrawalPenaltyEnabled() {
        return this.dynamicDetail != null && this.dynamicDetail.isEarlyWithdrawalPenaltyEnabled();
    }

    @Override
    public Map<String, Object> update(final JsonCommand command) {
        final Map<String, Object> actualChanges = new LinkedHashMap<>(10);
        final List<ApiParameterError> dataValidationErrors = new ArrayList<>();
        final DataValidatorBuilder baseDataValidator = new DataValidatorBuilder(dataValidationErrors)
                .resource(DYNAMIC_DEPOSIT_PRODUCT_RESOURCE_NAME);

        actualChanges.putAll(super.update(command));

        if (this.dynamicDetail != null) {
            actualChanges.putAll(this.dynamicDetail.update(command));
        }

        if (command.hasParameter(DepositsApiConstants.chartsParamName)) {
            updateCharts(command, actualChanges, baseDataValidator);
        }

        validateDomainRules(baseDataValidator);

        throwExceptionIfValidationWarningsExist(dataValidationErrors);

        return actualChanges;
    }

    private void updateCharts(final JsonCommand command, final Map<String, Object> actualChanges,
            final DataValidatorBuilder baseDataValidator) {
        final Map<String, Object> deletedCharts = new HashMap<>();
        final Map<String, Object> chartsChanges = new HashMap<>();

        final JsonArray array = command.arrayOfParameterNamed(DepositsApiConstants.chartsParamName);
        if (array != null) {
            for (int i = 0; i < array.size(); i++) {
                final JsonObject chartElement = array.get(i).getAsJsonObject();
                final JsonCommand chartCommand = JsonCommand.fromExistingCommand(command, chartElement);
                if (chartCommand.parameterExists(idParamName)) {
                    final Long chartId = chartCommand.longValueOfParameterNamed(idParamName);
                    final InterestRateChart chart = this.findChart(chartId);
                    if (chart == null) {
                        baseDataValidator.parameter(idParamName).value(chartId).failWithCode("no.chart.associated.with.id");
                    } else if (chartCommand.parameterExists(deleteParamName)) {
                        if (removeChart(chart)) {
                            deletedCharts.put(idParamName, chartId);
                        }
                    } else {
                        chart.update(chartCommand, chartsChanges, baseDataValidator, this.setOfCharts(), this.currency().getCode());
                    }
                } else {
                    final InterestRateChart newChart = this.chartAssembler.assembleFrom(chartElement, this.currency().getCode(),
                            baseDataValidator);
                    this.addChart(newChart);
                }
            }
        }

        if (!chartsChanges.isEmpty()) {
            actualChanges.put(InterestRateChartApiConstants.chartSlabs, chartsChanges);
        }

        if (!deletedCharts.isEmpty()) {
            actualChanges.put("deletedCharts", deletedCharts);
        }
    }

    private boolean removeChart(final InterestRateChart chart) {
        return setOfCharts().remove(chart);
    }

    public void setHelpers(final InterestRateChartAssembler chartAssembler) {
        this.chartAssembler = chartAssembler;
    }

    private void validateDomainRules() {
        final List<ApiParameterError> dataValidationErrors = new ArrayList<>();
        final DataValidatorBuilder baseDataValidator = new DataValidatorBuilder(dataValidationErrors)
                .resource(DYNAMIC_DEPOSIT_PRODUCT_RESOURCE_NAME);
        validateDomainRules(baseDataValidator);
        throwExceptionIfValidationWarningsExist(dataValidationErrors);
    }

    private void validateDomainRules(final DataValidatorBuilder baseDataValidator) {
        if (this.charts == null || this.charts.isEmpty()) {
            if (this.nominalAnnualInterestRate == null || this.nominalAnnualInterestRate.compareTo(BigDecimal.ZERO) == 0) {
                baseDataValidator.reset().parameter("nominalAnnualInterestRate").value(this.nominalAnnualInterestRate)
                        .failWithCodeNoParameterAddedToErrorCode("interest.chart.or.nominal.interest.rate.required");
            }
        }
    }

    private void throwExceptionIfValidationWarningsExist(final List<ApiParameterError> dataValidationErrors) {
        if (!dataValidationErrors.isEmpty()) {
            throw new PlatformApiDataValidationException(dataValidationErrors);
        }
    }
}
