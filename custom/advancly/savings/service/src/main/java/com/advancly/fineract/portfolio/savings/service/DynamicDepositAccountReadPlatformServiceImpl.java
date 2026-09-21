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

import com.advancly.fineract.portfolio.savings.data.DynamicDepositAccountData;
import com.advancly.fineract.portfolio.savings.data.DynamicDepositInterestSummaryData;
import com.advancly.fineract.portfolio.savings.domain.DepositAccountDynamicRateHistory;
import com.advancly.fineract.portfolio.savings.domain.DepositAccountDynamicRateHistoryRepository;
import com.advancly.fineract.portfolio.savings.domain.DepositAccountInterestWithdrawal;
import com.advancly.fineract.portfolio.savings.domain.DepositAccountInterestWithdrawalRepository;
import com.advancly.fineract.portfolio.savings.domain.DepositInterestChargeApplicationRepository;
import com.advancly.fineract.portfolio.savings.exception.DynamicDepositAccountNotFoundException;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import org.apache.fineract.infrastructure.core.data.EnumOptionData;
import org.apache.fineract.infrastructure.core.domain.JdbcSupport;
import org.apache.fineract.infrastructure.security.service.PlatformSecurityContext;
import org.apache.fineract.organisation.monetary.data.CurrencyData;
import org.apache.fineract.portfolio.savings.DepositAccountType;
import org.apache.fineract.portfolio.savings.data.DepositAccountInterestRateChartData;
import org.apache.fineract.portfolio.savings.data.SavingsAccountApplicationTimelineData;
import org.apache.fineract.portfolio.savings.data.SavingsAccountStatusEnumData;
import org.apache.fineract.portfolio.savings.data.SavingsAccountSummaryData;
import org.apache.fineract.portfolio.savings.domain.SavingsAccount;
import org.apache.fineract.portfolio.savings.domain.SavingsAccountRepositoryWrapper;
import org.apache.fineract.portfolio.savings.domain.SavingsAccountSummary;
import org.apache.fineract.portfolio.savings.exception.SavingsAccountNotFoundException;
import org.apache.fineract.portfolio.savings.service.DepositAccountInterestRateChartReadPlatformService;
import org.apache.fineract.portfolio.savings.service.SavingsEnumerations;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;

/**
 * Lean, self-contained read-side implementation for the Dynamic Deposit account (deposit_type_enum = 500). Joins the
 * reused {@code m_deposit_account_term_and_preclosure} table (fixed tenor / invested amount, exactly as
 * {@code FixedDepositAccount} does) and the new {@code m_deposit_account_dynamic_detail} table.
 */
@Service
public class DynamicDepositAccountReadPlatformServiceImpl implements DynamicDepositAccountReadPlatformService {

    private static final DynamicDepositAccountMapper MAPPER = new DynamicDepositAccountMapper();

    private final PlatformSecurityContext context;
    private final JdbcTemplate jdbcTemplate;
    private final DynamicDepositProductReadPlatformService dynamicDepositProductReadPlatformService;
    private final SavingsAccountRepositoryWrapper savingsAccountRepository;
    private final DepositAccountInterestWithdrawalRepository interestWithdrawalRepository;
    private final DepositAccountDynamicRateHistoryRepository rateHistoryRepository;
    private final DepositInterestChargeApplicationRepository interestChargeApplicationRepository;
    private final DepositAccountInterestRateChartReadPlatformService accountChartReadPlatformService;

    public DynamicDepositAccountReadPlatformServiceImpl(final PlatformSecurityContext context, final JdbcTemplate jdbcTemplate,
            final DynamicDepositProductReadPlatformService dynamicDepositProductReadPlatformService,
            final SavingsAccountRepositoryWrapper savingsAccountRepository,
            final DepositAccountInterestWithdrawalRepository interestWithdrawalRepository,
            final DepositAccountDynamicRateHistoryRepository rateHistoryRepository,
            final DepositInterestChargeApplicationRepository interestChargeApplicationRepository,
            final DepositAccountInterestRateChartReadPlatformService accountChartReadPlatformService) {
        this.context = context;
        this.jdbcTemplate = jdbcTemplate;
        this.dynamicDepositProductReadPlatformService = dynamicDepositProductReadPlatformService;
        this.savingsAccountRepository = savingsAccountRepository;
        this.interestWithdrawalRepository = interestWithdrawalRepository;
        this.rateHistoryRepository = rateHistoryRepository;
        this.interestChargeApplicationRepository = interestChargeApplicationRepository;
        this.accountChartReadPlatformService = accountChartReadPlatformService;
    }

