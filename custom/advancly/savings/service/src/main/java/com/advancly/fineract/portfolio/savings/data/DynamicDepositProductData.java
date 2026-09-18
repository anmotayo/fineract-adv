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
import java.util.List;
import java.util.Map;
import org.apache.fineract.accounting.common.AccountingRuleType;
import org.apache.fineract.accounting.glaccount.data.GLAccountData;
import org.apache.fineract.accounting.producttoaccountmapping.data.ChargeToGLAccountMapper;
import org.apache.fineract.accounting.producttoaccountmapping.data.PaymentTypeToGLAccountMapper;
import org.apache.fineract.infrastructure.core.data.EnumOptionData;
import org.apache.fineract.organisation.monetary.data.CurrencyData;
import org.apache.fineract.portfolio.charge.data.ChargeData;
import org.apache.fineract.portfolio.interestratechart.data.InterestRateChartData;
import org.apache.fineract.portfolio.paymenttype.data.PaymentTypeData;
import org.apache.fineract.portfolio.savings.data.DepositProductData;
import org.apache.fineract.portfolio.tax.data.TaxGroupData;

/**
 * Read-side representation of a Dynamic Deposit product (deposit_type_enum = 500). Mirrors the shape of
 * {@code FixedDepositProductData} for the fields this product has, plus {@code allowWithdrawal} /
 * {@code dynamicRateEnabled}. No nominal interest rate is modeled - the rate always comes from the interest rate chart.
 * Min/max deposit term, min/default/max deposit amount and the withholding-tax posting type are read from the same
 * {@code m_deposit_product_term_and_preclosure} table FD/RD use.
 */
public class DynamicDepositProductData implements Serializable {

    private final Long id;
    private final String name;
    private final String shortName;
    private final String description;
    private final CurrencyData currency;
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
    // The tax GROUP's name has to come from a JOIN in the read-platform-service SQL - unlike activeChart, it can't be
    // derived from anything else already on this object, so it's threaded through as its own constructor parameter
    // (mirroring FixedDepositProductData's `taxGroup` field) rather than computed. Without it, the view screen has
    // nothing but the raw id to display.
    private final TaxGroupData taxGroup;
    private final boolean allowWithdrawal;
    private final boolean dynamicRateEnabled;
    private final boolean earlyWithdrawalPenaltyEnabled;
    private final Long earlyWithdrawalChargeId;
    private final Integer earlyWithdrawalChargeMode;
    private final Integer minDepositTerm;
    private final Integer maxDepositTerm;
    private final EnumOptionData minDepositTermType;
    private final EnumOptionData maxDepositTermType;
    private final BigDecimal minDepositAmount;
    private final BigDecimal depositAmount;
    private final BigDecimal maxDepositAmount;
    private final EnumOptionData withHoldTaxPostingType;
    private final Collection<ChargeData> charges;
    private final Collection<InterestRateChartData> charts;
    // Derived from `charts` (see DepositProductData.activeChart(...)) - the chart whose date range is currently
    // effective. FD/RD's view and edit-chart-step frontend code both key off this field name (via
    // DepositProductData), not `charts`; without it here, Dynamic Deposit's chart never renders on view and the
    // edit-chart-step never pre-populates from the existing chart, so every edit looks like adding a brand new one.
    private final InterestRateChartData activeChart;
    private final Map<String, Object> accountingMappings;
    private final Collection<PaymentTypeToGLAccountMapper> paymentChannelToFundSourceMappings;
    private final Collection<ChargeToGLAccountMapper> feeToIncomeAccountMappings;
    private final Collection<ChargeToGLAccountMapper> penaltyToIncomeAccountMappings;

    // template-only
    private final Collection<CurrencyData> currencyOptions;
    private final Collection<EnumOptionData> interestCompoundingPeriodTypeOptions;
    private final Collection<EnumOptionData> interestPostingPeriodTypeOptions;
    private final Collection<EnumOptionData> interestCalculationTypeOptions;
    private final Collection<EnumOptionData> interestCalculationDaysInYearTypeOptions;
    private final Collection<EnumOptionData> lockinPeriodFrequencyTypeOptions;
    private final Collection<EnumOptionData> accountingRuleOptions;
    private final Collection<ChargeData> chargeOptions;
    private final Collection<ChargeData> penaltyOptions;
    private final Collection<PaymentTypeData> paymentTypeOptions;
    private final Map<String, List<GLAccountData>> accountingMappingOptions;
    private final Collection<TaxGroupData> taxGroupOptions;
    private final InterestRateChartData chartTemplate;
    private final Collection<EnumOptionData> depositTermTypeOptions;
    private final Collection<EnumOptionData> withHoldTaxPostingTypeOptions;

