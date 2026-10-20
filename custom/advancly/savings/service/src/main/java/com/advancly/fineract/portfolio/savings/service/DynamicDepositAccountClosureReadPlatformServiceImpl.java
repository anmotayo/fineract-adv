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
import static com.advancly.fineract.portfolio.savings.DynamicDepositApiConstants.DYNAMIC_DEPOSIT_ACCOUNT_RESOURCE_NAME;

import com.advancly.fineract.portfolio.savings.data.DynamicDepositClosureData;
import com.advancly.fineract.portfolio.savings.domain.DynamicDepositAccount;
import com.google.gson.JsonElement;
import com.google.gson.reflect.TypeToken;
import java.lang.reflect.Type;
import java.math.BigDecimal;
import java.math.MathContext;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.apache.fineract.infrastructure.configuration.domain.ConfigurationDomainService;
import org.apache.fineract.infrastructure.core.api.JsonQuery;
import org.apache.fineract.infrastructure.core.data.ApiParameterError;
import org.apache.fineract.infrastructure.core.data.DataValidatorBuilder;
import org.apache.fineract.infrastructure.core.data.EnumOptionData;
import org.apache.fineract.infrastructure.core.exception.GeneralPlatformDomainRuleException;
import org.apache.fineract.infrastructure.core.exception.PlatformApiDataValidationException;
import org.apache.fineract.infrastructure.core.serialization.FromJsonHelper;
import org.apache.fineract.infrastructure.core.service.DateUtils;
import org.apache.fineract.infrastructure.security.service.PlatformSecurityContext;
import org.apache.fineract.organisation.monetary.domain.Money;
import org.apache.fineract.organisation.monetary.domain.MoneyHelper;
import org.apache.fineract.portfolio.account.data.PortfolioAccountData;
import org.apache.fineract.portfolio.account.service.AccountAssociationsReadPlatformService;
import org.apache.fineract.portfolio.paymenttype.data.PaymentTypeData;
import org.apache.fineract.portfolio.paymenttype.service.PaymentTypeReadPlatformService;
import org.apache.fineract.portfolio.savings.DepositAccountOnClosureType;
import org.apache.fineract.portfolio.savings.DepositAccountType;
import org.apache.fineract.portfolio.savings.DepositsApiConstants;
import org.apache.fineract.portfolio.savings.data.SavingsAccountData;
import org.apache.fineract.portfolio.savings.domain.SavingsAccount;
import org.apache.fineract.portfolio.savings.domain.SavingsAccountAssembler;
import org.apache.fineract.portfolio.savings.domain.SavingsAccountRepositoryWrapper;
import org.apache.fineract.portfolio.savings.domain.SavingsAccountSummary;
import org.apache.fineract.portfolio.savings.domain.SavingsAccountTransaction;
import org.apache.fineract.portfolio.savings.domain.SavingsAccountTransactionComparator;
import org.apache.fineract.portfolio.savings.service.SavingsAccountReadPlatformService;
import org.apache.fineract.portfolio.savings.service.SavingsEnumerations;
import org.springframework.stereotype.Service;

/**
 * Previews a Dynamic Deposit closure by running the closure's own settlement on an in-memory copy of the account: the
 * closing interest is posted - withholding tax and all - and the early-withdrawal forfeiture is taken from it with the
 * same {@link CumulativeForfeitureCalculator} the real closure uses, so the figure shown is the figure paid out.
 *
 * Deliberately has NO {@code @Transactional} of its own, for the same reason
 * {@link EarlyWithdrawalChargeReadPlatformServiceImpl} does not: {@code findOneWithNotFoundDetection(Long)} opens and
 * commits its own transaction, detaching the account before this class touches it, so nothing this class does to the
 * account is ever flushed.
 */
@Service
@RequiredArgsConstructor
public class DynamicDepositAccountClosureReadPlatformServiceImpl implements DynamicDepositAccountClosureReadPlatformService {

    private static final Set<String> PRE_MATURE_CALCULATION_REQUEST_DATA_PARAMETERS = new HashSet<>(
            Arrays.asList(DepositsApiConstants.localeParamName, DepositsApiConstants.dateFormatParamName,
                    DepositsApiConstants.closedOnDateParamName, EARLY_WITHDRAWAL_CHARGE_PERCENTAGE));

    /** A premature closure, like a Fixed or Recurring Deposit's, cannot reinvest. */
    private static final DepositAccountOnClosureType[] PRE_MATURE_CLOSURE_OPTIONS = { DepositAccountOnClosureType.WITHDRAW_DEPOSIT,
            DepositAccountOnClosureType.TRANSFER_TO_SAVINGS };
    private static final DepositAccountOnClosureType[] MATURITY_CLOSURE_OPTIONS = { DepositAccountOnClosureType.WITHDRAW_DEPOSIT,
            DepositAccountOnClosureType.TRANSFER_TO_SAVINGS, DepositAccountOnClosureType.REINVEST_PRINCIPAL_AND_INTEREST,
            DepositAccountOnClosureType.REINVEST_PRINCIPAL_ONLY };

