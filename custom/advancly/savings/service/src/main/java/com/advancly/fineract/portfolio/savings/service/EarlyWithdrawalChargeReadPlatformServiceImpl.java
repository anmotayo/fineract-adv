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

import static com.advancly.fineract.portfolio.savings.AdvanclyInterestChargeApiConstants.EARLY_WITHDRAWAL_CHARGE_PERCENTAGE;
import static com.advancly.fineract.portfolio.savings.AdvanclyInterestChargeApiConstants.FOR_CLOSURE;
import static com.advancly.fineract.portfolio.savings.AdvanclyInterestChargeApiConstants.SELECTED_FROM_DATE;
import static com.advancly.fineract.portfolio.savings.AdvanclyInterestChargeApiConstants.SELECTED_TO_DATE;
import static com.advancly.fineract.portfolio.savings.AdvanclyInterestChargeApiConstants.WITHDRAWAL_DATE;

import com.advancly.fineract.portfolio.savings.data.EarlyWithdrawalChargeData;
import com.advancly.fineract.portfolio.savings.domain.AdvanclyChargeInterestRule;
import com.advancly.fineract.portfolio.savings.domain.DepositInterestChargeApplicationRepository;
import com.advancly.fineract.portfolio.savings.domain.InterestBasisMode;
import com.advancly.fineract.portfolio.savings.domain.InterestChargeMath;
import com.google.gson.JsonElement;
import java.math.BigDecimal;
import java.math.MathContext;
import java.time.LocalDate;
import lombok.RequiredArgsConstructor;
import org.apache.fineract.infrastructure.configuration.domain.ConfigurationDomainService;
import org.apache.fineract.infrastructure.core.api.JsonQuery;
import org.apache.fineract.infrastructure.core.exception.GeneralPlatformDomainRuleException;
import org.apache.fineract.infrastructure.core.serialization.FromJsonHelper;
import org.apache.fineract.infrastructure.core.service.DateUtils;
import org.apache.fineract.infrastructure.security.service.PlatformSecurityContext;
import org.apache.fineract.organisation.monetary.domain.Money;
import org.apache.fineract.organisation.monetary.domain.MoneyHelper;
import org.apache.fineract.portfolio.savings.domain.SavingsAccount;
import org.apache.fineract.portfolio.savings.domain.SavingsAccountAssembler;
import org.apache.fineract.portfolio.savings.domain.SavingsAccountCharge;
import org.apache.fineract.portfolio.savings.domain.SavingsAccountRepositoryWrapper;
import org.springframework.stereotype.Service;

/**
 * Backs {@code POST /savingsaccounts/{accountId}/earlywithdrawalcharge} (see
 * {@link EarlyWithdrawalChargeReadPlatformService}).
 *
 * Deliberately has NO {@code @Transactional} annotation of its own, for the same reason
 * {@link InterestCalculationReadPlatformServiceImpl} does not: {@code findOneWithNotFoundDetection(Long)} opens and
 * commits its own transaction, detaching the account before this class touches it, so every mutation this class makes
 * afterwards (posting the interest on its in-memory copy) happens on an object nothing is tracking for a flush. This is
 * what makes it safe to call this endpoint as often as needed without ever charging or posting anything for real.
 *
 * Cumulative mode quotes what the real exit charges, by doing what it does to the copy: force-post the interest on the
 * date {@link CumulativeInterestForfeitureService#forcedPostingDate} picks (skipped when a posting already sits on that
 * date), then take the percentage of the interest posted net of the tax posted with it, less what was already
 * forfeited. {@code forClosure} selects the date a closure posts on (the closure date, which accrues through the day
 * before it) instead of the one an ordinary withdrawal uses (the day before the withdrawal). Custom-period mode reuses
 * {@link AdvanclyInterestChargeApplicationService#selectedPostedNetInterest} on the interest posted in the period: for
 * a withdrawal only what has really posted, for a closure (which posts its closing interest before it charges) what the
 * closure would have posted. Both rules report {@code accountBalanceAfterPosting}, the balance once that posting is in,
 * which a closure pays out of.
 *
 * An optional {@code earlyWithdrawalChargePercentage} in the request body is threaded through to the same resolution
 * the real withdrawal uses ({@code resolveQualifyingChargeWithPercentage(...)} / {@code resolvePercentage(...)}, both
 * of which already take an override), so the preview and the charge that actually gets applied agree on the percentage.
 */
