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

import static com.advancly.fineract.portfolio.savings.DynamicDepositApiConstants.DYNAMIC_DEPOSIT_ACCOUNT_RESOURCE_NAME;
import static com.advancly.fineract.portfolio.savings.DynamicDepositApiConstants.allowWithdrawalParamName;
import static com.advancly.fineract.portfolio.savings.DynamicDepositApiConstants.dynamicRateEnabledParamName;
import static org.apache.fineract.portfolio.savings.DepositsApiConstants.depositAmountParamName;
import static org.apache.fineract.portfolio.savings.DepositsApiConstants.depositPeriodFrequencyIdParamName;
import static org.apache.fineract.portfolio.savings.DepositsApiConstants.depositPeriodParamName;
import static org.apache.fineract.portfolio.savings.DepositsApiConstants.expectedFirstDepositOnDateParamName;
import static org.apache.fineract.portfolio.savings.DepositsApiConstants.transferInterestToSavingsParamName;
import static org.apache.fineract.portfolio.savings.SavingsApiConstants.accountNoParamName;
import static org.apache.fineract.portfolio.savings.SavingsApiConstants.clientIdParamName;
import static org.apache.fineract.portfolio.savings.SavingsApiConstants.externalIdParamName;
import static org.apache.fineract.portfolio.savings.SavingsApiConstants.fieldOfficerIdParamName;
import static org.apache.fineract.portfolio.savings.SavingsApiConstants.groupIdParamName;
import static org.apache.fineract.portfolio.savings.SavingsApiConstants.interestCalculationDaysInYearTypeParamName;
import static org.apache.fineract.portfolio.savings.SavingsApiConstants.interestCalculationTypeParamName;
import static org.apache.fineract.portfolio.savings.SavingsApiConstants.interestCompoundingPeriodTypeParamName;
import static org.apache.fineract.portfolio.savings.SavingsApiConstants.interestPostingPeriodTypeParamName;
import static org.apache.fineract.portfolio.savings.SavingsApiConstants.lockinPeriodFrequencyParamName;
import static org.apache.fineract.portfolio.savings.SavingsApiConstants.lockinPeriodFrequencyTypeParamName;
import static org.apache.fineract.portfolio.savings.SavingsApiConstants.minRequiredOpeningBalanceParamName;
import static org.apache.fineract.portfolio.savings.SavingsApiConstants.nominalAnnualInterestRateParamName;
import static org.apache.fineract.portfolio.savings.SavingsApiConstants.productIdParamName;
import static org.apache.fineract.portfolio.savings.SavingsApiConstants.submittedOnDateParamName;
import static org.apache.fineract.portfolio.savings.SavingsApiConstants.withHoldTaxParamName;
import static org.apache.fineract.portfolio.savings.SavingsApiConstants.withdrawalFeeForTransfersParamName;