    private final PlatformSecurityContext context;
    private final SavingsAccountRepositoryWrapper savingsAccountRepositoryWrapper;
    private final SavingsAccountAssembler savingsAccountAssembler;
    private final ConfigurationDomainService configurationDomainService;
    private final FromJsonHelper fromJsonHelper;
    private final CumulativeInterestForfeitureService cumulativeInterestForfeitureService;
    private final AdvanclyInterestChargeApplicationService advanclyInterestChargeApplicationService;
    private final PaymentTypeReadPlatformService paymentTypeReadPlatformService;
    private final SavingsAccountReadPlatformService savingsAccountReadPlatformService;
    private final AccountAssociationsReadPlatformService accountAssociationsReadPlatformService;

    @Override
    public DynamicDepositClosureData retrieveClosureTemplate(final Long accountId) {
        final DynamicDepositAccount account = loadAccount(accountId);
        final LocalDate businessDate = DateUtils.getBusinessLocalDate();
        // Closing is only allowed from maturity, so show what the customer receives then - projected to the maturity
        // date while the account has not reached it, today's figure once it has.
        final LocalDate maturityDate = account.maturityDate();
        final LocalDate asOfDate = maturityDate != null && businessDate.isBefore(maturityDate) ? maturityDate : businessDate;
        return simulate(account, asOfDate, null, MATURITY_CLOSURE_OPTIONS);
    }

    @Override
    public DynamicDepositClosureData calculatePrematureAmount(final Long accountId, final JsonQuery query) {
        final DynamicDepositAccount account = loadAccount(accountId);

        final Type typeOfMap = new TypeToken<Map<String, Object>>() {}.getType();
        this.fromJsonHelper.checkForUnsupportedParameters(typeOfMap, query.json(), PRE_MATURE_CALCULATION_REQUEST_DATA_PARAMETERS);
        final JsonElement element = this.fromJsonHelper.parse(query.json());
        final LocalDate closedOnDate = this.fromJsonHelper.extractLocalDateNamed(DepositsApiConstants.closedOnDateParamName, element);
        final BigDecimal percentageOverride = this.fromJsonHelper.extractBigDecimalWithLocaleNamed(EARLY_WITHDRAWAL_CHARGE_PERCENTAGE,
                element);
        validatePrematureClosureDate(account, closedOnDate);
        this.advanclyInterestChargeApplicationService.validatePercentageOverride(percentageOverride);

        return simulate(account, closedOnDate, percentageOverride, PRE_MATURE_CLOSURE_OPTIONS);
    }

    private DynamicDepositAccount loadAccount(final Long accountId) {
        final SavingsAccount account = this.savingsAccountRepositoryWrapper.findOneWithNotFoundDetection(accountId);
        this.savingsAccountAssembler.setHelpers(account);
        this.context.authenticatedUser().validateHasReadPermission(DYNAMIC_DEPOSIT_ACCOUNT_RESOURCE_NAME);
        if (!(account instanceof DynamicDepositAccount dynamicDepositAccount)) {
            throw new GeneralPlatformDomainRuleException("error.msg.dynamicdepositaccount.closure.not.a.dynamic.deposit",
                    "Account " + accountId + " is not a Dynamic Deposit account.", accountId);
        }
        return dynamicDepositAccount;
    }

    private void validatePrematureClosureDate(final DynamicDepositAccount account, final LocalDate closedOnDate) {
        final List<ApiParameterError> dataValidationErrors = new ArrayList<>();
        final DataValidatorBuilder baseDataValidator = new DataValidatorBuilder(dataValidationErrors)
                .resource(DYNAMIC_DEPOSIT_ACCOUNT_RESOURCE_NAME + DepositsApiConstants.preMatureCloseAction);
        baseDataValidator.reset().parameter(DepositsApiConstants.closedOnDateParamName).value(closedOnDate).notNull();
        if (closedOnDate != null) {
            if (DateUtils.isBefore(closedOnDate, account.getActivationDate())) {
                baseDataValidator.reset().parameter(DepositsApiConstants.closedOnDateParamName).value(closedOnDate)
                        .failWithCode("must.be.after.activation.date");
            } else if (DateUtils.isAfterBusinessDate(closedOnDate)) {
                baseDataValidator.reset().parameter(DepositsApiConstants.closedOnDateParamName).value(closedOnDate)
                        .failWithCode("cannot.be.a.future.date");
            } else if (!account.isEarlyWithdrawal(closedOnDate)) {
                baseDataValidator.reset().parameter(DepositsApiConstants.closedOnDateParamName).value(closedOnDate)
                        .failWithCode("must.be.before.maturity.date");
            }
        }
        if (!dataValidationErrors.isEmpty()) {
            throw new PlatformApiDataValidationException(dataValidationErrors);
        }
    }