@Service
@RequiredArgsConstructor
public class EarlyWithdrawalChargeReadPlatformServiceImpl implements EarlyWithdrawalChargeReadPlatformService {

    private final PlatformSecurityContext context;
    private final SavingsAccountRepositoryWrapper savingsAccountRepositoryWrapper;
    private final SavingsAccountAssembler savingsAccountAssembler;
    private final ConfigurationDomainService configurationDomainService;
    private final FromJsonHelper fromJsonHelper;
    private final CumulativeInterestForfeitureService cumulativeInterestForfeitureService;
    private final AdvanclyInterestChargeApplicationService advanclyInterestChargeApplicationService;
    private final DepositInterestChargeApplicationRepository interestChargeApplicationRepository;

    @Override
    public EarlyWithdrawalChargeData calculate(final Long savingsAccountId, final JsonQuery query) {
        final SavingsAccount account = this.savingsAccountRepositoryWrapper.findOneWithNotFoundDetection(savingsAccountId);
        this.savingsAccountAssembler.setHelpers(account);
        this.context.authenticatedUser().validateHasReadPermission(account.depositAccountType().resourceName());

        final JsonElement element = this.fromJsonHelper.parse(query.json());
        final LocalDate withdrawalDate = this.fromJsonHelper.extractLocalDateNamed(WITHDRAWAL_DATE, element);
        validateWithdrawalDate(withdrawalDate);
        final BigDecimal percentageOverride = this.fromJsonHelper.extractBigDecimalWithLocaleNamed(EARLY_WITHDRAWAL_CHARGE_PERCENTAGE,
                element);
        this.advanclyInterestChargeApplicationService.validatePercentageOverride(percentageOverride);
        final LocalDate selectedFromDateParam = this.fromJsonHelper.extractLocalDateNamed(SELECTED_FROM_DATE, element);
        final LocalDate selectedToDateParam = this.fromJsonHelper.extractLocalDateNamed(SELECTED_TO_DATE, element);

        final AdvanclyChargeInterestRule rule = this.advanclyInterestChargeApplicationService.resolveSingleRule(account);
        if (rule == null) {
            return EarlyWithdrawalChargeData.notApplicable(account.getId(), withdrawalDate,
                    "No early-withdrawal interest-charge rule is configured for this account's product.");
        }

        final boolean forClosure = Boolean.TRUE.equals(this.fromJsonHelper.extractBooleanNamed(FOR_CLOSURE, element));
        return rule.isCumulative() ? calculateCumulative(account, withdrawalDate, percentageOverride, forClosure)
                : calculateCustomPeriod(account, withdrawalDate, rule, selectedFromDateParam, selectedToDateParam, percentageOverride,
                        forClosure);
    }

    private void validateWithdrawalDate(final LocalDate withdrawalDate) {
        if (withdrawalDate == null) {
            throw new GeneralPlatformDomainRuleException("error.msg.advancly.early.withdrawal.charge.withdrawal.date.required",
                    "withdrawalDate is required.");
        }
        final LocalDate businessDate = DateUtils.getBusinessLocalDate();
        if (withdrawalDate.isAfter(businessDate)) {
            throw new GeneralPlatformDomainRuleException("error.msg.advancly.early.withdrawal.charge.withdrawal.date.in.future",
                    "withdrawalDate cannot be after the business date.", withdrawalDate, businessDate);
        }
    }

