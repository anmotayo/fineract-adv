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

import static com.advancly.fineract.portfolio.savings.AdvanclyInterestChargeApiConstants.APPLY_EARLY_WITHDRAWAL_CHARGE;
import static com.advancly.fineract.portfolio.savings.AdvanclyInterestChargeApiConstants.EARLY_WITHDRAWAL_CHARGE_PERCENTAGE;
import static com.advancly.fineract.portfolio.savings.AdvanclyInterestChargeApiConstants.SELECTED_FROM_DATE;
import static com.advancly.fineract.portfolio.savings.AdvanclyInterestChargeApiConstants.SELECTED_TO_DATE;

import com.advancly.fineract.portfolio.savings.domain.AdvanclyChargeInterestRule;
import com.advancly.fineract.portfolio.savings.domain.AdvanclyChargeInterestRuleRepository;
import com.advancly.fineract.portfolio.savings.domain.CustomPeriodReapplyPolicy;
import com.advancly.fineract.portfolio.savings.domain.DepositInterestChargeApplication;
import com.advancly.fineract.portfolio.savings.domain.DepositInterestChargeApplicationRepository;
import com.advancly.fineract.portfolio.savings.domain.InterestChargeMath;
import com.advancly.fineract.portfolio.savings.helper.SavingsAccountTransactionHelper;
import java.math.BigDecimal;
import java.math.MathContext;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.fineract.accounting.journalentry.service.JournalEntryWritePlatformService;
import org.apache.fineract.infrastructure.core.api.JsonCommand;
import org.apache.fineract.infrastructure.core.exception.GeneralPlatformDomainRuleException;
import org.apache.fineract.organisation.monetary.domain.Money;
import org.apache.fineract.portfolio.savings.SavingsPostingInterestPeriodType;
import org.apache.fineract.portfolio.savings.domain.SavingsAccount;
import org.apache.fineract.portfolio.savings.domain.SavingsAccountCharge;
import org.apache.fineract.portfolio.savings.domain.SavingsAccountChargePaidBy;
import org.apache.fineract.portfolio.savings.domain.SavingsAccountRepositoryWrapper;
import org.apache.fineract.portfolio.savings.domain.SavingsAccountTransaction;
import org.apache.fineract.portfolio.savings.domain.SavingsAccountTransactionRepository;
import org.springframework.context.annotation.Lazy;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Component
public class AdvanclyInterestChargeApplicationService {

    private static final BigDecimal ONE_HUNDRED = BigDecimal.valueOf(100L);
    private static final MathContext MATH_CONTEXT = MathContext.DECIMAL64;

    private final AdvanclyChargeInterestRuleRepository chargeInterestRuleRepository;
    private final DepositInterestChargeApplicationRepository applicationRepository;
    private final SavingsAccountTransactionRepository savingsAccountTransactionRepository;
    private final SavingsAccountRepositoryWrapper savingsAccountRepository;
    private final SavingsAccountTransactionHelper transactionHelper;
    private final JournalEntryWritePlatformService journalEntryWritePlatformService;
    private final CumulativeInterestForfeitureService cumulativeInterestForfeitureService;
    private final JdbcTemplate jdbcTemplate;

    public AdvanclyInterestChargeApplicationService(final AdvanclyChargeInterestRuleRepository chargeInterestRuleRepository,
            final DepositInterestChargeApplicationRepository applicationRepository,
            final SavingsAccountTransactionRepository savingsAccountTransactionRepository,
            final SavingsAccountRepositoryWrapper savingsAccountRepository, final SavingsAccountTransactionHelper transactionHelper,
            final JournalEntryWritePlatformService journalEntryWritePlatformService,
            @Lazy final CumulativeInterestForfeitureService cumulativeInterestForfeitureService, final JdbcTemplate jdbcTemplate) {
        this.chargeInterestRuleRepository = chargeInterestRuleRepository;
        this.applicationRepository = applicationRepository;
        this.savingsAccountTransactionRepository = savingsAccountTransactionRepository;
        this.savingsAccountRepository = savingsAccountRepository;
        this.transactionHelper = transactionHelper;
        this.journalEntryWritePlatformService = journalEntryWritePlatformService;
        this.cumulativeInterestForfeitureService = cumulativeInterestForfeitureService;
        this.jdbcTemplate = jdbcTemplate;
    }

