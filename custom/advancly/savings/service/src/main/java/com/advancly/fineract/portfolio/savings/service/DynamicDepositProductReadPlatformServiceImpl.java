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

import com.advancly.fineract.portfolio.savings.data.DynamicDepositProductData;
import com.advancly.fineract.portfolio.savings.domain.EarlyWithdrawalChargeMode;
import com.advancly.fineract.portfolio.savings.exception.DynamicDepositProductNotFoundException;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import org.apache.fineract.accounting.common.AccountingDropdownReadPlatformService;
import org.apache.fineract.accounting.common.AccountingEnumerations;
import org.apache.fineract.accounting.glaccount.data.GLAccountData;
import org.apache.fineract.infrastructure.core.data.EnumOptionData;
import org.apache.fineract.infrastructure.core.domain.JdbcSupport;
import org.apache.fineract.infrastructure.security.service.PlatformSecurityContext;
import org.apache.fineract.organisation.monetary.data.CurrencyData;
import org.apache.fineract.organisation.monetary.service.CurrencyReadPlatformService;
import org.apache.fineract.portfolio.charge.data.ChargeData;
import org.apache.fineract.portfolio.charge.service.ChargeReadPlatformService;
import org.apache.fineract.portfolio.interestratechart.data.InterestRateChartData;
import org.apache.fineract.portfolio.interestratechart.service.InterestRateChartReadPlatformService;
import org.apache.fineract.portfolio.paymenttype.data.PaymentTypeData;
import org.apache.fineract.portfolio.paymenttype.service.PaymentTypeReadPlatformService;
import org.apache.fineract.portfolio.savings.service.SavingsDropdownReadPlatformService;
import org.apache.fineract.portfolio.savings.service.SavingsEnumerations;
import org.apache.fineract.portfolio.tax.data.TaxGroupData;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;

/**
 * Lean, self-contained read-side implementation for the Dynamic Deposit product (deposit_type_enum = 500). Joins the
 * same {@code m_deposit_product_term_and_preclosure} table FD/RD use for min/max deposit term, min/default/max deposit
 * amount and the withholding-tax posting type.
 */
@Service
public class DynamicDepositProductReadPlatformServiceImpl implements DynamicDepositProductReadPlatformService {

    private static final DynamicDepositProductMapper MAPPER = new DynamicDepositProductMapper();

    private final PlatformSecurityContext context;
    private final JdbcTemplate jdbcTemplate;
    private final CurrencyReadPlatformService currencyReadPlatformService;
    private final SavingsDropdownReadPlatformService savingsDropdownReadPlatformService;
    private final AccountingDropdownReadPlatformService accountingDropdownReadPlatformService;
    private final ChargeReadPlatformService chargeReadPlatformService;
    private final InterestRateChartReadPlatformService chartReadPlatformService;
    private final PaymentTypeReadPlatformService paymentTypeReadPlatformService;

    public DynamicDepositProductReadPlatformServiceImpl(final PlatformSecurityContext context, final JdbcTemplate jdbcTemplate,
            final CurrencyReadPlatformService currencyReadPlatformService,
            final SavingsDropdownReadPlatformService savingsDropdownReadPlatformService,
            final AccountingDropdownReadPlatformService accountingDropdownReadPlatformService,
            final ChargeReadPlatformService chargeReadPlatformService, final InterestRateChartReadPlatformService chartReadPlatformService,
            final PaymentTypeReadPlatformService paymentTypeReadPlatformService) {
        this.context = context;
        this.jdbcTemplate = jdbcTemplate;
        this.currencyReadPlatformService = currencyReadPlatformService;
        this.savingsDropdownReadPlatformService = savingsDropdownReadPlatformService;
        this.accountingDropdownReadPlatformService = accountingDropdownReadPlatformService;
        this.chargeReadPlatformService = chargeReadPlatformService;
        this.chartReadPlatformService = chartReadPlatformService;
        this.paymentTypeReadPlatformService = paymentTypeReadPlatformService;
    }