    @Override
    public Collection<DynamicDepositAccountData> retrieveAll() {
        this.context.authenticatedUser();
        final String sql = "select " + MAPPER.schema() + " where sa.deposit_type_enum = 500 ";
        return this.jdbcTemplate.query(sql, MAPPER);
    }

    @Override
    public DynamicDepositAccountData retrieveOne(final Long accountId) {
        try {
            this.context.authenticatedUser();
            final String sql = "select " + MAPPER.schema() + " where sa.id = ? and sa.deposit_type_enum = 500 ";
            final DynamicDepositAccountData accountData = this.jdbcTemplate.queryForObject(sql, MAPPER, accountId);
            final DepositAccountInterestRateChartData chart = this.accountChartReadPlatformService
                    .retrieveOneWithSlabsOnAccountId(accountId);
            return DynamicDepositAccountData.withChart(accountData, chart);
        } catch (final EmptyResultDataAccessException e) {
            throw new DynamicDepositAccountNotFoundException(accountId, e);
        }
    }

    @Override
    public DynamicDepositInterestSummaryData retrieveInterestSummary(final Long accountId) {
        this.context.authenticatedUser();

        final SavingsAccount account;
        try {
            account = this.savingsAccountRepository.findOneWithNotFoundDetection(accountId, DepositAccountType.DYNAMIC_DEPOSIT);
        } catch (final SavingsAccountNotFoundException e) {
            throw new DynamicDepositAccountNotFoundException(accountId, e);
        }
        final SavingsAccountSummary summary = account.getSummary();

        final BigDecimal grossInterestEarnedAsAtToday = defaultToZero(summary.getTotalInterestEarned());
        final BigDecimal interestPosted = defaultToZero(summary.getTotalInterestPosted());
        // The current period's unposted accrual: total earned to date minus what has already been posted.
        final BigDecimal totalInterestForPeriod = grossInterestEarnedAsAtToday.subtract(interestPosted);
        final BigDecimal withholdingTax = defaultToZero(summary.getTotalWithholdTax());
        final BigDecimal interestBasedChargePostedDerived = defaultToZero(
                this.interestChargeApplicationRepository.sumActiveAppliedAmountForAccount(accountId));
        final BigDecimal interestBasedCharges = interestBasedChargePostedDerived;
        // Phase 5 (Transfers And Withdrawal Lock) owns this field; it stays zero for now.
        final BigDecimal interestTransferredToSavings = BigDecimal.ZERO;

        BigDecimal interestWithdrawn = BigDecimal.ZERO;
        for (final DepositAccountInterestWithdrawal withdrawal : this.interestWithdrawalRepository
                .findByAccountIdOrderByTransactionDateAscIdAsc(accountId)) {
            // Skip marker rows whose linked posting or withdrawal transaction has since been reversed (e.g. a
            // backdated-transaction correction that reverses-and-replaces the interest posting transaction) - the
            // marker's own row carries no reversal state of its own (see class Javadoc), so this must be checked on
            // both linked transactions to avoid double-counting against a transaction that no longer stands.
            if (withdrawal.interestPostingTransaction().isReversed() || withdrawal.withdrawalTransaction().isReversed()) {
                continue;
            }
            interestWithdrawn = interestWithdrawn.add(withdrawal.withdrawnInterestAmount());
        }

        // Life-to-date scope, consistent with withholdingTax and interestBasedCharges (both life-to-date; charges are
        // read from the application ledger) - not totalInterestForPeriod, which is the current unposted accrual.
        // Phase 5's Transfer Interest To Savings Job computes its own period-scoped net interest directly from
        // per-transaction data and does not read this DTO, so this field is a reporting convenience only; a correct
        // per-period WHT figure isn't computable this phase anyway since WHT is only known once a period is actually
        // posted.
        final BigDecimal netInterest = interestPosted.subtract(withholdingTax).subtract(interestBasedCharges);

        final List<DepositAccountDynamicRateHistory> rateHistoryRows = this.rateHistoryRepository
                .findByAccountIdOrderByTransactionDateAscIdAsc(accountId);
        final List<DynamicDepositInterestSummaryData.RateIntervalData> effectiveRateIntervals = rateHistoryRows.stream()
                .map(row -> new DynamicDepositInterestSummaryData.RateIntervalData(row.transactionDate(),
                        row.investedAmountAfterTransaction(), row.resolvedAnnualInterestRate(), row.rateSource()))
                .toList();

        return new DynamicDepositInterestSummaryData(grossInterestEarnedAsAtToday, interestPosted, totalInterestForPeriod,
                interestWithdrawn, withholdingTax, interestBasedCharges, interestBasedChargePostedDerived, netInterest,
                interestTransferredToSavings, effectiveRateIntervals);
    }