    public boolean hasChargeDrivenRule(final SavingsAccount account) {
        return resolveSingleRule(account) != null;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public SavingsAccountTransaction applyIfApplicable(final SavingsAccount account, final SavingsAccountTransaction withdrawalTransaction,
            final JsonCommand command, final boolean backdatedTxnsAllowedTill) {
        final boolean applyEarlyWithdrawalCharge = command.booleanPrimitiveValueOfParameterNamed(APPLY_EARLY_WITHDRAWAL_CHARGE);
        final BigDecimal percentageOverride = command.parameterExists(EARLY_WITHDRAWAL_CHARGE_PERCENTAGE)
                ? command.bigDecimalValueOfParameterNamed(EARLY_WITHDRAWAL_CHARGE_PERCENTAGE)
                : null;
        final LocalDate selectedFromDate = command.localDateValueOfParameterNamed(SELECTED_FROM_DATE);
        final LocalDate selectedToDate = command.localDateValueOfParameterNamed(SELECTED_TO_DATE);
        return applyIfApplicable(account, withdrawalTransaction, applyEarlyWithdrawalCharge, percentageOverride, selectedFromDate,
                selectedToDate, backdatedTxnsAllowedTill);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public SavingsAccountTransaction applyIfApplicable(final SavingsAccount account, final SavingsAccountTransaction withdrawalTransaction,
            final boolean applyEarlyWithdrawalCharge, final BigDecimal percentageOverride, final LocalDate selectedFromDate,
            final LocalDate selectedToDate, final boolean backdatedTxnsAllowedTill) {
        if (withdrawalTransaction == null || withdrawalTransaction.isReversed()
                || !isRequested(account, withdrawalTransaction, applyEarlyWithdrawalCharge)) {
            return null;
        }
        validatePercentageOverride(percentageOverride);

        final AdvanclyChargeInterestRule rule = resolveSingleRule(account);
        if (rule == null) {
            return null;
        }
        final SavingsAccountCharge accountCharge = resolveAccountCharge(account, rule.chargeId());
        if (accountCharge == null) {
            return null;
        }

        final LocalDate transactionDate = withdrawalTransaction.getTransactionDate();
        if (rule.isCumulative()) {
            return this.cumulativeInterestForfeitureService.forfeitIfApplicable(account, withdrawalTransaction, transactionDate,
                    backdatedTxnsAllowedTill, false, percentageOverride);
        }
        validateDailyPostingPeriod(account);

        final LocalDate resolvedSelectedFromDate = selectedFromDate(account, transactionDate, selectedFromDate, rule);
        final LocalDate resolvedSelectedToDate = selectedToDate(transactionDate, selectedToDate, rule);
        if (resolvedSelectedFromDate.isAfter(resolvedSelectedToDate)) {
            throw new GeneralPlatformDomainRuleException("error.msg.advancly.interest.charge.invalid.selected.period",
                    "selectedFromDate cannot be after selectedToDate.");
        }

        final CustomPeriodReapplyPolicy policy = rule.customPeriodReapplyPolicy();
        if (rule.isCustomPeriod() && policy != null && policy.isOncePerSelectedPeriod() && this.applicationRepository
                .countActiveForSelectedPeriod(account.getId(), rule.chargeId(), resolvedSelectedFromDate, resolvedSelectedToDate) > 0) {
            return null;
        }

        final BigDecimal selectedNetInterestAmount = selectedPostedNetInterest(account, resolvedSelectedFromDate, resolvedSelectedToDate);
        if (selectedNetInterestAmount.compareTo(BigDecimal.ZERO) <= 0) {
            return null;
        }

        final BigDecimal alreadyApplied = amountOrZero(this.applicationRepository.sumActiveAppliedAmountForSelectedPeriod(account.getId(),
                rule.chargeId(), resolvedSelectedFromDate, resolvedSelectedToDate));
        final BigDecimal remaining = selectedNetInterestAmount.subtract(alreadyApplied).max(BigDecimal.ZERO);
        if (remaining.compareTo(BigDecimal.ZERO) <= 0) {
            return null;
        }

        final BigDecimal percentage = resolvePercentage(percentageOverride, accountCharge);
        if (percentage == null || percentage.compareTo(BigDecimal.ZERO) <= 0) {
            return null;
        }
        final BigDecimal baseCharge = remaining.multiply(percentage).divide(ONE_HUNDRED, MATH_CONTEXT).max(BigDecimal.ZERO);
        final BigDecimal roundedAmount = InterestChargeMath.roundToCurrency(baseCharge.min(remaining), account.getCurrency());
        if (roundedAmount.compareTo(BigDecimal.ZERO) <= 0) {
            return null;
        }

        final Set<Long> existingTransactionIds = new HashSet<>(account.findCurrentTransactionIdsWithPivotDateConfig());
        final Set<Long> existingReversedTransactionIds = new HashSet<>(account.findCurrentReversedTransactionIdsWithPivotDateConfig());
        final SavingsAccountTransaction chargeTransaction = writeChargeTransaction(account, withdrawalTransaction, accountCharge,
                transactionDate, roundedAmount, backdatedTxnsAllowedTill);

        final DepositInterestChargeApplication application = DepositInterestChargeApplication.createNew(account, accountCharge.getCharge(),
                withdrawalTransaction, chargeTransaction, transactionDate, resolvedSelectedFromDate, resolvedSelectedToDate,
                rule.interestBasisMode(), rule.customPeriodReapplyPolicy(), percentage, selectedNetInterestAmount, alreadyApplied,
                roundedAmount);
        this.applicationRepository.saveAndFlush(application);
        refreshPostedDerivedChargeColumn(account.getId());

        this.savingsAccountRepository.saveAndFlush(account);
        postJournalEntries(account, existingTransactionIds, existingReversedTransactionIds, backdatedTxnsAllowedTill);
        return chargeTransaction;
    }

    private SavingsAccountTransaction writeChargeTransaction(final SavingsAccount account,
            final SavingsAccountTransaction withdrawalTransaction, final SavingsAccountCharge accountCharge,
            final LocalDate transactionDate, final BigDecimal roundedAmount, final boolean backdatedTxnsAllowedTill) {
        final SavingsAccountTransaction chargeTransaction = SavingsAccountTransaction.interestCharge(account, account.office(),
                transactionDate, Money.of(account.getCurrency(), roundedAmount));
        accountCharge.pay(account.getCurrency(), Money.of(account.getCurrency(), chargeTransaction.getAmount()));
        chargeTransaction.getSavingsAccountChargesPaid()
                .add(SavingsAccountChargePaidBy.instance(chargeTransaction, accountCharge, chargeTransaction.getAmount()));
        if (backdatedTxnsAllowedTill) {
            account.addTransactionToExisting(chargeTransaction);
        } else {
            account.addTransaction(chargeTransaction);
        }
        transactionHelper.setRunningBalanceForAppendPath(chargeTransaction, withdrawalTransaction.getRunningBalance(account.getCurrency()),
                account.getCurrency());
        transactionHelper.updateSummaryIncremental(account, chargeTransaction, account.getCurrency());
        this.savingsAccountTransactionRepository.save(chargeTransaction);
        return chargeTransaction;
    }

    private void postJournalEntries(final SavingsAccount account, final Set<Long> existingTransactionIds,
            final Set<Long> existingReversedTransactionIds, final boolean backdatedTxnsAllowedTill) {
        final boolean isAccountTransfer = false;
        final Map<String, Object> accountingBridgeData = account.deriveAccountingBridgeData(account.getCurrency().getCode(),
                existingTransactionIds, existingReversedTransactionIds, isAccountTransfer, backdatedTxnsAllowedTill);
        this.journalEntryWritePlatformService.createJournalEntriesForSavings(accountingBridgeData);
    }

    private void refreshPostedDerivedChargeColumn(final Long accountId) {
        final BigDecimal posted = amountOrZero(this.applicationRepository.sumActiveAppliedAmountForAccount(accountId));
        this.jdbcTemplate.update("update m_savings_account set total_interest_charge_derived = ? where id = ?", posted, accountId);
    }

    private BigDecimal selectedPostedNetInterest(final SavingsAccount account, final LocalDate selectedFromDate,
            final LocalDate selectedToDate) {
        BigDecimal postedInterest = BigDecimal.ZERO;
        BigDecimal withholdingTax = BigDecimal.ZERO;
        for (final SavingsAccountTransaction transaction : account.getTransactions()) {
            if (transaction.isReversalTransaction() || !isWithinSelectedPeriod(transaction, selectedFromDate, selectedToDate)) {
                continue;
            }
            if (transaction.isInterestPostingAndNotReversed()) {
                postedInterest = postedInterest.add(amountOrZero(transaction.getAmount()));
            } else if (transaction.isWithHoldTaxAndNotReversed()) {
                withholdingTax = withholdingTax.add(amountOrZero(transaction.getAmount()));
            }
        }
        return postedInterest.subtract(withholdingTax).max(BigDecimal.ZERO);
    }

    private boolean isWithinSelectedPeriod(final SavingsAccountTransaction transaction, final LocalDate selectedFromDate,
            final LocalDate selectedToDate) {
        final LocalDate transactionDate = transaction.getTransactionDate();
        return transactionDate != null && !transactionDate.isBefore(selectedFromDate) && !transactionDate.isAfter(selectedToDate);
    }

    private void validateDailyPostingPeriod(final SavingsAccount account) {
        if (SavingsPostingInterestPeriodType.fromInt(account.getInterestPostingPeriodType()) != SavingsPostingInterestPeriodType.DAILY) {
            throw new GeneralPlatformDomainRuleException(
                    "error.msg.advancly.savings.account.custom.period.early.withdrawal.interest.charge.requires.daily.posting",
                    "A custom-period early-withdrawal interest charge requires daily interest posting on the account.", account.getId());
        }
    }

    private BigDecimal amountOrZero(final BigDecimal amount) {
        return amount == null ? BigDecimal.ZERO : amount;
    }

    private boolean isRequested(final SavingsAccount account, final SavingsAccountTransaction withdrawalTransaction,
            final boolean applyEarlyWithdrawalCharge) {
        return account.isEarlyWithdrawal(withdrawalTransaction.getTransactionDate()) || applyEarlyWithdrawalCharge;
    }

    private LocalDate selectedFromDate(final SavingsAccount account, final LocalDate transactionDate, final JsonCommand command,
            final AdvanclyChargeInterestRule rule) {
        return selectedFromDate(account, transactionDate, command.localDateValueOfParameterNamed(SELECTED_FROM_DATE), rule);
    }

    private LocalDate selectedFromDate(final SavingsAccount account, final LocalDate transactionDate, final LocalDate selectedFromDate,
            final AdvanclyChargeInterestRule rule) {
        if (rule.isCumulative()) {
            return account.getStartInterestCalculationDate() == null ? transactionDate : account.getStartInterestCalculationDate();
        }
        if (selectedFromDate == null) {
            throw new GeneralPlatformDomainRuleException("error.msg.advancly.interest.charge.selected.from.date.required",
                    "selectedFromDate is required for a custom-period early-withdrawal interest charge.");
        }
        return selectedFromDate;
    }

    private LocalDate selectedToDate(final LocalDate transactionDate, final JsonCommand command, final AdvanclyChargeInterestRule rule) {
        return selectedToDate(transactionDate, command.localDateValueOfParameterNamed(SELECTED_TO_DATE), rule);
    }

    private LocalDate selectedToDate(final LocalDate transactionDate, final LocalDate selectedToDate,
            final AdvanclyChargeInterestRule rule) {
        if (rule.isCumulative()) {
            return transactionDate;
        }
        if (selectedToDate == null) {
            throw new GeneralPlatformDomainRuleException("error.msg.advancly.interest.charge.selected.to.date.required",
                    "selectedToDate is required for a custom-period early-withdrawal interest charge.");
        }
        return selectedToDate;
    }

    private BigDecimal resolvePercentage(final JsonCommand command, final SavingsAccountCharge accountCharge) {
        return resolvePercentage(command.parameterExists(EARLY_WITHDRAWAL_CHARGE_PERCENTAGE)
                ? command.bigDecimalValueOfParameterNamed(EARLY_WITHDRAWAL_CHARGE_PERCENTAGE)
                : null, accountCharge);
    }

    private BigDecimal resolvePercentage(final BigDecimal percentageOverride, final SavingsAccountCharge accountCharge) {
        if (percentageOverride != null) {
            return percentageOverride;
        }
        final BigDecimal accountPercentage = accountCharge.getPercentage();
        if (accountPercentage != null && accountPercentage.compareTo(BigDecimal.ZERO) > 0) {
            return accountPercentage;
        }
        return accountCharge.getCharge().getAmount();
    }

    private void validatePercentageOverride(final BigDecimal percentageOverride) {
        if (percentageOverride != null
                && (percentageOverride.compareTo(BigDecimal.ZERO) < 0 || percentageOverride.compareTo(ONE_HUNDRED) > 0)) {
            throw new GeneralPlatformDomainRuleException("error.msg.savings.account.early.withdrawal.charge.percentage.invalid",
                    "earlyWithdrawalChargePercentage must be between 0 and 100.");
        }
    }

    private AdvanclyChargeInterestRule resolveSingleRule(final SavingsAccount account) {
        final List<AdvanclyChargeInterestRule> rules = this.chargeInterestRuleRepository.findBySavingsProductId(account.productId());
        return rules.size() == 1 ? rules.get(0) : null;
    }

    private SavingsAccountCharge resolveAccountCharge(final SavingsAccount account, final Long chargeId) {
        for (final SavingsAccountCharge accountCharge : account.charges()) {
            if (accountCharge.getCharge() == null || !chargeId.equals(accountCharge.getCharge().getId())) {
                continue;
            }
            if (!accountCharge.isActive() || !accountCharge.isPenaltyCharge()) {
                continue;
            }
            return accountCharge;
        }
        return null;
    }
}
