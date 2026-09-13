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
package com.advancly.fineract.portfolio.savings.data;

import java.io.Serializable;
import java.math.BigDecimal;
import java.util.Collection;
import org.apache.fineract.infrastructure.core.data.EnumOptionData;
import org.apache.fineract.organisation.monetary.data.CurrencyData;
import org.apache.fineract.portfolio.charge.data.ChargeData;
import org.apache.fineract.portfolio.interestratechart.data.InterestRateChartData;
import org.apache.fineract.portfolio.tax.data.TaxGroupData;

/**
 * Read-side representation of a Dynamic Deposit product (deposit_type_enum = 500). Mirrors the shape of
 * {@code FixedDepositProductData} for the fields this Phase-1 product actually has, plus {@code allowWithdrawal} /
 * {@code dynamicRateEnabled}.
 */
public class DynamicDepositProductData implements Serializable {

    private final Long id;
    private final String name;
    private final String shortName;
    private final String description;
    private final CurrencyData currency;
    private final BigDecimal nominalAnnualInterestRate;
    private final EnumOptionData interestCompoundingPeriodType;
    private final EnumOptionData interestPostingPeriodType;
    private final EnumOptionData interestCalculationType;
    private final EnumOptionData interestCalculationDaysInYearType;
    private final Integer lockinPeriodFrequency;
    private final EnumOptionData lockinPeriodFrequencyType;
    private final EnumOptionData accountingRule;
    private final BigDecimal minBalanceForInterestCalculation;
    private final boolean withHoldTax;
    private final Long taxGroupId;
    private final boolean allowWithdrawal;
    private final boolean dynamicRateEnabled;
    private final Collection<ChargeData> charges;
    private final Collection<InterestRateChartData> charts;

    // template-only
    private final Collection<CurrencyData> currencyOptions;
    private final Collection<EnumOptionData> interestCompoundingPeriodTypeOptions;
    private final Collection<EnumOptionData> interestPostingPeriodTypeOptions;
    private final Collection<EnumOptionData> interestCalculationTypeOptions;
    private final Collection<EnumOptionData> interestCalculationDaysInYearTypeOptions;
    private final Collection<EnumOptionData> lockinPeriodFrequencyTypeOptions;
    private final Collection<EnumOptionData> accountingRuleOptions;
    private final Collection<ChargeData> chargeOptions;
    private final Collection<TaxGroupData> taxGroupOptions;
    private final InterestRateChartData chartTemplate;

    public DynamicDepositProductData(final Long id, final String name, final String shortName, final String description,
            final CurrencyData currency, final BigDecimal nominalAnnualInterestRate, final EnumOptionData interestCompoundingPeriodType,
            final EnumOptionData interestPostingPeriodType, final EnumOptionData interestCalculationType,
            final EnumOptionData interestCalculationDaysInYearType, final Integer lockinPeriodFrequency,
            final EnumOptionData lockinPeriodFrequencyType, final EnumOptionData accountingRule,
            final BigDecimal minBalanceForInterestCalculation, final boolean withHoldTax, final Long taxGroupId,
            final boolean allowWithdrawal, final boolean dynamicRateEnabled, final Collection<ChargeData> charges,
            final Collection<InterestRateChartData> charts) {
        this(id, name, shortName, description, currency, nominalAnnualInterestRate, interestCompoundingPeriodType,
                interestPostingPeriodType, interestCalculationType, interestCalculationDaysInYearType, lockinPeriodFrequency,
                lockinPeriodFrequencyType, accountingRule, minBalanceForInterestCalculation, withHoldTax, taxGroupId, allowWithdrawal,
                dynamicRateEnabled, charges, charts, null, null, null, null, null, null, null, null, null, null);
    }

    private DynamicDepositProductData(final Long id, final String name, final String shortName, final String description,
            final CurrencyData currency, final BigDecimal nominalAnnualInterestRate, final EnumOptionData interestCompoundingPeriodType,
            final EnumOptionData interestPostingPeriodType, final EnumOptionData interestCalculationType,
            final EnumOptionData interestCalculationDaysInYearType, final Integer lockinPeriodFrequency,
            final EnumOptionData lockinPeriodFrequencyType, final EnumOptionData accountingRule,
            final BigDecimal minBalanceForInterestCalculation, final boolean withHoldTax, final Long taxGroupId,
            final boolean allowWithdrawal, final boolean dynamicRateEnabled, final Collection<ChargeData> charges,
            final Collection<InterestRateChartData> charts, final Collection<CurrencyData> currencyOptions,
            final Collection<EnumOptionData> interestCompoundingPeriodTypeOptions,
            final Collection<EnumOptionData> interestPostingPeriodTypeOptions,
            final Collection<EnumOptionData> interestCalculationTypeOptions,
            final Collection<EnumOptionData> interestCalculationDaysInYearTypeOptions,
            final Collection<EnumOptionData> lockinPeriodFrequencyTypeOptions, final Collection<EnumOptionData> accountingRuleOptions,
            final Collection<ChargeData> chargeOptions, final Collection<TaxGroupData> taxGroupOptions,
            final InterestRateChartData chartTemplate) {
        this.id = id;
        this.name = name;
        this.shortName = shortName;
        this.description = description;
        this.currency = currency;
        this.nominalAnnualInterestRate = nominalAnnualInterestRate;
        this.interestCompoundingPeriodType = interestCompoundingPeriodType;
        this.interestPostingPeriodType = interestPostingPeriodType;
        this.interestCalculationType = interestCalculationType;
        this.interestCalculationDaysInYearType = interestCalculationDaysInYearType;
        this.lockinPeriodFrequency = lockinPeriodFrequency;
        this.lockinPeriodFrequencyType = lockinPeriodFrequencyType;
        this.accountingRule = accountingRule;
        this.minBalanceForInterestCalculation = minBalanceForInterestCalculation;
        this.withHoldTax = withHoldTax;
        this.taxGroupId = taxGroupId;
        this.allowWithdrawal = allowWithdrawal;
        this.dynamicRateEnabled = dynamicRateEnabled;
        this.charges = charges;
        this.charts = charts;
        this.currencyOptions = currencyOptions;
        this.interestCompoundingPeriodTypeOptions = interestCompoundingPeriodTypeOptions;
        this.interestPostingPeriodTypeOptions = interestPostingPeriodTypeOptions;
        this.interestCalculationTypeOptions = interestCalculationTypeOptions;
        this.interestCalculationDaysInYearTypeOptions = interestCalculationDaysInYearTypeOptions;
        this.lockinPeriodFrequencyTypeOptions = lockinPeriodFrequencyTypeOptions;
        this.accountingRuleOptions = accountingRuleOptions;
        this.chargeOptions = chargeOptions;
        this.taxGroupOptions = taxGroupOptions;
        this.chartTemplate = chartTemplate;
    }