    private static BigDecimal defaultToZero(final BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    @Override
    public DynamicDepositAccountData retrieveTemplate() {
        this.context.authenticatedUser();
        final Collection<com.advancly.fineract.portfolio.savings.data.DynamicDepositProductData> productOptions = this.dynamicDepositProductReadPlatformService
                .retrieveAll();
        // id, accountNo, externalId, clientId, clientName, groupId, groupName, savingsProductId, savingsProductName,
        // fieldOfficerId, status, currency, nominalAnnualInterestRate, interestCompoundingPeriodType,
        // interestPostingPeriodType,
        // interestCalculationType, interestCalculationDaysInYearType, depositAmount, depositPeriod,
        // depositPeriodFrequencyType,
        // expectedFirstDepositOnDate, maturityDate, maturityAmount, submittedOnDate, approvedOnDate, activatedOnDate
        final DynamicDepositAccountData data = new DynamicDepositAccountData(null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null,
                // allowWithdrawal, dynamicRateEnabled, transferInterestToSavings
                false, false, false);
        return DynamicDepositAccountData.withTemplateOptions(data, productOptions);
    }

    private static final class DynamicDepositAccountMapper implements RowMapper<DynamicDepositAccountData> {

        public String schema() {
            final StringBuilder sqlBuilder = new StringBuilder(600);
            sqlBuilder.append("sa.id as id, sa.account_no as accountNo, sa.external_id as externalId, ");
            sqlBuilder.append("sa.client_id as clientId, c.display_name as clientName, ");
            sqlBuilder.append("sa.group_id as groupId, g.display_name as groupName, ");
            sqlBuilder.append("sa.product_id as savingsProductId, sp.name as savingsProductName, ");
            sqlBuilder.append("sa.field_officer_id as fieldOfficerId, sa.status_enum as statusEnum, ");
            sqlBuilder.append("sa.currency_code as currencyCode, sa.currency_digits as currencyDigits, ");
            sqlBuilder.append("sa.currency_multiplesof as inMultiplesOf, curr.name as currencyName, ");
            sqlBuilder.append("curr.internationalized_name_code as currencyNameCode, curr.display_symbol as currencyDisplaySymbol, ");
            sqlBuilder.append("sa.nominal_annual_interest_rate as nominalAnnualInterestRate, ");
            sqlBuilder.append("sa.interest_compounding_period_enum as interestCompoundingPeriodType, ");
            sqlBuilder.append("sa.interest_posting_period_enum as interestPostingPeriodType, ");
            sqlBuilder.append("sa.interest_calculation_type_enum as interestCalculationType, ");
            sqlBuilder.append("sa.interest_calculation_days_in_year_type_enum as interestCalculationDaysInYearType, ");
            sqlBuilder.append("sa.total_deposits_derived as totalDeposits, ");
            sqlBuilder.append("sa.total_withdrawals_derived as totalWithdrawals, ");
            sqlBuilder.append("sa.total_withdrawal_fees_derived as totalWithdrawalFees, ");
            sqlBuilder.append("sa.total_annual_fees_derived as totalAnnualFees, ");
            sqlBuilder.append("sa.total_interest_earned_derived as totalInterestEarned, ");
            sqlBuilder.append("sa.total_interest_posted_derived as totalInterestPosted, ");
            sqlBuilder.append("sa.account_balance_derived as accountBalance, ");
            sqlBuilder.append("sa.total_fees_charge_derived as totalFeeCharge, ");
            sqlBuilder.append("sa.total_penalty_charge_derived as totalPenaltyCharge, ");
            sqlBuilder.append("sa.total_withhold_tax_derived as totalWithholdTax, ");
            sqlBuilder.append("sa.interest_posted_till_date as interestPostedTillDate, ");
            sqlBuilder.append("dat.deposit_amount as depositAmount, dat.deposit_period as depositPeriod, ");
            sqlBuilder.append("dat.deposit_period_frequency_enum as depositPeriodFrequencyType, ");
            sqlBuilder.append("dat.expected_firstdepositon_date as expectedFirstDepositOnDate, ");
            sqlBuilder.append("dat.maturity_date as maturityDate, dat.maturity_amount as maturityAmount, ");
            sqlBuilder.append("dat.transfer_interest_to_linked_account as transferInterestToSavings, ");
            sqlBuilder.append("aa.linked_savings_account_id as linkAccountId, ");
            sqlBuilder.append("sa.submittedon_date as submittedOnDate, sa.approvedon_date as approvedOnDate, ");
            sqlBuilder.append("sa.activatedon_date as activatedOnDate, ");
            sqlBuilder.append("ddd.allow_withdrawal as allowWithdrawal, ddd.dynamic_rate_enabled as dynamicRateEnabled, ");
            sqlBuilder.append("dptp.min_deposit_term as minDepositTerm, dptp.max_deposit_term as maxDepositTerm, ");
            sqlBuilder.append("dptp.min_deposit_amount as minDepositAmount, dptp.max_deposit_amount as maxDepositAmount, ");
            sqlBuilder.append("dat.withhold_tax_posting_type_enum as withHoldTaxPostingType ");
            sqlBuilder.append("from m_savings_account sa ");
            sqlBuilder.append("join m_savings_product sp on sp.id = sa.product_id ");
            sqlBuilder.append("join m_currency curr on curr.code = sa.currency_code ");
            sqlBuilder.append("left join m_client c on c.id = sa.client_id ");
            sqlBuilder.append("left join m_group g on g.id = sa.group_id ");
            sqlBuilder.append("left join m_deposit_account_term_and_preclosure dat on dat.savings_account_id = sa.id ");
            sqlBuilder.append("left join m_deposit_account_dynamic_detail ddd on ddd.savings_account_id = sa.id ");
            sqlBuilder.append("left join m_deposit_product_term_and_preclosure dptp on dptp.savings_product_id = sa.product_id ");
            sqlBuilder.append(
                    "left join m_portfolio_account_associations aa on aa.savings_account_id = sa.id and aa.association_type_enum = 1 ");
            return sqlBuilder.toString();
        }

        @Override
        public DynamicDepositAccountData mapRow(final ResultSet rs, @SuppressWarnings("unused") final int rowNum) throws SQLException {

            final Long id = rs.getLong("id");
            final String accountNo = rs.getString("accountNo");
            final String externalId = rs.getString("externalId");
            final Long clientId = JdbcSupport.getLong(rs, "clientId");
            final String clientName = rs.getString("clientName");
            final Long groupId = JdbcSupport.getLong(rs, "groupId");
            final String groupName = rs.getString("groupName");
            final Long savingsProductId = rs.getLong("savingsProductId");
            final String savingsProductName = rs.getString("savingsProductName");
            final Long fieldOfficerId = JdbcSupport.getLong(rs, "fieldOfficerId");

            final Integer statusEnum = JdbcSupport.getInteger(rs, "statusEnum");
            final SavingsAccountStatusEnumData status = SavingsEnumerations.status(statusEnum);

            final String currencyCode = rs.getString("currencyCode");
            final String currencyName = rs.getString("currencyName");
            final String currencyNameCode = rs.getString("currencyNameCode");
            final String currencyDisplaySymbol = rs.getString("currencyDisplaySymbol");
            final Integer currencyDigits = JdbcSupport.getInteger(rs, "currencyDigits");
            final Integer inMultiplesOf = JdbcSupport.getInteger(rs, "inMultiplesOf");
            final CurrencyData currency = new CurrencyData(currencyCode, currencyName, currencyDigits, inMultiplesOf, currencyDisplaySymbol,
                    currencyNameCode);

            final BigDecimal nominalAnnualInterestRate = rs.getBigDecimal("nominalAnnualInterestRate");
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

            final BigDecimal depositAmount = rs.getBigDecimal("depositAmount");
            final Integer depositPeriod = JdbcSupport.getInteger(rs, "depositPeriod");
            EnumOptionData depositPeriodFrequencyType = null;
            final Integer depositPeriodFrequencyTypeValue = JdbcSupport.getInteger(rs, "depositPeriodFrequencyType");
            if (depositPeriodFrequencyTypeValue != null) {
                depositPeriodFrequencyType = SavingsEnumerations.depositPeriodFrequency(depositPeriodFrequencyTypeValue);
            }
            final LocalDate expectedFirstDepositOnDate = JdbcSupport.getLocalDate(rs, "expectedFirstDepositOnDate");
            final LocalDate maturityDate = JdbcSupport.getLocalDate(rs, "maturityDate");
            final BigDecimal maturityAmount = rs.getBigDecimal("maturityAmount");
            final boolean transferInterestToSavings = rs.getBoolean("transferInterestToSavings");
            final Long linkAccountId = JdbcSupport.getLong(rs, "linkAccountId");

            final LocalDate submittedOnDate = JdbcSupport.getLocalDate(rs, "submittedOnDate");
            final LocalDate approvedOnDate = JdbcSupport.getLocalDate(rs, "approvedOnDate");
            final LocalDate activatedOnDate = JdbcSupport.getLocalDate(rs, "activatedOnDate");
            final SavingsAccountApplicationTimelineData timeline = new SavingsAccountApplicationTimelineData(submittedOnDate, null, null,
                    null, null, null, null, null, null, null, null, null, approvedOnDate, null, null, null, activatedOnDate, null, null,
                    null, null, null, null, null);

            final boolean allowWithdrawal = rs.getBoolean("allowWithdrawal");
            final boolean dynamicRateEnabled = rs.getBoolean("dynamicRateEnabled");
            final BigDecimal totalDeposits = JdbcSupport.getBigDecimalDefaultToNullIfZero(rs, "totalDeposits");
            final BigDecimal totalWithdrawals = JdbcSupport.getBigDecimalDefaultToNullIfZero(rs, "totalWithdrawals");
            final BigDecimal totalWithdrawalFees = JdbcSupport.getBigDecimalDefaultToNullIfZero(rs, "totalWithdrawalFees");
            final BigDecimal totalAnnualFees = JdbcSupport.getBigDecimalDefaultToNullIfZero(rs, "totalAnnualFees");
            final BigDecimal totalInterestEarned = JdbcSupport.getBigDecimalDefaultToNullIfZero(rs, "totalInterestEarned");
            final BigDecimal totalInterestPosted = JdbcSupport.getBigDecimalDefaultToNullIfZero(rs, "totalInterestPosted");
            final BigDecimal accountBalance = JdbcSupport.getBigDecimalDefaultToZeroIfNull(rs, "accountBalance");
            final BigDecimal totalFeeCharge = JdbcSupport.getBigDecimalDefaultToNullIfZero(rs, "totalFeeCharge");
            final BigDecimal totalPenaltyCharge = JdbcSupport.getBigDecimalDefaultToNullIfZero(rs, "totalPenaltyCharge");
            final BigDecimal totalWithholdTax = JdbcSupport.getBigDecimalDefaultToNullIfZero(rs, "totalWithholdTax");
            final LocalDate interestPostedTillDate = JdbcSupport.getLocalDate(rs, "interestPostedTillDate");
            final SavingsAccountSummaryData summary = new SavingsAccountSummaryData(currency, totalDeposits, totalWithdrawals,
                    totalWithdrawalFees, totalAnnualFees, totalInterestEarned, totalInterestPosted, accountBalance, totalFeeCharge,
                    totalPenaltyCharge, null, totalWithholdTax, null, null, null, interestPostedTillDate);

            final Integer minDepositTerm = JdbcSupport.getInteger(rs, "minDepositTerm");
            final Integer maxDepositTerm = JdbcSupport.getInteger(rs, "maxDepositTerm");
            final BigDecimal minDepositAmount = rs.getBigDecimal("minDepositAmount");
            final BigDecimal maxDepositAmount = rs.getBigDecimal("maxDepositAmount");
            EnumOptionData withHoldTaxPostingType = null;
            final Integer withHoldTaxPostingTypeValue = JdbcSupport.getInteger(rs, "withHoldTaxPostingType");
            if (withHoldTaxPostingTypeValue != null) {
                withHoldTaxPostingType = SavingsEnumerations.withHoldTaxPostingType(withHoldTaxPostingTypeValue);
            }

            return DynamicDepositAccountData.withRangeAndPostingType(
                    new DynamicDepositAccountData(id, accountNo, externalId, clientId, clientName, groupId, groupName, savingsProductId,
                            savingsProductName, fieldOfficerId, status, timeline, currency, nominalAnnualInterestRate,
                            interestCompoundingPeriodType, interestPostingPeriodType, interestCalculationType,
                            interestCalculationDaysInYearType, depositAmount, depositPeriod, depositPeriodFrequencyType,
                            expectedFirstDepositOnDate, maturityDate, maturityAmount, submittedOnDate, approvedOnDate, activatedOnDate,
                            allowWithdrawal, dynamicRateEnabled, transferInterestToSavings, linkAccountId, summary),
                    minDepositTerm, maxDepositTerm, minDepositAmount, maxDepositAmount, withHoldTaxPostingType);
        }
    }
}