    private EarlyWithdrawalChargeData calculateCumulative(final SavingsAccount account, final LocalDate withdrawalDate,
            final BigDecimal percentageOverride, final boolean forClosure) {
        final CumulativeInterestForfeitureService.QualifyingCharge qualifying = this.cumulativeInterestForfeitureService
                .resolveQualifyingChargeWithPercentage(account, percentageOverride);
        if (qualifying == null) {
            return EarlyWithdrawalChargeData.notApplicable(account.getId(), withdrawalDate,
                    "No active penalty charge qualifies for this account's cumulative early-withdrawal rule.");
        }

        // Do what the real exit does, so the quote is the charge: force-post the interest on the same date, then take
        // the
        // percentage of the interest posted net of the tax posted with it. A closure dates that posting on the closure
        // date (the accrual through the day before it), an ordinary withdrawal the day before the withdrawal.
        final LocalDate basisDate = this.cumulativeInterestForfeitureService.forcedPostingDate(account, withdrawalDate, forClosure);
        if (this.cumulativeInterestForfeitureService.latestInterestPostingOn(account, basisDate) == null) {
            postInterestOn(account, basisDate,
                    forClosure ? account.interestPostingUpToForClosure(withdrawalDate) : DateUtils.getBusinessLocalDate());
        }
        account.refreshSummary(false);

        final BigDecimal alreadyForfeited = this.cumulativeInterestForfeitureService.sumActiveAppliedAmount(account.getId());
        final BigDecimal totalInterestPosted = zeroIfNull(account.getSummary().getTotalInterestPosted());
        final BigDecimal totalWithholdTax = zeroIfNull(account.getSummary().getTotalWithholdTax());
        final BigDecimal basisAmount = totalInterestPosted.subtract(totalWithholdTax).subtract(alreadyForfeited).max(BigDecimal.ZERO);
        // Rounded to the currency, as the forfeiture transaction is when it is written, so the quote is the amount
        // charged.
        final BigDecimal chargeAmount = Money.of(account.getCurrency(), CumulativeForfeitureCalculator.forfeitureAmount(totalInterestPosted,
                totalWithholdTax, alreadyForfeited, qualifying.percentage())).getAmount();

        final SavingsAccountCharge accountCharge = qualifying.accountCharge();
        return EarlyWithdrawalChargeData.applicable(account.getId(), withdrawalDate, InterestBasisMode.CUMULATIVE.name(),
                accountCharge.getCharge().getId(), accountCharge.getCharge().getName(), qualifying.percentage(), basisAmount, chargeAmount,
                null, null, account.getSummary().getAccountBalance());
    }