import com.advancly.fineract.portfolio.savings.validator.DynamicDepositAccountDataValidator;
import com.google.gson.JsonElement;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Set;
import org.apache.fineract.infrastructure.core.api.JsonCommand;
import org.apache.fineract.infrastructure.core.service.ExternalIdFactory;
import org.apache.fineract.organisation.staff.domain.Staff;
import org.apache.fineract.organisation.staff.domain.StaffRepositoryWrapper;
import org.apache.fineract.portfolio.accountdetails.domain.AccountType;
import org.apache.fineract.portfolio.client.domain.Client;
import org.apache.fineract.portfolio.client.domain.ClientRepositoryWrapper;
import org.apache.fineract.portfolio.client.exception.ClientNotActiveException;
import org.apache.fineract.portfolio.group.domain.Group;
import org.apache.fineract.portfolio.group.domain.GroupRepositoryWrapper;
import org.apache.fineract.portfolio.group.exception.CenterNotActiveException;
import org.apache.fineract.portfolio.group.exception.ClientNotInGroupException;
import org.apache.fineract.portfolio.group.exception.GroupNotActiveException;
import org.apache.fineract.portfolio.interestratechart.domain.InterestRateChart;
import org.apache.fineract.portfolio.savings.DepositAccountType;
import org.apache.fineract.portfolio.savings.SavingsCompoundingInterestPeriodType;
import org.apache.fineract.portfolio.savings.SavingsInterestCalculationDaysInYearType;
import org.apache.fineract.portfolio.savings.SavingsInterestCalculationType;
import org.apache.fineract.portfolio.savings.SavingsPeriodFrequencyType;
import org.apache.fineract.portfolio.savings.SavingsPostingInterestPeriodType;
import org.apache.fineract.portfolio.savings.domain.DepositAccountInterestRateChart;
import org.apache.fineract.portfolio.savings.domain.DepositAccountTermAndPreClosure;
import org.apache.fineract.portfolio.savings.domain.DepositPreClosureDetail;
import org.apache.fineract.portfolio.savings.domain.DepositTermDetail;
import org.apache.fineract.portfolio.savings.domain.SavingsAccountCharge;
import org.apache.fineract.portfolio.savings.domain.SavingsAccountChargeAssembler;
import org.apache.fineract.portfolio.savings.domain.SavingsAccountTransactionSummaryWrapper;
import org.apache.fineract.portfolio.savings.domain.SavingsHelper;
import org.apache.fineract.portfolio.savings.exception.SavingsProductNotFoundException;
import org.springframework.stereotype.Component;

/**
 * Builds a {@link DynamicDepositAccount} from a submission {@link JsonCommand}. Structurally mirrors
 * {@code DepositAccountAssembler#assembleFrom}'s fixed-deposit branch, implementing the account-creation resolution
 * rules from the implementation plan (section 3):
 *
 * <ol>
 * <li>Read the product's {@link DepositProductDynamicDetail}.</li>
 * <li>{@code allowWithdrawal} / {@code dynamicRateEnabled}: use the request value if present, else inherit from the
 * product.</li>
 * <li>Persist the resolved values into {@link DepositAccountDynamicDetail}.</li>
 * <li>Validate {@code allowWithdrawal = false} => {@code transferInterestToSavings = false} (business rule 8).</li>
 * <li>Resolve the initial interest rate: the request's {@code nominalAnnualInterestRate} if present, otherwise the
 * interest rate chart (via {@link DepositAccountInterestRateChart#getApplicableInterestRate}) using the initial
 * invested amount and fixed tenor - this chart fallback applies regardless of {@code dynamicRateEnabled} (business rule
 * 6) - falling back further to the product's flat nominal rate if no chart is configured.</li>
 * </ol>
 */
@Component
public class DynamicDepositAccountAssembler {

    private final ClientRepositoryWrapper clientRepository;
    private final GroupRepositoryWrapper groupRepository;
    private final StaffRepositoryWrapper staffRepository;
    private final DynamicDepositProductRepository dynamicDepositProductRepository;
    private final SavingsAccountChargeAssembler savingsAccountChargeAssembler;
    private final SavingsAccountTransactionSummaryWrapper savingsAccountTransactionSummaryWrapper;
    private final SavingsHelper savingsHelper;
    private final ExternalIdFactory externalIdFactory;
    private final DynamicDepositAccountDataValidator dynamicDepositAccountDataValidator;

    public DynamicDepositAccountAssembler(final ClientRepositoryWrapper clientRepository, final GroupRepositoryWrapper groupRepository,
            final StaffRepositoryWrapper staffRepository, final DynamicDepositProductRepository dynamicDepositProductRepository,
            final SavingsAccountChargeAssembler savingsAccountChargeAssembler,
            final SavingsAccountTransactionSummaryWrapper savingsAccountTransactionSummaryWrapper, final SavingsHelper savingsHelper,
            final ExternalIdFactory externalIdFactory, final DynamicDepositAccountDataValidator dynamicDepositAccountDataValidator) {
        this.clientRepository = clientRepository;
        this.groupRepository = groupRepository;
        this.staffRepository = staffRepository;
        this.dynamicDepositProductRepository = dynamicDepositProductRepository;
        this.savingsAccountChargeAssembler = savingsAccountChargeAssembler;
        this.savingsAccountTransactionSummaryWrapper = savingsAccountTransactionSummaryWrapper;
        this.savingsHelper = savingsHelper;
        this.externalIdFactory = externalIdFactory;
        this.dynamicDepositAccountDataValidator = dynamicDepositAccountDataValidator;
    }