    public DynamicDepositProductData(final Long id, final String name, final String shortName, final String description,
            final CurrencyData currency, final EnumOptionData interestCompoundingPeriodType, final EnumOptionData interestPostingPeriodType,
            final EnumOptionData interestCalculationType, final EnumOptionData interestCalculationDaysInYearType,
            final Integer lockinPeriodFrequency, final EnumOptionData lockinPeriodFrequencyType, final EnumOptionData accountingRule,
            final BigDecimal minBalanceForInterestCalculation, final boolean withHoldTax, final Long taxGroupId,
            final TaxGroupData taxGroup, final boolean allowWithdrawal, final boolean dynamicRateEnabled,
            final boolean earlyWithdrawalPenaltyEnabled, final Long earlyWithdrawalChargeId, final Integer earlyWithdrawalChargeMode,
            final Integer minDepositTerm, final Integer maxDepositTerm, final EnumOptionData minDepositTermType,
            final EnumOptionData maxDepositTermType, final BigDecimal minDepositAmount, final BigDecimal depositAmount,
            final BigDecimal maxDepositAmount, final EnumOptionData withHoldTaxPostingType, final Collection<ChargeData> charges,
            final Collection<InterestRateChartData> charts) {
        this(id, name, shortName, description, currency, interestCompoundingPeriodType, interestPostingPeriodType, interestCalculationType,
                interestCalculationDaysInYearType, lockinPeriodFrequency, lockinPeriodFrequencyType, accountingRule,
                minBalanceForInterestCalculation, withHoldTax, taxGroupId, taxGroup, allowWithdrawal, dynamicRateEnabled,
                earlyWithdrawalPenaltyEnabled, earlyWithdrawalChargeId, earlyWithdrawalChargeMode, minDepositTerm, maxDepositTerm,
                minDepositTermType, maxDepositTermType, minDepositAmount, depositAmount, maxDepositAmount, withHoldTaxPostingType, charges,
                charts,
                // accountingMappings, paymentChannelToFundSourceMappings, feeToIncomeAccountMappings,
                // penaltyToIncomeAccountMappings
                null, null, null, null,
                // currencyOptions, interestCompoundingPeriodTypeOptions, interestPostingPeriodTypeOptions,
                // interestCalculationTypeOptions, interestCalculationDaysInYearTypeOptions,
                // lockinPeriodFrequencyTypeOptions,
                // accountingRuleOptions, chargeOptions, penaltyOptions, paymentTypeOptions, accountingMappingOptions,
                // taxGroupOptions,
                // chartTemplate, depositTermTypeOptions, withHoldTaxPostingTypeOptions
                null, null, null, null, null, null, null, null, null, null, null, null, null, null, null);
    }