    private EarlyWithdrawalChargeData calculateCustomPeriod(final SavingsAccount account, final LocalDate withdrawalDate,
            final AdvanclyChargeInterestRule rule, final LocalDate selectedFromDateParam, final LocalDate selectedToDateParam,
            final BigDecimal percentageOverride, final boolean forClosure) {
        final SavingsAccountCharge accountCharge = this.advanclyInterestChargeApplicationService.resolveAccountCharge(account,
                rule.chargeId());
        if (accountCharge == null) {
            return EarlyWithdrawalChargeData.notApplicable(account.getId(), withdrawalDate,
                    "No active penalty charge qualifies for this account's custom-period early-withdrawal rule.");
        }
        this.advanclyInterestChargeApplicationService.validateDailyPostingPeriod(account);

        // A closure posts its closing interest before it takes the charge, so the interest posted in the period is what
        // the
        // closure will see, not just what the daily postings have reached; the withdrawal of a period only charges what
        // is
        // posted. The balance after that posting is what the closure pays out of.
        BigDecimal accountBalanceAfterPosting = null;
        if (forClosure) {
            final LocalDate postingDate = account.interestPostingTransactionDateForClosure(withdrawalDate);
            if (this.cumulativeInterestForfeitureService.latestInterestPostingOn(account, postingDate) == null) {
                postInterestOn(account, postingDate, account.interestPostingUpToForClosure(withdrawalDate));
            }
            account.refreshSummary(false);
            accountBalanceAfterPosting = account.getSummary().getAccountBalance();
        }

        final LocalDate resolvedSelectedFromDate = this.advanclyInterestChargeApplicationService.selectedFromDate(account, withdrawalDate,
                selectedFromDateParam, rule);
        final LocalDate resolvedSelectedToDate = this.advanclyInterestChargeApplicationService.selectedToDate(withdrawalDate,
                selectedToDateParam, rule);
        if (resolvedSelectedFromDate.isAfter(resolvedSelectedToDate)) {
            throw new GeneralPlatformDomainRuleException("error.msg.advancly.interest.charge.invalid.selected.period",
                    "selectedFromDate cannot be after selectedToDate.");
        }

        if (rule.isCustomPeriod() && rule.customPeriodReapplyPolicy() != null && rule.customPeriodReapplyPolicy().isOncePerSelectedPeriod()
                && this.interestChargeApplicationRepository.countActiveForSelectedPeriod(account.getId(), rule.chargeId(),
                        resolvedSelectedFromDate, resolvedSelectedToDate) > 0) {
            return EarlyWithdrawalChargeData.notApplicable(account.getId(), withdrawalDate,
                    "A charge has already been applied for this selected period under a once-per-selected-period policy.",
                    accountBalanceAfterPosting);
        }

        final BigDecimal selectedNetInterestAmount = this.advanclyInterestChargeApplicationService.selectedPostedNetInterest(account,
                resolvedSelectedFromDate, resolvedSelectedToDate);
        final BigDecimal alreadyApplied = zeroIfNull(this.interestChargeApplicationRepository.sumActiveAppliedAmountForSelectedPeriod(
                account.getId(), rule.chargeId(), resolvedSelectedFromDate, resolvedSelectedToDate));
        final BigDecimal remaining = selectedNetInterestAmount.subtract(alreadyApplied).max(BigDecimal.ZERO);

        final BigDecimal percentage = this.advanclyInterestChargeApplicationService.resolvePercentage(percentageOverride, accountCharge);
        if (percentage == null || percentage.compareTo(BigDecimal.ZERO) <= 0 || remaining.compareTo(BigDecimal.ZERO) <= 0) {
            return EarlyWithdrawalChargeData.notApplicable(account.getId(), withdrawalDate,
                    "No net interest remains available to charge for the selected period.", accountBalanceAfterPosting);
        }

        final BigDecimal baseCharge = InterestChargeMath.recomputedChargeAmount(remaining, percentage);
        final BigDecimal chargeAmount = InterestChargeMath.roundToCurrency(baseCharge, account.getCurrency());

        return EarlyWithdrawalChargeData.applicable(account.getId(), withdrawalDate, rule.interestBasisMode().name(),
                accountCharge.getCharge().getId(), accountCharge.getCharge().getName(), percentage, remaining, chargeAmount,
                resolvedSelectedFromDate, resolvedSelectedToDate, accountBalanceAfterPosting);
    }

    /**
     * Posts the interest on {@code postingDate} the way a real exit does, on this detached copy of the account only, so
     * the interest and the tax it posts can be read back from the summary.
     */
    private void postInterestOn(final SavingsAccount account, final LocalDate postingDate, final LocalDate interestPostingUpToDate) {
        final MathContext mc = new MathContext(10, MoneyHelper.getRoundingMode());
        final boolean isSavingsInterestPostingAtCurrentPeriodEnd = this.configurationDomainService
                .isSavingsInterestPostingAtCurrentPeriodEnd();
        final Integer financialYearBeginningMonth = this.configurationDomainService.retrieveFinancialYearBeginningMonth();
        final boolean isInterestTransfer = false;
        final boolean backdatedTxnsAllowedTill = false;
        final boolean postReversals = false;
        account.postInterest(mc, interestPostingUpToDate, isInterestTransfer, isSavingsInterestPostingAtCurrentPeriodEnd,
                financialYearBeginningMonth, postingDate, backdatedTxnsAllowedTill, postReversals);
    }

    private static BigDecimal zeroIfNull(final BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }
}