    public DynamicDepositAccount assembleFrom(final JsonCommand command) {

        final JsonElement element = command.parsedJson();

        final String accountNo = command.stringValueOfParameterNamed(accountNoParamName);
        final String externalId = command.stringValueOfParameterNamed(externalIdParamName);
        final Long productId = command.longValueOfParameterNamed(productIdParamName);

        final DynamicDepositProduct product = this.dynamicDepositProductRepository.findById(productId)
                .orElseThrow(() -> new SavingsProductNotFoundException(productId));

        Client client = null;
        Group group = null;
        AccountType accountType = AccountType.INVALID;
        final Long clientId = command.longValueOfParameterNamed(clientIdParamName);
        if (clientId != null) {
            client = this.clientRepository.findOneWithNotFoundDetection(clientId);
            accountType = AccountType.INDIVIDUAL;
            if (client.isNotActive()) {
                throw new ClientNotActiveException(clientId);
            }
        }

        final Long groupId = command.longValueOfParameterNamed(groupIdParamName);
        if (groupId != null) {
            group = this.groupRepository.findOneWithNotFoundDetection(groupId);
            accountType = AccountType.GROUP;
        }

        if (group != null && client != null) {
            if (!group.hasClientAsMember(client)) {
                throw new ClientNotInGroupException(clientId, groupId);
            }
            accountType = AccountType.JLG;
            if (group.isNotActive()) {
                if (group.isCenter()) {
                    throw new CenterNotActiveException(groupId);
                }
                throw new GroupNotActiveException(groupId);
            }
        }

        Staff fieldOfficer = null;
        final Long fieldOfficerId = command.longValueOfParameterNamed(fieldOfficerIdParamName);
        if (fieldOfficerId != null) {
            fieldOfficer = this.staffRepository.findOneWithNotFoundDetection(fieldOfficerId);
        }

        final LocalDate submittedOnDate = command.localDateValueOfParameterNamed(submittedOnDateParamName);

        final SavingsCompoundingInterestPeriodType interestCompoundingPeriodType = resolveOrDefault(
                command.integerValueOfParameterNamed(interestCompoundingPeriodTypeParamName), product.interestCompoundingPeriodType());
        final SavingsPostingInterestPeriodType interestPostingPeriodType = resolveOrDefault(
                command.integerValueOfParameterNamed(interestPostingPeriodTypeParamName), product.interestPostingPeriodType());
        final SavingsInterestCalculationType interestCalculationType = resolveOrDefault(
                command.integerValueOfParameterNamed(interestCalculationTypeParamName), product.interestCalculationType());
        final SavingsInterestCalculationDaysInYearType interestCalculationDaysInYearType = resolveOrDefault(
                command.integerValueOfParameterNamed(interestCalculationDaysInYearTypeParamName),
                product.interestCalculationDaysInYearType());

        final BigDecimal minRequiredOpeningBalance = command.parameterExists(minRequiredOpeningBalanceParamName)
                ? command.bigDecimalValueOfParameterNamed(minRequiredOpeningBalanceParamName)
                : product.minRequiredOpeningBalance();

        final Integer lockinPeriodFrequency = command.parameterExists(lockinPeriodFrequencyParamName)
                ? command.integerValueOfParameterNamed(lockinPeriodFrequencyParamName)
                : product.lockinPeriodFrequency();
        final SavingsPeriodFrequencyType lockinPeriodFrequencyType = command.parameterExists(lockinPeriodFrequencyTypeParamName)
                ? SavingsPeriodFrequencyType.fromInt(command.integerValueOfParameterNamed(lockinPeriodFrequencyTypeParamName))
                : product.lockinPeriodFrequencyType();

        final boolean withdrawalFeeApplicableForTransfer = command
                .booleanPrimitiveValueOfParameterNamed(withdrawalFeeForTransfersParamName);

        final Set<SavingsAccountCharge> charges = this.savingsAccountChargeAssembler.fromParsedJson(element, product.currency().getCode(),
                DepositAccountType.DYNAMIC_DEPOSIT);

        boolean withHoldTax = product.withHoldTax();
        if (command.parameterExists(withHoldTaxParamName)) {
            withHoldTax = command.booleanPrimitiveValueOfParameterNamed(withHoldTaxParamName);
        }

        // --- dynamic-detail inheritance (plan section 3, steps 1-3) ---
        final boolean allowWithdrawal = command.parameterExists(allowWithdrawalParamName)
                ? command.booleanPrimitiveValueOfParameterNamed(allowWithdrawalParamName)
                : product.isAllowWithdrawal();
        final boolean dynamicRateEnabled = command.parameterExists(dynamicRateEnabledParamName)
                ? command.booleanPrimitiveValueOfParameterNamed(dynamicRateEnabledParamName)
                : product.isDynamicRateEnabled();

        final boolean transferInterestToSavings = command.booleanPrimitiveValueOfParameterNamed(transferInterestToSavingsParamName);

        // --- business rule 8 (plan section 3, step 5) ---
        this.dynamicDepositAccountDataValidator.validateAllowWithdrawalTransferInterestRule(allowWithdrawal, transferInterestToSavings);

        final BigDecimal depositAmount = command.bigDecimalValueOfParameterNamed(depositAmountParamName);
        final Integer depositPeriod = command.integerValueOfParameterNamed(depositPeriodParamName);
        final Integer depositPeriodFrequencyId = command.integerValueOfParameterNamed(depositPeriodFrequencyIdParamName);
        final SavingsPeriodFrequencyType depositPeriodFrequency = SavingsPeriodFrequencyType.fromInt(depositPeriodFrequencyId);
        final LocalDate expectedFirstDepositOnDate = command.localDateValueOfParameterNamed(expectedFirstDepositOnDateParamName);

        // --- account-level interest rate chart snapshot (this correction): captured once, here, at submission time -
        // so a later edit to the product's chart never retroactively changes the rate an already-open account
        // resolves against. Both the initial rate resolution below and every later re-resolution
        // (DynamicDepositRateResolutionService, via DynamicDepositRateHistoryService) read this same snapshot, never
        // the product's chart directly.
        final InterestRateChart productChart = product.applicableChart(submittedOnDate);
        final DepositAccountInterestRateChart accountChart = productChart == null ? null
                : DepositAccountInterestRateChart.from(productChart);

        // --- initial interest rate resolution (plan section 3, step 6) ---
        BigDecimal interestRate = command.bigDecimalValueOfParameterNamed(nominalAnnualInterestRateParamName);
        if (interestRate == null) {
            interestRate = resolveInitialRateFromChart(product, accountChart, depositAmount, submittedOnDate, depositPeriod,
                    depositPeriodFrequency);
        }

        final DepositPreClosureDetail preClosureDetail = DepositPreClosureDetail.createFrom(false, null, null);
        final DepositTermDetail depositTermDetail = DepositTermDetail.createFrom(depositPeriod, depositPeriod, depositPeriodFrequency,
                depositPeriodFrequency, null, null);
        final DepositAccountTermAndPreClosure accountTermAndPreClosure = DepositAccountTermAndPreClosure.createNew(preClosureDetail,
                depositTermDetail, null, depositAmount, null, null, depositPeriod, depositPeriodFrequency, expectedFirstDepositOnDate, null,
                transferInterestToSavings, null, null);

        final DynamicDepositAccount account = DynamicDepositAccount.createNewApplicationForSubmittal(client, group, product, fieldOfficer,
                accountNo, this.externalIdFactory.create(externalId), accountType, submittedOnDate, null, interestRate,
                interestCompoundingPeriodType, interestPostingPeriodType, interestCalculationType, interestCalculationDaysInYearType,
                minRequiredOpeningBalance, lockinPeriodFrequency, lockinPeriodFrequencyType, withdrawalFeeApplicableForTransfer, charges,
                accountTermAndPreClosure, accountChart, withHoldTax);

        accountTermAndPreClosure.updateAccountReference(account);

        final DepositAccountDynamicDetail dynamicDetail = DepositAccountDynamicDetail.createNew(account, allowWithdrawal,
                dynamicRateEnabled);
        account.setDynamicDetail(dynamicDetail);

        account.setHelpers(this.savingsAccountTransactionSummaryWrapper, this.savingsHelper);
        account.validateNewApplicationState(DYNAMIC_DEPOSIT_ACCOUNT_RESOURCE_NAME);

        return account;
    }

