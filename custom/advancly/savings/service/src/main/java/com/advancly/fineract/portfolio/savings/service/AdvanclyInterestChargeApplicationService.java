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
import com.advancly.fineract.portfolio.savings.domain.DynamicDepositAccount;
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

    public AdvanclyInterestChargeApplicationService(final AdvanclyChargeInterestRuleRepository chargeInterestRuleRepository,
            final DepositInterestChargeApplicationRepository applicationRepository,
            final SavingsAccountTransactionRepository savingsAccountTransactionRepository,
            final SavingsAccountRepositoryWrapper savingsAccountRepository, final SavingsAccountTransactionHelper transactionHelper,
            final JournalEntryWritePlatformService journalEntryWritePlatformService,
            @Lazy final CumulativeInterestForfeitureService cumulativeInterestForfeitureService) {
        this.chargeInterestRuleRepository = chargeInterestRuleRepository;
        this.applicationRepository = applicationRepository;
        this.savingsAccountTransactionRepository = savingsAccountTransactionRepository;
        this.savingsAccountRepository = savingsAccountRepository;
        this.transactionHelper = transactionHelper;
        this.journalEntryWritePlatformService = journalEntryWritePlatformService;
        this.cumulativeInterestForfeitureService = cumulativeInterestForfeitureService;
    }

    public boolean hasChargeDrivenRule(final SavingsAccount account) {
        return resolveSingleRule(account) != null;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public SavingsAccountTransaction applyIfApplicable(final SavingsAccount account, final SavingsAccountTransaction withdrawalTransaction,
            final JsonCommand command, final boolean appendPath, final boolean backdatedTxnsAllowedTill) {
        final boolean applyEarlyWithdrawalCharge = command.booleanPrimitiveValueOfParameterNamed(APPLY_EARLY_WITHDRAWAL_CHARGE);
        final BigDecimal percentageOverride = command.parameterExists(EARLY_WITHDRAWAL_CHARGE_PERCENTAGE)
                ? command.bigDecimalValueOfParameterNamed(EARLY_WITHDRAWAL_CHARGE_PERCENTAGE)
                : null;
        final LocalDate selectedFromDate = command.localDateValueOfParameterNamed(SELECTED_FROM_DATE);
        final LocalDate selectedToDate = command.localDateValueOfParameterNamed(SELECTED_TO_DATE);
        return applyIfApplicable(account, withdrawalTransaction, applyEarlyWithdrawalCharge, percentageOverride, selectedFromDate,
                selectedToDate, appendPath, backdatedTxnsAllowedTill);
    }

    /**
     * {@code appendPath}/{@code backdatedTxnsAllowedTill} identify the transaction collection the withdrawal used; the
     * charge must use the same one or {@code deriveAccountingBridgeData} won't see it.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public SavingsAccountTransaction applyIfApplicable(final SavingsAccount account, final SavingsAccountTransaction withdrawalTransaction,
            final boolean applyEarlyWithdrawalCharge, final BigDecimal percentageOverride, final LocalDate selectedFromDate,
            final LocalDate selectedToDate, final boolean appendPath, final boolean backdatedTxnsAllowedTill) {
        return applyIfApplicable(account, withdrawalTransaction, applyEarlyWithdrawalCharge, percentageOverride, selectedFromDate,
                selectedToDate, appendPath, backdatedTxnsAllowedTill, false);
    }

    /** {@code postAtClosureDate} (cumulative only): date the forced posting on transactionDate, not the day before. */
    @Transactional(propagation = Propagation.MANDATORY)
    public SavingsAccountTransaction applyIfApplicable(final SavingsAccount account, final SavingsAccountTransaction withdrawalTransaction,
            final boolean applyEarlyWithdrawalCharge, final BigDecimal percentageOverride, final LocalDate selectedFromDate,
            final LocalDate selectedToDate, final boolean appendPath, final boolean backdatedTxnsAllowedTill,
            final boolean postAtClosureDate) {
        if (withdrawalTransaction == null || withdrawalTransaction.isReversed()
                || !isRequested(account, withdrawalTransaction, applyEarlyWithdrawalCharge)
                || isSettledByClosure(account, withdrawalTransaction)) {
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
                    appendPath || backdatedTxnsAllowedTill, postAtClosureDate, percentageOverride);
        }
        final CustomPeriodCharge charge = customPeriodCharge(account, rule, accountCharge, transactionDate, selectedFromDate,
                selectedToDate, percentageOverride);
        if (charge == null) {
            return null;
        }
        final LocalDate resolvedSelectedFromDate = charge.selectedFromDate();
        final LocalDate resolvedSelectedToDate = charge.selectedToDate();
        final BigDecimal percentage = charge.percentage();
        final BigDecimal selectedNetInterestAmount = charge.selectedNetInterestAmount();
        final BigDecimal alreadyApplied = charge.alreadyApplied();
        final BigDecimal roundedAmount = charge.amount();

        final Set<Long> existingTransactionIds = new HashSet<>(account.findCurrentTransactionIdsWithPivotDateConfig());
        final Set<Long> existingReversedTransactionIds = new HashSet<>(account.findCurrentReversedTransactionIdsWithPivotDateConfig());
        final SavingsAccountTransaction chargeTransaction = writeChargeTransaction(account, withdrawalTransaction, accountCharge,
                transactionDate, roundedAmount, appendPath, backdatedTxnsAllowedTill);

        final DepositInterestChargeApplication application = DepositInterestChargeApplication.createNew(account, accountCharge.getCharge(),
                withdrawalTransaction, chargeTransaction, transactionDate, resolvedSelectedFromDate, resolvedSelectedToDate,
                rule.interestBasisMode(), rule.customPeriodReapplyPolicy(), percentage, selectedNetInterestAmount, alreadyApplied,
                roundedAmount);
        this.applicationRepository.saveAndFlush(application);

        this.savingsAccountRepository.saveAndFlush(account);
        postJournalEntries(account, existingTransactionIds, existingReversedTransactionIds, appendPath || backdatedTxnsAllowedTill);
        return chargeTransaction;
    }

    /** Read-only, so a closure can size its payout before the charge is applied. */
    private CustomPeriodCharge customPeriodCharge(final SavingsAccount account, final AdvanclyChargeInterestRule rule,
            final SavingsAccountCharge accountCharge, final LocalDate transactionDate, final LocalDate selectedFromDate,
            final LocalDate selectedToDate, final BigDecimal percentageOverride) {
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
        return new CustomPeriodCharge(percentage, selectedNetInterestAmount, alreadyApplied, roundedAmount, resolvedSelectedFromDate,
                resolvedSelectedToDate);
    }

    private record CustomPeriodCharge(BigDecimal percentage, BigDecimal selectedNetInterestAmount, BigDecimal alreadyApplied,
            BigDecimal amount, LocalDate selectedFromDate, LocalDate selectedToDate) {
    }

    /** Read-only quote. For a cumulative rule the caller must already have posted the closing interest. */
    public BigDecimal quoteClosureCharge(final SavingsAccount account, final LocalDate closedDate, final boolean applyEarlyWithdrawalCharge,
            final BigDecimal percentageOverride, final LocalDate selectedFromDate, final LocalDate selectedToDate) {
        if (!(account.isEarlyWithdrawal(closedDate) || applyEarlyWithdrawalCharge)) {
            return BigDecimal.ZERO;
        }
        validatePercentageOverride(percentageOverride);
        final AdvanclyChargeInterestRule rule = resolveSingleRule(account);
        if (rule == null) {
            return BigDecimal.ZERO;
        }
        final SavingsAccountCharge accountCharge = resolveAccountCharge(account, rule.chargeId());
        if (accountCharge == null) {
            return BigDecimal.ZERO;
        }
        if (rule.isCumulative()) {
            final CumulativeInterestForfeitureService.QualifyingCharge qualifying = this.cumulativeInterestForfeitureService
                    .resolveQualifyingChargeWithPercentage(account, percentageOverride);
            if (qualifying == null) {
                return BigDecimal.ZERO;
            }
            final BigDecimal amount = CumulativeForfeitureCalculator.forfeitureAmount(account.getSummary().getTotalInterestPosted(),
                    account.getSummary().getTotalWithholdTax(),
                    this.cumulativeInterestForfeitureService.sumActiveAppliedAmount(account.getId()), qualifying.percentage());
            return Money.of(account.getCurrency(), amount).getAmount();
        }
        final CustomPeriodCharge charge = customPeriodCharge(account, rule, accountCharge, closedDate, selectedFromDate, selectedToDate,
                percentageOverride);
        return charge == null ? BigDecimal.ZERO : charge.amount();
    }

    private SavingsAccountTransaction writeChargeTransaction(final SavingsAccount account,
            final SavingsAccountTransaction withdrawalTransaction, final SavingsAccountCharge accountCharge,
            final LocalDate transactionDate, final BigDecimal roundedAmount, final boolean appendPath,
            final boolean backdatedTxnsAllowedTill) {
        final SavingsAccountTransaction chargeTransaction = SavingsAccountTransaction.interestCharge(account, account.office(),
                transactionDate, Money.of(account.getCurrency(), roundedAmount));
        // accountCharge is a reusable rule row (outstanding pinned at 0), so refresh its outstanding before paying it.
        accountCharge.payExternallyComputedCharge(account.getCurrency(), roundedAmount);
        chargeTransaction.getSavingsAccountChargesPaid()
                .add(SavingsAccountChargePaidBy.instance(chargeTransaction, accountCharge, chargeTransaction.getAmount()));
        // Use the same collection the withdrawal was added to.
        if (appendPath) {
            account.addTransactionToExisting(chargeTransaction);
        } else if (backdatedTxnsAllowedTill) {
            account.addTransactionToExisting(chargeTransaction);
        } else {
            account.addTransaction(chargeTransaction);
        }
        // Close the withdrawal's open-ended balance window, or balance windows would overlap.
        transactionHelper.updatePreviousTransactionBalanceEndDate(withdrawalTransaction, transactionDate, account.getCurrency());
        transactionHelper.setRunningBalanceForAppendPath(chargeTransaction, withdrawalTransaction.getRunningBalance(account.getCurrency()),
                account.getCurrency());
        transactionHelper.updateSummaryIncremental(account, chargeTransaction, account.getCurrency());
        this.savingsAccountTransactionRepository.save(chargeTransaction);
        return chargeTransaction;
    }

    private void postJournalEntries(final SavingsAccount account, final Set<Long> existingTransactionIds,
            final Set<Long> existingReversedTransactionIds, final boolean appendPath) {
        final boolean isAccountTransfer = false;
        final Map<String, Object> accountingBridgeData = account.deriveAccountingBridgeData(account.getCurrency().getCode(),
                existingTransactionIds, existingReversedTransactionIds, isAccountTransfer, appendPath);
        this.journalEntryWritePlatformService.createJournalEntriesForSavings(accountingBridgeData);
    }

    /** Also used by {@code EarlyWithdrawalChargeReadPlatformServiceImpl}. */
    BigDecimal selectedPostedNetInterest(final SavingsAccount account, final LocalDate selectedFromDate, final LocalDate selectedToDate) {
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

    /** Also used by {@code EarlyWithdrawalChargeReadPlatformServiceImpl}. */
    void validateDailyPostingPeriod(final SavingsAccount account) {
        if (SavingsPostingInterestPeriodType.fromInt(account.getInterestPostingPeriodType()) != SavingsPostingInterestPeriodType.DAILY) {
            throw new GeneralPlatformDomainRuleException(
                    "error.msg.advancly.savings.account.custom.period.early.withdrawal.interest.charge.requires.daily.posting",
                    "A custom-period early-withdrawal interest charge requires daily interest posting on the account.", account.getId());
        }
    }

    private BigDecimal amountOrZero(final BigDecimal amount) {
        return amount == null ? BigDecimal.ZERO : amount;
    }

    /** See {@code DynamicDepositAccount#beginClosureSettlement}. */
    private boolean isSettledByClosure(final SavingsAccount account, final SavingsAccountTransaction withdrawalTransaction) {
        return account instanceof DynamicDepositAccount dynamicDepositAccount
                && dynamicDepositAccount.isClosureSettledOn(withdrawalTransaction.getTransactionDate());
    }

    private boolean isRequested(final SavingsAccount account, final SavingsAccountTransaction withdrawalTransaction,
            final boolean applyEarlyWithdrawalCharge) {
        return account.isEarlyWithdrawal(withdrawalTransaction.getTransactionDate()) || applyEarlyWithdrawalCharge;
    }

    private LocalDate selectedFromDate(final SavingsAccount account, final LocalDate transactionDate, final JsonCommand command,
            final AdvanclyChargeInterestRule rule) {
        return selectedFromDate(account, transactionDate, command.localDateValueOfParameterNamed(SELECTED_FROM_DATE), rule);
    }

    /** Also used by {@code EarlyWithdrawalChargeReadPlatformServiceImpl}. */
    LocalDate selectedFromDate(final SavingsAccount account, final LocalDate transactionDate, final LocalDate selectedFromDate,
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

    /** Also used by {@code EarlyWithdrawalChargeReadPlatformServiceImpl}. */
    LocalDate selectedToDate(final LocalDate transactionDate, final LocalDate selectedToDate, final AdvanclyChargeInterestRule rule) {
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

    /** Also used by {@code EarlyWithdrawalChargeReadPlatformServiceImpl}. */
    BigDecimal resolvePercentage(final BigDecimal percentageOverride, final SavingsAccountCharge accountCharge) {
        if (percentageOverride != null) {
            return percentageOverride;
        }
        final BigDecimal accountPercentage = accountCharge.getPercentage();
        if (accountPercentage != null && accountPercentage.compareTo(BigDecimal.ZERO) > 0) {
            return accountPercentage;
        }
        return accountCharge.getCharge().getAmount();
    }

    /** Also used by {@code EarlyWithdrawalChargeReadPlatformServiceImpl}. */
    void validatePercentageOverride(final BigDecimal percentageOverride) {
        if (percentageOverride != null
                && (percentageOverride.compareTo(BigDecimal.ZERO) < 0 || percentageOverride.compareTo(ONE_HUNDRED) > 0)) {
            throw new GeneralPlatformDomainRuleException("error.msg.savings.account.early.withdrawal.charge.percentage.invalid",
                    "earlyWithdrawalChargePercentage must be between 0 and 100.");
        }
    }

    /** Also used by {@code EarlyWithdrawalChargeReadPlatformServiceImpl}. */
    AdvanclyChargeInterestRule resolveSingleRule(final SavingsAccount account) {
        final List<AdvanclyChargeInterestRule> rules = this.chargeInterestRuleRepository.findBySavingsProductId(account.productId());
        return rules.size() == 1 ? rules.get(0) : null;
    }

    /** Also used by {@code EarlyWithdrawalChargeReadPlatformServiceImpl}. */
    SavingsAccountCharge resolveAccountCharge(final SavingsAccount account, final Long chargeId) {
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