    private DynamicDepositProductData(final Long id, final String name, final String shortName, final String description,
            final CurrencyData currency, final EnumOptionData interestCompoundingPeriodType, final EnumOptionData interestPostingPeriodType,
            final EnumOptionData interestCalculationType, final EnumOptionData interestCalculationDaysInYearType,
            final Integer lockinPeriodFrequency, final EnumOptionData lockinPeriodFrequencyType, final EnumOptionData accountingRule,
            final BigDecimal minBalanceForInterestCalculation, final boolean withHoldTax, final Long taxGroupId,
            final TaxGroupData taxGroup, final boolean allowWithdrawal, final boolean dynamicRateEnabled,
            final boolean earlyWithdrawalPenaltyEnabled, final Long earlyWithdrawalChargeId, final Integer earlyWithdrawalChargeMode,
            final Integer minDepositTerm, final Integer maxDepositTerm, final EnumOptionData minDepositTermType,
            final EnumOptionData maxDepositTermType, final BigDecimal minDepositAmount, final BigDecimal depositAmount,
            final BigDecimal maxDepositAmount, final EnumOptionData withHoldTaxPostingType, final Collection<ChargeData> charges,
            final Collection<InterestRateChartData> charts, final Map<String, Object> accountingMappings,
            final Collection<PaymentTypeToGLAccountMapper> paymentChannelToFundSourceMappings,
            final Collection<ChargeToGLAccountMapper> feeToIncomeAccountMappings,
            final Collection<ChargeToGLAccountMapper> penaltyToIncomeAccountMappings, final Collection<CurrencyData> currencyOptions,
            final Collection<EnumOptionData> interestCompoundingPeriodTypeOptions,
            final Collection<EnumOptionData> interestPostingPeriodTypeOptions,
            final Collection<EnumOptionData> interestCalculationTypeOptions,
            final Collection<EnumOptionData> interestCalculationDaysInYearTypeOptions,
            final Collection<EnumOptionData> lockinPeriodFrequencyTypeOptions, final Collection<EnumOptionData> accountingRuleOptions,
            final Collection<ChargeData> chargeOptions, final Collection<ChargeData> penaltyOptions,
            final Collection<PaymentTypeData> paymentTypeOptions, final Map<String, List<GLAccountData>> accountingMappingOptions,
            final Collection<TaxGroupData> taxGroupOptions, final InterestRateChartData chartTemplate,
            final Collection<EnumOptionData> depositTermTypeOptions, final Collection<EnumOptionData> withHoldTaxPostingTypeOptions) {
        this.id = id;
        this.name = name;
        this.shortName = shortName;
        this.description = description;
        this.currency = currency;
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
        this.taxGroup = taxGroup;
        this.allowWithdrawal = allowWithdrawal;
        this.dynamicRateEnabled = dynamicRateEnabled;
        this.earlyWithdrawalPenaltyEnabled = earlyWithdrawalPenaltyEnabled;
        this.earlyWithdrawalChargeId = earlyWithdrawalChargeId;
        this.earlyWithdrawalChargeMode = earlyWithdrawalChargeMode;
        this.minDepositTerm = minDepositTerm;
        this.maxDepositTerm = maxDepositTerm;
        this.minDepositTermType = minDepositTermType;
        this.maxDepositTermType = maxDepositTermType;
        this.minDepositAmount = minDepositAmount;
        this.depositAmount = depositAmount;
        this.maxDepositAmount = maxDepositAmount;
        this.withHoldTaxPostingType = withHoldTaxPostingType;
        this.charges = charges;
        this.charts = charts;
        this.activeChart = DepositProductData.activeChart(charts);
        this.accountingMappings = accountingMappings;
        this.paymentChannelToFundSourceMappings = paymentChannelToFundSourceMappings;
        this.feeToIncomeAccountMappings = feeToIncomeAccountMappings;
        this.penaltyToIncomeAccountMappings = penaltyToIncomeAccountMappings;
        this.currencyOptions = currencyOptions;
        this.interestCompoundingPeriodTypeOptions = interestCompoundingPeriodTypeOptions;
        this.interestPostingPeriodTypeOptions = interestPostingPeriodTypeOptions;
        this.interestCalculationTypeOptions = interestCalculationTypeOptions;
        this.interestCalculationDaysInYearTypeOptions = interestCalculationDaysInYearTypeOptions;
        this.lockinPeriodFrequencyTypeOptions = lockinPeriodFrequencyTypeOptions;
        this.accountingRuleOptions = accountingRuleOptions;
        this.chargeOptions = chargeOptions;
        this.penaltyOptions = penaltyOptions;
        this.paymentTypeOptions = paymentTypeOptions;
        this.accountingMappingOptions = accountingMappingOptions;
        this.taxGroupOptions = taxGroupOptions;
        this.chartTemplate = chartTemplate;
        this.depositTermTypeOptions = depositTermTypeOptions;
        this.withHoldTaxPostingTypeOptions = withHoldTaxPostingTypeOptions;
    }