    @Override
    public Collection<DynamicDepositProductData> retrieveAll() {
        this.context.authenticatedUser();
        final String sql = "select " + MAPPER.schema() + " where sp.deposit_type_enum = 500 ";
        final List<DynamicDepositProductData> products = this.jdbcTemplate.query(sql, MAPPER);
        final List<DynamicDepositProductData> result = new ArrayList<>(products.size());
        for (DynamicDepositProductData product : products) {
            final Collection<ChargeData> charges = this.chargeReadPlatformService.retrieveSavingsProductCharges(product.id());
            final Collection<InterestRateChartData> charts = this.chartReadPlatformService.retrieveAllWithSlabs(product.id());
            product = DynamicDepositProductData.withCharges(product, charges);
            product = DynamicDepositProductData.withCharts(product, charts);
            result.add(product);
        }
        return result;
    }

    @Override
    public DynamicDepositProductData retrieveOne(final Long productId) {
        try {
            this.context.authenticatedUser();
            final String sql = "select " + MAPPER.schema() + " where sp.id = ? and sp.deposit_type_enum = 500 ";
            DynamicDepositProductData productData = this.jdbcTemplate.queryForObject(sql, MAPPER, productId);
            final Collection<ChargeData> charges = this.chargeReadPlatformService.retrieveSavingsProductCharges(productId);
            final Collection<InterestRateChartData> charts = this.chartReadPlatformService.retrieveAllWithSlabsWithTemplate(productId);
            productData = DynamicDepositProductData.withCharges(productData, charges);
            productData = DynamicDepositProductData.withCharts(productData, charts);
            return productData;
        } catch (final EmptyResultDataAccessException e) {
            throw new DynamicDepositProductNotFoundException(productId, e);
        }
    }

    @Override
    public DynamicDepositProductData retrieveTemplate() {
        this.context.authenticatedUser();

        final Collection<CurrencyData> currencyOptions = this.currencyReadPlatformService.retrieveAllowedCurrencies();
        final Collection<EnumOptionData> compoundingInterestPeriodTypeOptions = this.savingsDropdownReadPlatformService
                .retrieveCompoundingInterestPeriodTypeOptions();
        final Collection<EnumOptionData> interestPostingPeriodTypeOptions = this.savingsDropdownReadPlatformService
                .retrieveInterestPostingPeriodTypeOptions();
        final Collection<EnumOptionData> interestCalculationTypeOptions = this.savingsDropdownReadPlatformService
                .retrieveInterestCalculationTypeOptions();
        final Collection<EnumOptionData> interestCalculationDaysInYearTypeOptions = this.savingsDropdownReadPlatformService
                .retrieveInterestCalculationDaysInYearTypeOptions();
        final Collection<EnumOptionData> lockinPeriodFrequencyTypeOptions = this.savingsDropdownReadPlatformService
                .retrieveLockinPeriodFrequencyTypeOptions();
        final Collection<EnumOptionData> accountingRuleOptions = this.accountingDropdownReadPlatformService
                .retrieveAccountingRuleTypeOptions();
        final Collection<ChargeData> chargeOptions = this.chargeReadPlatformService.retrieveSavingsProductApplicableCharges(false);
        final Collection<ChargeData> penaltyOptions = this.chargeReadPlatformService.retrieveSavingsApplicablePenalties();
        final Collection<PaymentTypeData> paymentTypeOptions = this.paymentTypeReadPlatformService.retrieveAllPaymentTypes();
        final Map<String, List<GLAccountData>> accountingMappingOptions = this.accountingDropdownReadPlatformService
                .retrieveAccountMappingOptionsForSavingsProducts();
        final Collection<TaxGroupData> taxGroupOptions = retrieveTaxGroupOptions();
        final InterestRateChartData chartTemplate = this.chartReadPlatformService.template();
        // Deposit term is expressed with the same DAYS/WEEKS/MONTHS/YEARS enum as the lock-in period.
        final Collection<EnumOptionData> depositTermTypeOptions = lockinPeriodFrequencyTypeOptions;
        final Collection<EnumOptionData> withHoldTaxPostingTypeOptions = this.savingsDropdownReadPlatformService
                .retrieveWithHoldTaxPostingTypeOptions();

        // id, name, shortName, description, currency, interestCompoundingPeriodType, interestPostingPeriodType,
        // interestCalculationType, interestCalculationDaysInYearType, lockinPeriodFrequency, lockinPeriodFrequencyType,
        // accountingRule
        final DynamicDepositProductData data = new DynamicDepositProductData(null, null, null, null, null, null, null, null, null, null,
                null, null,
                // minBalanceForInterestCalculation, withHoldTax, taxGroupId, taxGroup, allowWithdrawal,
                // dynamicRateEnabled,
                // earlyWithdrawalPenaltyEnabled, earlyWithdrawalChargeId, earlyWithdrawalChargeMode
                null, false, null, null, false, false, false, null, null,
                // minDepositTerm, maxDepositTerm, minDepositTermType, maxDepositTermType, minDepositAmount,
                // depositAmount,
                // maxDepositAmount, withHoldTaxPostingType, charges, charts
                null, null, null, null, null, null, null, null, null, null);
        return DynamicDepositProductData.withTemplateOptions(data, currencyOptions, compoundingInterestPeriodTypeOptions,
                interestPostingPeriodTypeOptions, interestCalculationTypeOptions, interestCalculationDaysInYearTypeOptions,
                lockinPeriodFrequencyTypeOptions, accountingRuleOptions, chargeOptions, penaltyOptions, paymentTypeOptions,
                accountingMappingOptions, taxGroupOptions, chartTemplate, depositTermTypeOptions, withHoldTaxPostingTypeOptions);
    }

