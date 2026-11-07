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
import static org.apache.fineract.portfolio.savings.DepositsApiConstants.toSavingsAccountIdParamName;
import static org.apache.fineract.portfolio.savings.DepositsApiConstants.transferDescriptionParamName;

import com.advancly.fineract.portfolio.account.service.AdvanclyAccountTransfersWritePlatformService;
import com.advancly.fineract.portfolio.account.service.AdvanclyAccountTransfersWritePlatformService.ClosureCharge;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.reflect.TypeToken;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.lang.reflect.Type;
import java.math.BigDecimal;
import java.math.MathContext;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.apache.fineract.accounting.journalentry.service.JournalEntryWritePlatformService;
import org.apache.fineract.infrastructure.configuration.domain.ConfigurationDomainService;
import org.apache.fineract.infrastructure.core.api.JsonCommand;
import org.apache.fineract.infrastructure.core.data.CommandProcessingResult;
import org.apache.fineract.infrastructure.core.data.CommandProcessingResultBuilder;
import org.apache.fineract.infrastructure.core.domain.ExternalId;
import org.apache.fineract.infrastructure.core.exception.GeneralPlatformDomainRuleException;
import org.apache.fineract.infrastructure.core.serialization.FromJsonHelper;
import org.apache.fineract.infrastructure.core.service.DateUtils;
import org.apache.fineract.infrastructure.security.service.PlatformSecurityContext;
import org.apache.fineract.organisation.monetary.domain.MoneyHelper;
import org.apache.fineract.portfolio.account.PortfolioAccountType;
import org.apache.fineract.portfolio.account.data.AccountTransferDTO;
import org.apache.fineract.portfolio.account.domain.AccountTransferType;
import org.apache.fineract.portfolio.savings.SavingsApiConstants;
import org.apache.fineract.portfolio.savings.domain.SavingsAccount;
import org.apache.fineract.portfolio.savings.domain.SavingsAccountAssembler;
import org.apache.fineract.portfolio.savings.domain.SavingsAccountRepositoryWrapper;
import org.apache.fineract.portfolio.savings.domain.SavingsAccountTransaction;
import org.apache.fineract.portfolio.savings.domain.SavingsAccountTransactionRepository;
import org.apache.fineract.portfolio.savings.service.SavingsAccountPostInterestService;
import org.apache.fineract.portfolio.savings.service.SavingsAccountWritePlatformService;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Closes a plain savings account and transfers the balance less the early-withdrawal charge in one transaction, so the
 * payout is sized from the exact balance the charge is taken from. Each phase expects a freshly loaded account, so the
 * persistence context is flushed and cleared between phases.
 */
@Service
@RequiredArgsConstructor
public class AdvanclySavingsClosureTransferService {

    private static final Set<String> CLOSE_WITH_TRANSFER_REQUEST_DATA_PARAMETERS = new HashSet<>(Arrays.asList(
            SavingsApiConstants.localeParamName, SavingsApiConstants.dateFormatParamName, SavingsApiConstants.closedOnDateParamName,
            SavingsApiConstants.noteParamName, SavingsApiConstants.paymentTypeIdParamName, SavingsApiConstants.withdrawBalanceParamName,
            SavingsApiConstants.transactionAccountNumberParamName, SavingsApiConstants.checkNumberParamName,
            SavingsApiConstants.routingCodeParamName, SavingsApiConstants.receiptNumberParamName, SavingsApiConstants.bankNumberParamName,
            SavingsApiConstants.postInterestValidationOnClosure, APPLY_EARLY_WITHDRAWAL_CHARGE, toSavingsAccountIdParamName,
            transferDescriptionParamName, EARLY_WITHDRAWAL_CHARGE_PERCENTAGE, SELECTED_FROM_DATE, SELECTED_TO_DATE));

    /** Stripped before delegating to core's close, which would reject them. */
    private static final Set<String> CLOSURE_ONLY_PARAMETERS = Set.of(toSavingsAccountIdParamName, transferDescriptionParamName,
            EARLY_WITHDRAWAL_CHARGE_PERCENTAGE, SELECTED_FROM_DATE, SELECTED_TO_DATE);