    private DynamicDepositClosureData simulate(final DynamicDepositAccount account, final LocalDate closedOnDate,
            final BigDecimal percentageOverride, final DepositAccountOnClosureType[] closureOptions) {
        final boolean earlyClosure = account.isEarlyWithdrawal(closedOnDate);
        final CumulativeInterestForfeitureService.QualifyingCharge qualifying = earlyClosure
                ? this.cumulativeInterestForfeitureService.resolveQualifyingChargeWithPercentage(account, percentageOverride)
                : null;
        if (qualifying != null && DateUtils.isBefore(closedOnDate, DateUtils.getBusinessLocalDate())) {
            // The closure itself refuses this (see DynamicDepositAccount#beginClosureSettlement); a preview that
            // promised a payout the closure then refuses would be worse than no preview.
            throw new GeneralPlatformDomainRuleException(
                    "error.msg.savings.account.close.backdated.cumulative.early.withdrawal.not.allowed",
                    "Backdated cumulative early-withdrawal closure is not allowed.");
        }

        final SavingsAccountSummary summary = account.getSummary();
        final BigDecimal principal = zeroIfNull(summary.getAccountBalance());
        final BigDecimal interestPostedBefore = zeroIfNull(summary.getTotalInterestPosted());

        // The closure's settlement, on this in-memory copy: the closing interest posts (net of withholding tax).
        final MathContext mc = new MathContext(10, MoneyHelper.getRoundingMode());
        account.postInterest(mc, closedOnDate, false, this.configurationDomainService.isSavingsInterestPostingAtCurrentPeriodEnd(),
                this.configurationDomainService.retrieveFinancialYearBeginningMonth(),
                account.interestPostingTransactionDateForClosure(closedOnDate), false, false);
        // A closure also writes tax configured to be withheld at maturity - unless a cumulative forfeiture settles it,
        // which
        // takes its own course - so the preview must too, or it would show more than the closure pays.
        if (qualifying == null) {
            account.withholdMaturityTaxAtClosure(account.interestPostingTransactionDateForClosure(closedOnDate));
        }

        final BigDecimal totalInterestPosted = zeroIfNull(summary.getTotalInterestPosted());
        final BigDecimal withholdTax = zeroIfNull(summary.getTotalWithholdTax());
        BigDecimal forfeiture = BigDecimal.ZERO;
        if (qualifying != null) {
            final BigDecimal alreadyForfeited = this.cumulativeInterestForfeitureService.sumActiveAppliedAmount(account.getId());
            forfeiture = Money.of(account.getCurrency(), CumulativeForfeitureCalculator.forfeitureAmount(totalInterestPosted, withholdTax,
                    alreadyForfeited, qualifying.percentage())).getAmount();
        }
        final BigDecimal payout = zeroIfNull(summary.getAccountBalance()).subtract(forfeiture);

        final Collection<EnumOptionData> onAccountClosureOptions = SavingsEnumerations.depositAccountOnClosureType(closureOptions);
        final Collection<PaymentTypeData> paymentTypeOptions = this.paymentTypeReadPlatformService.retrieveAllPaymentTypes();
        final Collection<SavingsAccountData> savingsAccounts = this.savingsAccountReadPlatformService
                .retrieveActiveForLookup(account.clientId(), DepositAccountType.SAVINGS_DEPOSIT);

        final PortfolioAccountData linkedAccount = this.accountAssociationsReadPlatformService
                .retriveSavingsLinkedAssociation(account.getId());

        return new DynamicDepositClosureData(account.getId(), closedOnDate, account.maturityDate(), earlyClosure, principal,
                investedAmountOf(account), totalInterestPosted.subtract(interestPostedBefore), withholdTax, qualifying != null, forfeiture,
                qualifying == null ? null : qualifying.percentage(),
                qualifying == null ? null : qualifying.accountCharge().getCharge().getId(),
                qualifying == null ? null : qualifying.accountCharge().getCharge().getName(), payout, onAccountClosureOptions,
                paymentTypeOptions, savingsAccounts, linkedAccount == null ? null : linkedAccount.getId(),
                linkedAccount == null ? null : linkedAccount.getAccountNo());
    }

    /**
     * The principal invested as the account stands after the closing settlement: what a principal-only reinvestment
     * carries.
     */
    private static BigDecimal investedAmountOf(final DynamicDepositAccount account) {
        final List<SavingsAccountTransaction> transactions = account.getTransactions();
        if (transactions.isEmpty()) {
            return BigDecimal.ZERO;
        }
        final SavingsAccountTransaction last = transactions.stream().max(new SavingsAccountTransactionComparator()).orElseThrow();
        return DynamicDepositInvestedAmountCalculator
                .investedAmountAsOf(transactions, account.getCurrency(), account.interestTransferTransactionIds(), last)
                .max(BigDecimal.ZERO);
    }

    private static BigDecimal zeroIfNull(final BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }
}