    /**
     * Resolves the initial rate from the account's own interest rate chart snapshot (business rule 6: this fallback
     * applies regardless of {@code dynamicRateEnabled}), using the initial invested amount and a simple additive
     * estimate of the fixed tenor's end date. Falls back to the product's flat nominal rate when no chart is
     * configured. This is deliberately a lean, initial-rate-only resolution for Phase 1 - the full
     * transaction-history-based dynamic rate re-resolution (business rule 7) is Phase 2/3 work (dynamic rate history +
     * interest engine).
     */
    private BigDecimal resolveInitialRateFromChart(final DynamicDepositProduct product, final DepositAccountInterestRateChart accountChart,
            final BigDecimal depositAmount, final LocalDate submittedOnDate, final Integer depositPeriod,
            final SavingsPeriodFrequencyType depositPeriodFrequency) {
        if (accountChart == null) {
            return product.nominalAnnualInterestRate();
        }

        final LocalDate estimatedMaturityDate = addPeriod(submittedOnDate, depositPeriod, depositPeriodFrequency);
        final BigDecimal resolvedRate = accountChart.getApplicableInterestRate(depositAmount, submittedOnDate, estimatedMaturityDate, null);
        if (resolvedRate == null || resolvedRate.compareTo(BigDecimal.ZERO) == 0) {
            return product.nominalAnnualInterestRate();
        }
        return resolvedRate;
    }