    public static final String TRANSFER_DETAILS_ID_CHANGE = "transferDetailsId";
    public static final String TRANSFER_AMOUNT_CHANGE = "transferAmount";
    public static final String EARLY_WITHDRAWAL_CHARGE_AMOUNT_CHANGE = "earlyWithdrawalChargeAmount";

    private final PlatformSecurityContext context;
    private final SavingsAccountAssembler savingsAccountAssembler;
    private final SavingsAccountRepositoryWrapper savingsAccountRepository;
    private final SavingsAccountTransactionRepository savingsAccountTransactionRepository;
    private final ConfigurationDomainService configurationDomainService;
    private final JournalEntryWritePlatformService journalEntryWritePlatformService;
    private final AdvanclyInterestChargeApplicationService interestChargeApplicationService;
    private final AdvanclyAccountTransfersWritePlatformService accountTransfersWritePlatformService;
    private final FromJsonHelper fromApiJsonHelper;
    private final SavingsAccountPostInterestService savingsAccountPostInterestService;
    @Qualifier("coreSavingsAccountWritePlatformService")
    private final SavingsAccountWritePlatformService coreSavingsAccountWritePlatformService;

    @PersistenceContext
    private EntityManager entityManager;

    public static boolean isCloseWithTransfer(final JsonCommand command) {
        return command.parameterExists(toSavingsAccountIdParamName);
    }

    @Transactional
    public CommandProcessingResult closeWithTransfer(final Long savingsId, final JsonCommand command) {
        context.authenticatedUser();
        final Type typeOfMap = new TypeToken<Map<String, Object>>() {}.getType();
        this.fromApiJsonHelper.checkForUnsupportedParameters(typeOfMap, command.json(), CLOSE_WITH_TRANSFER_REQUEST_DATA_PARAMETERS);

        final LocalDate closedDate = command.localDateValueOfParameterNamed(SavingsApiConstants.closedOnDateParamName);
        final Long toSavingsAccountId = command.longValueOfParameterNamed(toSavingsAccountIdParamName);
        final boolean applyEarlyWithdrawalCharge = command.booleanPrimitiveValueOfParameterNamed(APPLY_EARLY_WITHDRAWAL_CHARGE);
        final BigDecimal percentageOverride = command.parameterExists(EARLY_WITHDRAWAL_CHARGE_PERCENTAGE)
                ? command.bigDecimalValueOfParameterNamed(EARLY_WITHDRAWAL_CHARGE_PERCENTAGE)
                : null;
        final LocalDate selectedFromDate = command.localDateValueOfParameterNamed(SELECTED_FROM_DATE);
        final LocalDate selectedToDate = command.localDateValueOfParameterNamed(SELECTED_TO_DATE);
        validateRequest(savingsId, closedDate, toSavingsAccountId, percentageOverride);

        final Locale locale = command.extractLocale();
        final DateTimeFormatter fmt = DateTimeFormatter.ofPattern(command.dateFormat()).withLocale(locale);

        SavingsAccount account = this.savingsAccountAssembler.assembleFrom(savingsId, false);
        validateAccount(account, toSavingsAccountId, closedDate);
        account.refreshSummary(false);

        final Map<String, Object> closureChanges = new LinkedHashMap<>();

        if (account.getSummary().getAccountBalance().signum() > 0) {
            // 1. Post closing interest first: a cumulative charge is a percentage of interest posted.
            postClosureInterest(account, closedDate);
            startNextPhase();

            // 2. Size the payout.
            account = this.savingsAccountAssembler.assembleFrom(savingsId, false);
            // Rebuild the summary from transactions so the quote reads exact figures.
            account.refreshSummary(false);
            final BigDecimal balance = account.getSummary().getAccountBalance();
            final BigDecimal charge = this.interestChargeApplicationService.quoteClosureCharge(account, closedDate,
                    applyEarlyWithdrawalCharge, percentageOverride, selectedFromDate, selectedToDate);
            final BigDecimal payout = balance.subtract(charge);
            if (balance.signum() > 0 && payout.signum() <= 0) {
                throw new GeneralPlatformDomainRuleException("error.msg.savings.account.close.nothing.to.transfer.after.charge",
                        "The early-withdrawal charge takes the whole balance, so there is nothing to transfer.", savingsId);
            }

            // 3. Transfer the payout plus charge, leaving zero.
            if (balance.signum() > 0) {
                final SavingsAccount destination = this.savingsAccountAssembler.assembleFrom(toSavingsAccountId, false);
                final boolean isRegularTransaction = true;
                final boolean isExceptionForBalanceCheck = false;
                final AccountTransferDTO accountTransferDTO = new AccountTransferDTO(closedDate, payout, PortfolioAccountType.SAVINGS,
                        PortfolioAccountType.SAVINGS, null, null, command.stringValueOfParameterNamed(transferDescriptionParamName), locale,
                        fmt, null, null, null, null, null, AccountTransferType.ACCOUNT_TRANSFER.getValue(), null, null, ExternalId.empty(),
                        null, destination, account, isRegularTransaction, isExceptionForBalanceCheck);
                final Long transferDetailsId = this.accountTransfersWritePlatformService.transferClosurePayout(accountTransferDTO,
                        new ClosureCharge(applyEarlyWithdrawalCharge, percentageOverride, selectedFromDate, selectedToDate));
                closureChanges.put(TRANSFER_DETAILS_ID_CHANGE, transferDetailsId);
                closureChanges.put(TRANSFER_AMOUNT_CHANGE, payout);
                closureChanges.put(EARLY_WITHDRAWAL_CHARGE_AMOUNT_CHANGE, charge);
                startNextPhase();
            }
        }

        // 4. Core close; withdrawBalance sweeps any sub-currency-unit remainder.
        final CommandProcessingResult closed = this.coreSavingsAccountWritePlatformService.close(savingsId, coreCloseCommand(command));
        final Map<String, Object> changes = new LinkedHashMap<>();
        if (closed.getChanges() != null) {
            changes.putAll(closed.getChanges());
        }
        changes.putAll(closureChanges);
        return new CommandProcessingResultBuilder().withCommandId(closed.getCommandId()).withEntityId(closed.getResourceId())
                .withOfficeId(closed.getOfficeId()).withClientId(closed.getClientId()).withGroupId(closed.getGroupId())
                .withSavingsId(closed.getSavingsId()).with(changes).build();
    }