    public static DynamicDepositProductData withTemplateOptions(final DynamicDepositProductData data,
            final Collection<CurrencyData> currencyOptions, final Collection<EnumOptionData> interestCompoundingPeriodTypeOptions,
            final Collection<EnumOptionData> interestPostingPeriodTypeOptions,
            final Collection<EnumOptionData> interestCalculationTypeOptions,
            final Collection<EnumOptionData> interestCalculationDaysInYearTypeOptions,
            final Collection<EnumOptionData> lockinPeriodFrequencyTypeOptions, final Collection<EnumOptionData> accountingRuleOptions,
            final Collection<ChargeData> chargeOptions, final Collection<TaxGroupData> taxGroupOptions,
            final InterestRateChartData chartTemplate) {
        return new DynamicDepositProductData(data.id, data.name, data.shortName, data.description, data.currency,
                data.nominalAnnualInterestRate, data.interestCompoundingPeriodType, data.interestPostingPeriodType,
                data.interestCalculationType, data.interestCalculationDaysInYearType, data.lockinPeriodFrequency,
                data.lockinPeriodFrequencyType, data.accountingRule, data.minBalanceForInterestCalculation, data.withHoldTax,
                data.taxGroupId, data.allowWithdrawal, data.dynamicRateEnabled, data.charges, data.charts, currencyOptions,
                interestCompoundingPeriodTypeOptions, interestPostingPeriodTypeOptions, interestCalculationTypeOptions,
                interestCalculationDaysInYearTypeOptions, lockinPeriodFrequencyTypeOptions, accountingRuleOptions, chargeOptions,
                taxGroupOptions, chartTemplate);
    }

    public static DynamicDepositProductData withCharts(final DynamicDepositProductData data,
            final Collection<InterestRateChartData> charts) {
        return new DynamicDepositProductData(data.id, data.name, data.shortName, data.description, data.currency,
                data.nominalAnnualInterestRate, data.interestCompoundingPeriodType, data.interestPostingPeriodType,
                data.interestCalculationType, data.interestCalculationDaysInYearType, data.lockinPeriodFrequency,
                data.lockinPeriodFrequencyType, data.accountingRule, data.minBalanceForInterestCalculation, data.withHoldTax,
                data.taxGroupId, data.allowWithdrawal, data.dynamicRateEnabled, data.charges, charts);
    }

    public static DynamicDepositProductData withCharges(final DynamicDepositProductData data, final Collection<ChargeData> charges) {
        return new DynamicDepositProductData(data.id, data.name, data.shortName, data.description, data.currency,
                data.nominalAnnualInterestRate, data.interestCompoundingPeriodType, data.interestPostingPeriodType,
                data.interestCalculationType, data.interestCalculationDaysInYearType, data.lockinPeriodFrequency,
                data.lockinPeriodFrequencyType, data.accountingRule, data.minBalanceForInterestCalculation, data.withHoldTax,
                data.taxGroupId, data.allowWithdrawal, data.dynamicRateEnabled, charges, data.charts);
    }

    public Long id() {
        return this.id;
    }

    public CurrencyData currency() {
        return this.currency;
    }

    public Collection<CurrencyData> currencyOptions() {
        return this.currencyOptions;
    }

    public Collection<EnumOptionData> interestCompoundingPeriodTypeOptions() {
        return this.interestCompoundingPeriodTypeOptions;
    }

    public Collection<EnumOptionData> interestPostingPeriodTypeOptions() {
        return this.interestPostingPeriodTypeOptions;
    }

    public Collection<EnumOptionData> interestCalculationTypeOptions() {
        return this.interestCalculationTypeOptions;
    }

    public Collection<EnumOptionData> interestCalculationDaysInYearTypeOptions() {
        return this.interestCalculationDaysInYearTypeOptions;
    }

    public Collection<EnumOptionData> lockinPeriodFrequencyTypeOptions() {
        return this.lockinPeriodFrequencyTypeOptions;
    }

    public Collection<EnumOptionData> accountingRuleOptions() {
        return this.accountingRuleOptions;
    }

    public Collection<ChargeData> chargeOptions() {
        return this.chargeOptions;
    }

    public Collection<TaxGroupData> taxGroupOptions() {
        return this.taxGroupOptions;
    }

    public InterestRateChartData chartTemplate() {
        return this.chartTemplate;
    }
}