    private LocalDate addPeriod(final LocalDate from, final Integer period, final SavingsPeriodFrequencyType frequencyType) {
        if (from == null || period == null || frequencyType == null) {
            return from;
        }
        switch (frequencyType) {
            case DAYS:
                return from.plusDays(period);
            case WEEKS:
                return from.plusWeeks(period);
            case MONTHS:
                return from.plusMonths(period);
            case YEARS:
                return from.plusYears(period);
            default:
                return from;
        }
    }

    private SavingsCompoundingInterestPeriodType resolveOrDefault(final Integer value,
            final SavingsCompoundingInterestPeriodType fallback) {
        return value != null ? SavingsCompoundingInterestPeriodType.fromInt(value) : fallback;
    }

    private SavingsPostingInterestPeriodType resolveOrDefault(final Integer value, final SavingsPostingInterestPeriodType fallback) {
        return value != null ? SavingsPostingInterestPeriodType.fromInt(value) : fallback;
    }

    private SavingsInterestCalculationType resolveOrDefault(final Integer value, final SavingsInterestCalculationType fallback) {
        return value != null ? SavingsInterestCalculationType.fromInt(value) : fallback;
    }

    private SavingsInterestCalculationDaysInYearType resolveOrDefault(final Integer value,
            final SavingsInterestCalculationDaysInYearType fallback) {
        return value != null ? SavingsInterestCalculationDaysInYearType.fromInt(value) : fallback;
    }
}