    private void validateRequest(final Long savingsId, final LocalDate closedDate, final Long toSavingsAccountId,
            final BigDecimal percentageOverride) {
        if (closedDate == null) {
            throw new GeneralPlatformDomainRuleException("error.msg.savings.account.close.closed.on.date.required",
                    "closedOnDate is required.");
        }
        if (DateUtils.isDateInTheFuture(closedDate)) {
            throw new GeneralPlatformDomainRuleException("error.msg.savings.account.close.closed.on.date.in.future",
                    "closedOnDate cannot be in the future.", closedDate);
        }
        if (toSavingsAccountId == null || toSavingsAccountId <= 0) {
            throw new GeneralPlatformDomainRuleException("error.msg.savings.account.close.transfer.destination.required",
                    "toSavingsAccountId is required to transfer the closure proceeds.");
        }
        if (Objects.equals(savingsId, toSavingsAccountId)) {
            throw new GeneralPlatformDomainRuleException("error.msg.savings.account.close.transfer.destination.same.account",
                    "The closure proceeds cannot be transferred to the account being closed.", savingsId);
        }
        this.interestChargeApplicationService.validatePercentageOverride(percentageOverride);
    }

    private void validateAccount(final SavingsAccount account, final Long toSavingsAccountId, final LocalDate closedDate) {
        if (!account.depositAccountType().isSavingsDeposit()) {
            throw new GeneralPlatformDomainRuleException("error.msg.savings.account.close.transfer.only.plain.savings",
                    "Closing with a transfer is only supported for a savings account; close a deposit account with its own closure.",
                    account.getId());
        }
        if (account.isWithdrawalBlockedByAccountRule()) {
            throw new GeneralPlatformDomainRuleException("error.msg.savings.account.close.withdrawal.not.allowed.account.rule",
                    "This account cannot be closed with a fund withdrawal while withdrawals are disabled for it.", account.getId());
        }
        for (final SavingsAccountTransaction transaction : account.getTransactions()) {
            if (!transaction.isReversed() && DateUtils.isAfter(transaction.getTransactionDate(), closedDate)) {
                throw new GeneralPlatformDomainRuleException("error.msg.savings.account.close.closed.on.date.before.last.transaction",
                        "closedOnDate cannot be before the last transaction on the account.", closedDate);
            }
        }

        final SavingsAccount destination = this.savingsAccountAssembler.assembleFrom(toSavingsAccountId, false);
        if (!destination.isActive()) {
            throw new GeneralPlatformDomainRuleException("error.msg.savings.account.close.transfer.destination.not.active",
                    "The savings account to transfer the closure proceeds to must be active.", toSavingsAccountId);
        }
        final boolean sameClient = account.clientId() != null && Objects.equals(account.clientId(), destination.clientId());
        final boolean sameGroup = account.groupId() != null && Objects.equals(account.groupId(), destination.groupId());
        if (!sameClient && !sameGroup) {
            throw new GeneralPlatformDomainRuleException("error.msg.savings.account.close.transfer.destination.must.belong.to.same.client",
                    "The savings account to transfer the closure proceeds to must belong to the same client as the account being closed.",
                    toSavingsAccountId);
        }
    }