    public static DynamicDepositProductData withTemplateOptions(final DynamicDepositProductData data,
            final Collection<CurrencyData> currencyOptions, final Collection<EnumOptionData> interestCompoundingPeriodTypeOptions,
            final Collection<EnumOptionData> interestPostingPeriodTypeOptions,
            final Collection<EnumOptionData> interestCalculationTypeOptions,
            final Collection<EnumOptionData> interestCalculationDaysInYearTypeOptions,
            final Collection<EnumOptionData> lockinPeriodFrequencyTypeOptions, final Collection<EnumOptionData> accountingRuleOptions,
            final Collection<ChargeData> chargeOptions, final Collection<ChargeData> penaltyOptions,
            final Collection<PaymentTypeData> paymentTypeOptions, final Map<String, List<GLAccountData>> accountingMappingOptions,
            final Collection<TaxGroupData> taxGroupOptions, final InterestRateChartData chartTemplate,
            final Collection<EnumOptionData> depositTermTypeOptions, final Collection<EnumOptionData> withHoldTaxPostingTypeOptions) {
        return new DynamicDepositProductData(data.id, data.name, data.shortName, data.description, data.currency,
                data.interestCompoundingPeriodType, data.interestPostingPeriodType, data.interestCalculationType,
                data.interestCalculationDaysInYearType, data.lockinPeriodFrequency, data.lockinPeriodFrequencyType, data.accountingRule,
                data.minBalanceForInterestCalculation, data.withHoldTax, data.taxGroupId, data.taxGroup, data.allowWithdrawal,
                data.dynamicRateEnabled, data.earlyWithdrawalPenaltyEnabled, data.earlyWithdrawalChargeId, data.earlyWithdrawalChargeMode,
                data.minDepositTerm, data.maxDepositTerm, data.minDepositTermType, data.maxDepositTermType, data.minDepositAmount,
                data.depositAmount, data.maxDepositAmount, data.withHoldTaxPostingType, data.charges, data.charts, data.accountingMappings,
                data.paymentChannelToFundSourceMappings, data.feeToIncomeAccountMappings, data.penaltyToIncomeAccountMappings,
                currencyOptions, interestCompoundingPeriodTypeOptions, interestPostingPeriodTypeOptions, interestCalculationTypeOptions,
                interestCalculationDaysInYearTypeOptions, lockinPeriodFrequencyTypeOptions, accountingRuleOptions, chargeOptions,
                penaltyOptions, paymentTypeOptions, accountingMappingOptions, taxGroupOptions, chartTemplate, depositTermTypeOptions,
                withHoldTaxPostingTypeOptions);
    }

    public static DynamicDepositProductData withAccountingDetails(final DynamicDepositProductData data,
            final Map<String, Object> accountingMappings, final Collection<PaymentTypeToGLAccountMapper> paymentChannelToFundSourceMappings,
            final Collection<ChargeToGLAccountMapper> feeToIncomeAccountMappings,
            final Collection<ChargeToGLAccountMapper> penaltyToIncomeAccountMappings) {
        return new DynamicDepositProductData(data.id, data.name, data.shortName, data.description, data.currency,
                data.interestCompoundingPeriodType, data.interestPostingPeriodType, data.interestCalculationType,
                data.interestCalculationDaysInYearType, data.lockinPeriodFrequency, data.lockinPeriodFrequencyType, data.accountingRule,
                data.minBalanceForInterestCalculation, data.withHoldTax, data.taxGroupId, data.taxGroup, data.allowWithdrawal,
                data.dynamicRateEnabled, data.earlyWithdrawalPenaltyEnabled, data.earlyWithdrawalChargeId, data.earlyWithdrawalChargeMode,
                data.minDepositTerm, data.maxDepositTerm, data.minDepositTermType, data.maxDepositTermType, data.minDepositAmount,
                data.depositAmount, data.maxDepositAmount, data.withHoldTaxPostingType, data.charges, data.charts, accountingMappings,
                paymentChannelToFundSourceMappings, feeToIncomeAccountMappings, penaltyToIncomeAccountMappings, data.currencyOptions,
                data.interestCompoundingPeriodTypeOptions, data.interestPostingPeriodTypeOptions, data.interestCalculationTypeOptions,
                data.interestCalculationDaysInYearTypeOptions, data.lockinPeriodFrequencyTypeOptions, data.accountingRuleOptions,
                data.chargeOptions, data.penaltyOptions, data.paymentTypeOptions, data.accountingMappingOptions, data.taxGroupOptions,
                data.chartTemplate, data.depositTermTypeOptions, data.withHoldTaxPostingTypeOptions);
    }

    public static DynamicDepositProductData withCharts(final DynamicDepositProductData data,
            final Collection<InterestRateChartData> charts) {
        return new DynamicDepositProductData(data.id, data.name, data.shortName, data.description, data.currency,
                data.interestCompoundingPeriodType, data.interestPostingPeriodType, data.interestCalculationType,
                data.interestCalculationDaysInYearType, data.lockinPeriodFrequency, data.lockinPeriodFrequencyType, data.accountingRule,
                data.minBalanceForInterestCalculation, data.withHoldTax, data.taxGroupId, data.taxGroup, data.allowWithdrawal,
                data.dynamicRateEnabled, data.earlyWithdrawalPenaltyEnabled, data.earlyWithdrawalChargeId, data.earlyWithdrawalChargeMode,
                data.minDepositTerm, data.maxDepositTerm, data.minDepositTermType, data.maxDepositTermType, data.minDepositAmount,
                data.depositAmount, data.maxDepositAmount, data.withHoldTaxPostingType, data.charges, charts, data.accountingMappings,
                data.paymentChannelToFundSourceMappings, data.feeToIncomeAccountMappings, data.penaltyToIncomeAccountMappings,
                data.currencyOptions, data.interestCompoundingPeriodTypeOptions, data.interestPostingPeriodTypeOptions,
                data.interestCalculationTypeOptions, data.interestCalculationDaysInYearTypeOptions, data.lockinPeriodFrequencyTypeOptions,
                data.accountingRuleOptions, data.chargeOptions, data.penaltyOptions, data.paymentTypeOptions, data.accountingMappingOptions,
                data.taxGroupOptions, data.chartTemplate, data.depositTermTypeOptions, data.withHoldTaxPostingTypeOptions);
    }