    private Collection<TaxGroupData> retrieveTaxGroupOptions() {
        return this.jdbcTemplate.query("select id, name from m_tax_group", (rs, rowNum) -> {
            final Long id = rs.getLong("id");
            final String name = rs.getString("name");
            return TaxGroupData.lookup(id, name);
        });
    }

    private static final class DynamicDepositProductMapper implements RowMapper<DynamicDepositProductData> {

        public String schema() {
            final StringBuilder sqlBuilder = new StringBuilder(600);
            sqlBuilder.append("sp.id as id, sp.name as name, sp.short_name as shortName, sp.description as description, ");
            sqlBuilder.append(
                    "sp.currency_code as currencyCode, sp.currency_digits as currencyDigits, sp.currency_multiplesof as inMultiplesOf, ");
            sqlBuilder.append("curr.name as currencyName, curr.internationalized_name_code as currencyNameCode, ");
            sqlBuilder.append("curr.display_symbol as currencyDisplaySymbol, ");
            sqlBuilder.append("sp.interest_compounding_period_enum as interestCompoundingPeriodType, ");
            sqlBuilder.append("sp.interest_posting_period_enum as interestPostingPeriodType, ");
            sqlBuilder.append("sp.interest_calculation_type_enum as interestCalculationType, ");
            sqlBuilder.append("sp.interest_calculation_days_in_year_type_enum as interestCalculationDaysInYearType, ");
            sqlBuilder.append("sp.lockin_period_frequency as lockinPeriodFrequency, ");
            sqlBuilder.append("sp.lockin_period_frequency_enum as lockinPeriodFrequencyType, ");
            sqlBuilder.append("sp.accounting_type as accountingType, ");
            sqlBuilder.append("sp.min_balance_for_interest_calculation as minBalanceForInterestCalculation, ");
            sqlBuilder.append("sp.withhold_tax as withHoldTax, ");
            sqlBuilder.append("tg.id as taxGroupId, tg.name as taxGroupName, ");
            sqlBuilder.append("ddd.allow_withdrawal as allowWithdrawal, ddd.dynamic_rate_enabled as dynamicRateEnabled, ");
            sqlBuilder.append("ddd.early_withdrawal_penalty_enabled as earlyWithdrawalPenaltyEnabled, ");
            sqlBuilder.append("ewc.charge_id as earlyWithdrawalChargeId, ");
            sqlBuilder.append("ewc.early_withdrawal_charge_mode_enum as earlyWithdrawalChargeMode, ");
            sqlBuilder.append("dptp.min_deposit_term as minDepositTerm, dptp.max_deposit_term as maxDepositTerm, ");
            sqlBuilder.append(
                    "dptp.min_deposit_term_type_enum as minDepositTermType, dptp.max_deposit_term_type_enum as maxDepositTermType, ");
            sqlBuilder.append("dptp.min_deposit_amount as minDepositAmount, dptp.deposit_amount as depositAmount, ");
            sqlBuilder
                    .append("dptp.max_deposit_amount as maxDepositAmount, dptp.withhold_tax_posting_type_enum as withHoldTaxPostingType ");
            sqlBuilder.append("from m_savings_product sp ");
            sqlBuilder.append("join m_currency curr on curr.code = sp.currency_code ");
            sqlBuilder.append("left join m_tax_group tg on tg.id = sp.tax_group_id ");
            sqlBuilder.append("left join m_deposit_product_dynamic_detail ddd on ddd.savings_product_id = sp.id ");
            sqlBuilder.append("left join m_savings_product_early_withdrawal_charge ewc on ewc.savings_product_id = sp.id ");
            sqlBuilder.append("left join m_deposit_product_term_and_preclosure dptp on dptp.savings_product_id = sp.id ");
            return sqlBuilder.toString();
        }