    /** Bounded by the closure date, not today, so a backdated closure earns no extra days. */
    private void postClosureInterest(final SavingsAccount account, final LocalDate closedDate) {
        if (account.getNominalAnnualInterestRate().signum() <= 0) {
            return;
        }
        final boolean isSavingsInterestPostingAtCurrentPeriodEnd = this.configurationDomainService
                .isSavingsInterestPostingAtCurrentPeriodEnd();
        final Integer financialYearBeginningMonth = this.configurationDomainService.retrieveFinancialYearBeginningMonth();
        final Set<Long> existingTransactionIds = new HashSet<>(account.findExistingTransactionIds());
        final Set<Long> existingReversedTransactionIds = new HashSet<>(account.findExistingReversedTransactionIds());

        final MathContext mc = new MathContext(10, MoneyHelper.getRoundingMode());
        final boolean isInterestTransfer = false;
        final boolean backdatedTxnsAllowedTill = false;
        final boolean postReversals = false;
        this.savingsAccountPostInterestService.postInterest(account, mc, account.interestPostingUpToForClosure(closedDate),
                isInterestTransfer, isSavingsInterestPostingAtCurrentPeriodEnd, financialYearBeginningMonth,
                account.interestPostingTransactionDateForClosure(closedDate), backdatedTxnsAllowedTill, postReversals);

        for (final SavingsAccountTransaction transaction : account.getTransactions()) {
            if (transaction.getId() == null) {
                this.savingsAccountTransactionRepository.save(transaction);
            }
        }
        this.savingsAccountRepository.saveAndFlush(account);

        final boolean isAccountTransfer = false;
        final Map<String, Object> accountingBridgeData = account.deriveAccountingBridgeData(account.getCurrency().getCode(),
                existingTransactionIds, existingReversedTransactionIds, isAccountTransfer, backdatedTxnsAllowedTill);
        this.journalEntryWritePlatformService.createJournalEntriesForSavings(accountingBridgeData);
    }

    private void startNextPhase() {
        this.entityManager.flush();
        this.entityManager.clear();
    }

    private JsonCommand coreCloseCommand(final JsonCommand command) {
        final JsonObject json = command.parsedJson().getAsJsonObject().deepCopy();
        CLOSURE_ONLY_PARAMETERS.forEach(json::remove);
        json.addProperty(SavingsApiConstants.withdrawBalanceParamName, true);
        final JsonElement parsed = json;
        return JsonCommand.fromExistingCommand(command, parsed);
    }
}