    public static DynamicDepositProductData withCharges(final DynamicDepositProductData data, final Collection<ChargeData> charges) {
        return new DynamicDepositProductData(data.id, data.name, data.shortName, data.description, data.currency,
                data.interestCompoundingPeriodType, data.interestPostingPeriodType, data.interestCalculationType,
                data.interestCalculationDaysInYearType, data.lockinPeriodFrequency, data.lockinPeriodFrequencyType, data.accountingRule,
                data.minBalanceForInterestCalculation, data.withHoldTax, data.taxGroupId, data.taxGroup, data.allowWithdrawal,
                data.dynamicRateEnabled, data.earlyWithdrawalPenaltyEnabled, data.earlyWithdrawalChargeId, data.earlyWithdrawalChargeMode,
                data.minDepositTerm, data.maxDepositTerm, data.minDepositTermType, data.maxDepositTermType, data.minDepositAmount,
                data.depositAmount, data.maxDepositAmount, data.withHoldTaxPostingType, charges, data.charts, data.accountingMappings,
                data.paymentChannelToFundSourceMappings, data.feeToIncomeAccountMappings, data.penaltyToIncomeAccountMappings,
                data.currencyOptions, data.interestCompoundingPeriodTypeOptions, data.interestPostingPeriodTypeOptions,
                data.interestCalculationTypeOptions, data.interestCalculationDaysInYearTypeOptions, data.lockinPeriodFrequencyTypeOptions,
                data.accountingRuleOptions, data.chargeOptions, data.penaltyOptions, data.paymentTypeOptions, data.accountingMappingOptions,
                data.taxGroupOptions, data.chartTemplate, data.depositTermTypeOptions, data.withHoldTaxPostingTypeOptions);
    }

    public Long id() {
        return this.id;
    }

    public CurrencyData currency() {
        return this.currency;
    }

    public Integer minDepositTerm() {
        return this.minDepositTerm;
    }

    public Integer maxDepositTerm() {
        return this.maxDepositTerm;
    }

    public BigDecimal minDepositAmount() {
        return this.minDepositAmount;
    }

    public BigDecimal depositAmount() {
        return this.depositAmount;
    }

    public BigDecimal maxDepositAmount() {
        return this.maxDepositAmount;
    }

    public EnumOptionData withHoldTaxPostingType() {
        return this.withHoldTaxPostingType;
    }

    public TaxGroupData taxGroup() {
        return this.taxGroup;
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

    public Collection<ChargeData> penaltyOptions() {
        return this.penaltyOptions;
    }

    public Collection<PaymentTypeData> paymentTypeOptions() {
        return this.paymentTypeOptions;
    }

    public Map<String, List<GLAccountData>> accountingMappingOptions() {
        return this.accountingMappingOptions;
    }

    public Collection<TaxGroupData> taxGroupOptions() {
        return this.taxGroupOptions;
    }

    public InterestRateChartData chartTemplate() {
        return this.chartTemplate;
    }

    public InterestRateChartData activeChart() {
        return this.activeChart;
    }

    public Collection<EnumOptionData> depositTermTypeOptions() {
        return this.depositTermTypeOptions;
    }

    public Collection<EnumOptionData> withHoldTaxPostingTypeOptions() {
        return this.withHoldTaxPostingTypeOptions;
    }

    public boolean hasAccountingEnabled() {
        return this.accountingRule != null && this.accountingRule.getId() > AccountingRuleType.NONE.getValue();
    }

    public int accountingRuleTypeId() {
        return this.accountingRule == null ? AccountingRuleType.NONE.getValue() : this.accountingRule.getId().intValue();
    }
}