        @Override
        public DynamicDepositProductData mapRow(final ResultSet rs, @SuppressWarnings("unused") final int rowNum) throws SQLException {

            final Long id = rs.getLong("id");
            final String name = rs.getString("name");
            final String shortName = rs.getString("shortName");
            final String description = rs.getString("description");

            final String currencyCode = rs.getString("currencyCode");
            final String currencyName = rs.getString("currencyName");
            final String currencyNameCode = rs.getString("currencyNameCode");
            final String currencyDisplaySymbol = rs.getString("currencyDisplaySymbol");
            final Integer currencyDigits = JdbcSupport.getInteger(rs, "currencyDigits");
            final Integer inMultiplesOf = JdbcSupport.getInteger(rs, "inMultiplesOf");
            final CurrencyData currency = new CurrencyData(currencyCode, currencyName, currencyDigits, inMultiplesOf, currencyDisplaySymbol,
                    currencyNameCode);

            final EnumOptionData interestCompoundingPeriodType = SavingsEnumerations
                    .compoundingInterestPeriodType(JdbcSupport.getInteger(rs, "interestCompoundingPeriodType"));
            final EnumOptionData interestPostingPeriodType = SavingsEnumerations
                    .interestPostingPeriodType(JdbcSupport.getInteger(rs, "interestPostingPeriodType"));
            final EnumOptionData interestCalculationType = SavingsEnumerations
                    .interestCalculationType(JdbcSupport.getInteger(rs, "interestCalculationType"));

            EnumOptionData interestCalculationDaysInYearType = null;
            final Integer interestCalculationDaysInYearTypeValue = JdbcSupport.getInteger(rs, "interestCalculationDaysInYearType");
            if (interestCalculationDaysInYearTypeValue != null) {
                interestCalculationDaysInYearType = SavingsEnumerations
                        .interestCalculationDaysInYearType(interestCalculationDaysInYearTypeValue);
            }

            final EnumOptionData accountingRuleType = AccountingEnumerations
                    .accountingRuleType(JdbcSupport.getInteger(rs, "accountingType"));

            final Integer lockinPeriodFrequency = JdbcSupport.getInteger(rs, "lockinPeriodFrequency");
            EnumOptionData lockinPeriodFrequencyType = null;
            final Integer lockinPeriodFrequencyTypeValue = JdbcSupport.getInteger(rs, "lockinPeriodFrequencyType");
            if (lockinPeriodFrequencyTypeValue != null) {
                lockinPeriodFrequencyType = SavingsEnumerations.lockinPeriodFrequencyType(lockinPeriodFrequencyTypeValue);
            }
            final BigDecimal minBalanceForInterestCalculation = rs.getBigDecimal("minBalanceForInterestCalculation");

            final boolean withHoldTax = rs.getBoolean("withHoldTax");
            final Long taxGroupId = JdbcSupport.getLong(rs, "taxGroupId");
            final String taxGroupName = rs.getString("taxGroupName");
            final TaxGroupData taxGroup = taxGroupId == null ? null : TaxGroupData.lookup(taxGroupId, taxGroupName);

            final boolean allowWithdrawal = rs.getBoolean("allowWithdrawal");
            final boolean dynamicRateEnabled = rs.getBoolean("dynamicRateEnabled");
            final boolean earlyWithdrawalPenaltyEnabled = rs.getBoolean("earlyWithdrawalPenaltyEnabled");
            final Long earlyWithdrawalChargeId = JdbcSupport.getLong(rs, "earlyWithdrawalChargeId");
            final Integer earlyWithdrawalChargeModeValue = JdbcSupport.getInteger(rs, "earlyWithdrawalChargeMode");
            final Integer earlyWithdrawalChargeMode = earlyWithdrawalChargeModeValue == null ? null
                    : EarlyWithdrawalChargeMode.fromInt(earlyWithdrawalChargeModeValue).getValue();

            final Integer minDepositTerm = JdbcSupport.getInteger(rs, "minDepositTerm");
            final Integer maxDepositTerm = JdbcSupport.getInteger(rs, "maxDepositTerm");
            EnumOptionData minDepositTermType = null;
            final Integer minDepositTermTypeValue = JdbcSupport.getInteger(rs, "minDepositTermType");
            if (minDepositTermTypeValue != null) {
                minDepositTermType = SavingsEnumerations.depositTermFrequencyType(minDepositTermTypeValue);
            }
            EnumOptionData maxDepositTermType = null;
            final Integer maxDepositTermTypeValue = JdbcSupport.getInteger(rs, "maxDepositTermType");
            if (maxDepositTermTypeValue != null) {
                maxDepositTermType = SavingsEnumerations.depositTermFrequencyType(maxDepositTermTypeValue);
            }
            final BigDecimal minDepositAmount = rs.getBigDecimal("minDepositAmount");
            final BigDecimal depositAmount = rs.getBigDecimal("depositAmount");
            final BigDecimal maxDepositAmount = rs.getBigDecimal("maxDepositAmount");
            EnumOptionData withHoldTaxPostingType = null;
            final Integer withHoldTaxPostingTypeValue = JdbcSupport.getInteger(rs, "withHoldTaxPostingType");
            if (withHoldTaxPostingTypeValue != null) {
                withHoldTaxPostingType = SavingsEnumerations.withHoldTaxPostingType(withHoldTaxPostingTypeValue);
            }

            return new DynamicDepositProductData(id, name, shortName, description, currency, interestCompoundingPeriodType,
                    interestPostingPeriodType, interestCalculationType, interestCalculationDaysInYearType, lockinPeriodFrequency,
                    lockinPeriodFrequencyType, accountingRuleType, minBalanceForInterestCalculation, withHoldTax, taxGroupId, taxGroup,
                    allowWithdrawal, dynamicRateEnabled, earlyWithdrawalPenaltyEnabled, earlyWithdrawalChargeId, earlyWithdrawalChargeMode,
                    minDepositTerm, maxDepositTerm, minDepositTermType, maxDepositTermType, minDepositAmount, depositAmount,
                    maxDepositAmount, withHoldTaxPostingType, null, null);
        }
    }
}
