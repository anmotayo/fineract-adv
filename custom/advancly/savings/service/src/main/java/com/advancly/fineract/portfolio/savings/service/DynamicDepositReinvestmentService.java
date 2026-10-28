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

import static com.advancly.fineract.portfolio.savings.DynamicDepositApiConstants.allowWithdrawalParamName;
import static com.advancly.fineract.portfolio.savings.DynamicDepositApiConstants.dynamicRateEnabledParamName;
import static com.advancly.fineract.portfolio.savings.DynamicDepositApiConstants.linkedAccountParamName;
import static com.advancly.fineract.portfolio.savings.DynamicDepositApiConstants.withHoldTaxPostingTypeIdParamName;

import com.advancly.fineract.portfolio.savings.domain.DynamicDepositAccount;
import com.advancly.fineract.portfolio.savings.domain.DynamicDepositAccountAssembler;
import com.advancly.fineract.portfolio.savings.domain.DynamicDepositAccountRepository;
import com.google.gson.JsonObject;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.HashSet;
import org.apache.fineract.infrastructure.accountnumberformat.domain.AccountNumberFormat;
import org.apache.fineract.infrastructure.accountnumberformat.domain.AccountNumberFormatRepositoryWrapper;
import org.apache.fineract.infrastructure.accountnumberformat.domain.EntityAccountType;
import org.apache.fineract.infrastructure.core.api.JsonCommand;
import org.apache.fineract.infrastructure.core.serialization.FromJsonHelper;
import org.apache.fineract.portfolio.account.data.PortfolioAccountData;
import org.apache.fineract.portfolio.account.domain.AccountAssociationType;
import org.apache.fineract.portfolio.account.domain.AccountAssociations;
import org.apache.fineract.portfolio.account.domain.AccountAssociationsRepository;
import org.apache.fineract.portfolio.account.service.AccountAssociationsReadPlatformService;
import org.apache.fineract.portfolio.account.service.AccountNumberGenerator;
import org.apache.fineract.portfolio.savings.DepositAccountType;
import org.apache.fineract.portfolio.savings.data.DepositAccountDataValidator;
import org.apache.fineract.portfolio.savings.domain.DepositAccountAssembler;
import org.apache.fineract.portfolio.savings.domain.DepositAccountTermAndPreClosure;
import org.apache.fineract.portfolio.savings.domain.SavingsAccount;
import org.apache.fineract.portfolio.savings.service.SavingsAccountWritePlatformService;
import org.apache.fineract.useradministration.domain.AppUser;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Opens the Dynamic Deposit a closing Dynamic Deposit is reinvested into - the counterpart of
 * {@code FixedDepositAccount#reInvest}.
 *
 * The new account is not hand-built: the closing account's terms are rewritten as the request that would have submitted
 * it and run through {@link DynamicDepositAccountAssembler#assembleFrom}, so everything a normal submission enforces
 * applies to a reinvestment too - the product's amount and term range, the rate resolved from the product's chart for
 * the NEW amount (a dynamic deposit's rate depends on how much is invested, so it is deliberately not copied), the
 * early-withdrawal charge the product maps, the maturity date. It is then approved and activated on the closure date,
 * and its opening deposit is credited exactly as an activation credits it, so the money that left the closing account
 * arrives in the new one.
 *
 * The opening deposit is never funded from the linked savings account here (as activation does): the funds are the
 * closing account's own payout. The link itself is carried over, so interest transfers keep working.
 */
@Service
public class DynamicDepositReinvestmentService {

    private static final String ISO_DATE = "yyyy-MM-dd";

    private final DynamicDepositAccountAssembler dynamicDepositAccountAssembler;
    private final DynamicDepositAccountRepository dynamicDepositAccountRepository;
    private final AccountNumberGenerator accountNumberGenerator;
    private final AccountNumberFormatRepositoryWrapper accountNumberFormatRepository;
    private final DepositAccountAssembler depositAccountAssembler;
    private final DepositAccountDataValidator depositAccountDataValidator;
    private final AccountAssociationsRepository accountAssociationsRepository;
    private final AccountAssociationsReadPlatformService accountAssociationsReadPlatformService;
    private final SavingsAccountWritePlatformService savingsAccountWritePlatformService;
    private final FromJsonHelper fromJsonHelper;
    private final DynamicDepositMaturityAmountService maturityAmountService;

    @Autowired
    public DynamicDepositReinvestmentService(final DynamicDepositAccountAssembler dynamicDepositAccountAssembler,
            final DynamicDepositAccountRepository dynamicDepositAccountRepository, final AccountNumberGenerator accountNumberGenerator,
            final AccountNumberFormatRepositoryWrapper accountNumberFormatRepository, final DepositAccountAssembler depositAccountAssembler,
            final DepositAccountDataValidator depositAccountDataValidator,
            final AccountAssociationsRepository accountAssociationsRepository,
            final AccountAssociationsReadPlatformService accountAssociationsReadPlatformService,
            @Lazy final SavingsAccountWritePlatformService savingsAccountWritePlatformService, final FromJsonHelper fromJsonHelper,
            final DynamicDepositMaturityAmountService maturityAmountService) {
        this.dynamicDepositAccountAssembler = dynamicDepositAccountAssembler;
        this.dynamicDepositAccountRepository = dynamicDepositAccountRepository;
        this.accountNumberGenerator = accountNumberGenerator;
        this.accountNumberFormatRepository = accountNumberFormatRepository;
        this.depositAccountAssembler = depositAccountAssembler;
        this.depositAccountDataValidator = depositAccountDataValidator;
        this.accountAssociationsRepository = accountAssociationsRepository;
        this.accountAssociationsReadPlatformService = accountAssociationsReadPlatformService;
        this.savingsAccountWritePlatformService = savingsAccountWritePlatformService;
        this.fromJsonHelper = fromJsonHelper;
        this.maturityAmountService = maturityAmountService;
    }

    /**
     * @param closing
     *            the account being closed; only read
     * @param amount
     *            what is reinvested - becomes the new account's opening deposit
     * @param activationDate
     *            the closure date: the new account is submitted, approved and activated on it
     * @return the new, active account
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public DynamicDepositAccount reinvest(final DynamicDepositAccount closing, final BigDecimal amount, final LocalDate activationDate,
            final AppUser user) {
        final JsonCommand submission = JsonCommand.fromJsonElement(null, submissionOf(closing, amount, activationDate),
                this.fromJsonHelper);
        final DynamicDepositAccount reinvested = this.dynamicDepositAccountAssembler.assembleFrom(submission);
        this.dynamicDepositAccountRepository.saveAndFlush(reinvested);

        if (reinvested.isAccountNumberRequiresAutoGeneration()) {
            final AccountNumberFormat accountNumberFormat = this.accountNumberFormatRepository.findByAccountType(EntityAccountType.SAVINGS);
            reinvested.updateAccountNo(this.accountNumberGenerator.generate(reinvested, accountNumberFormat));
        }

        final Long linkedAccountId = submission.longValueOfParameterNamed(linkedAccountParamName);
        if (linkedAccountId != null) {
            final SavingsAccount linkedAccount = this.depositAccountAssembler.assembleFrom(linkedAccountId,
                    DepositAccountType.SAVINGS_DEPOSIT);
            this.depositAccountDataValidator.validatelinkedSavingsAccount(linkedAccount, reinvested);
            this.accountAssociationsRepository.save(AccountAssociations.associateSavingsAccount(reinvested, linkedAccount,
                    AccountAssociationType.LINKED_ACCOUNT_ASSOCIATION.getValue(), true));
        }

        reinvested.approveAndActivateApplication(activationDate, user);
        // Re-anchor the maturity date on the activation date, as activating a Dynamic Deposit does.
        reinvested.updateMaturityDate();
        this.savingsAccountWritePlatformService.processPostActiveActions(reinvested, DateTimeFormatter.ofPattern(ISO_DATE), new HashSet<>(),
                new HashSet<>());
        this.maturityAmountService.storeProjectedMaturityAmount(reinvested);
        return this.dynamicDepositAccountRepository.saveAndFlush(reinvested);
    }

    private JsonObject submissionOf(final DynamicDepositAccount closing, final BigDecimal amount, final LocalDate submittedOnDate) {
        final DepositAccountTermAndPreClosure term = closing.accountTermAndPreClosure();
        final JsonObject json = new JsonObject();
        json.addProperty("locale", "en");
        json.addProperty("dateFormat", ISO_DATE);
        if (closing.clientId() != null) {
            json.addProperty("clientId", closing.clientId());
        }
        if (closing.groupId() != null) {
            json.addProperty("groupId", closing.groupId());
        }
        json.addProperty("productId", closing.productId());
        if (closing.getSavingsOfficer() != null) {
            json.addProperty("fieldOfficerId", closing.getSavingsOfficer().getId());
        }
        json.addProperty("submittedOnDate", submittedOnDate.format(DateTimeFormatter.ofPattern(ISO_DATE)));
        json.addProperty("depositAmount", amount);
        json.addProperty("depositPeriod", term.depositPeriod());
        json.addProperty("depositPeriodFrequencyId", term.depositPeriodFrequency());
        // The rate is deliberately not copied: it is resolved from the product's chart for the new amount.
        json.addProperty("interestCompoundingPeriodType", closing.getInterestCompoundingPeriodType());
        json.addProperty("interestPostingPeriodType", closing.getInterestPostingPeriodType());
        json.addProperty("interestCalculationType", closing.getInterestCalculationType());
        json.addProperty("interestCalculationDaysInYearType", closing.getInterestCalculationDaysInYearType());
        if (closing.getLockinPeriodFrequency() != null) {
            json.addProperty("lockinPeriodFrequency", closing.getLockinPeriodFrequency());
            json.addProperty("lockinPeriodFrequencyType", closing.getLockinPeriodFrequencyType());
        }
        json.addProperty("withdrawalFeeForTransfers", closing.isWithdrawalFeeApplicableForTransfer());
        json.addProperty("withHoldTax", closing.isWithHoldTax());
        if (term.getWithHoldTaxPostingType() != null) {
            json.addProperty(withHoldTaxPostingTypeIdParamName, term.getWithHoldTaxPostingType());
        }
        json.addProperty("transferInterestToSavings", term.isTransferInterestToLinkedAccount());
        if (closing.dynamicDetail() != null) {
            json.addProperty(allowWithdrawalParamName, closing.dynamicDetail().isAllowWithdrawal());
            json.addProperty(dynamicRateEnabledParamName, closing.dynamicDetail().isDynamicRateEnabled());
        }
        final PortfolioAccountData linkedAccount = this.accountAssociationsReadPlatformService
                .retriveSavingsLinkedAssociation(closing.getId());
        if (linkedAccount != null) {
            json.addProperty(linkedAccountParamName, linkedAccount.getId());
        }
        return json;
    }
}
