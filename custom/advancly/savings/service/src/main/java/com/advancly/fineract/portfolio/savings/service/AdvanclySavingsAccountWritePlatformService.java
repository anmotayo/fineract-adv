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

import com.advancly.fineract.portfolio.savings.data.BulkTransactionDataValidator;
import com.advancly.fineract.portfolio.savings.domain.AdvanclySavingsAccountAssembler;
import com.advancly.fineract.portfolio.savings.domain.AdvanclySavingsAccountTransactionRepository;
import com.advancly.fineract.portfolio.savings.domain.AssembledSavingsAccount;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.apache.fineract.accounting.common.AccountingConstants.CashAccountsForSavings;
import org.apache.fineract.accounting.producttoaccountmapping.domain.ProductToGLAccountMapping;
import org.apache.fineract.accounting.producttoaccountmapping.domain.ProductToGLAccountMappingRepository;
import org.apache.fineract.infrastructure.configuration.domain.ConfigurationDomainService;
import org.apache.fineract.infrastructure.core.api.JsonCommand;
import org.apache.fineract.infrastructure.core.data.CommandProcessingResult;
import org.apache.fineract.infrastructure.core.data.CommandProcessingResultBuilder;
import org.apache.fineract.infrastructure.core.exception.GeneralPlatformDomainRuleException;
import org.apache.fineract.infrastructure.core.serialization.FromJsonHelper;
import org.apache.fineract.infrastructure.security.service.PlatformSecurityContext;
import org.apache.fineract.organisation.monetary.domain.Money;
import org.apache.fineract.organisation.office.domain.Office;
import org.apache.fineract.organisation.staff.domain.Staff;
import org.apache.fineract.portfolio.PortfolioProductType;
import org.apache.fineract.portfolio.note.domain.Note;
import org.apache.fineract.portfolio.note.domain.NoteRepository;
import org.apache.fineract.portfolio.paymentdetail.PaymentDetailConstants;
import org.apache.fineract.portfolio.paymentdetail.domain.PaymentDetail;
import org.apache.fineract.portfolio.paymentdetail.domain.PaymentDetailRepository;
import org.apache.fineract.portfolio.paymentdetail.service.PaymentDetailWritePlatformService;
import org.apache.fineract.portfolio.paymenttype.domain.PaymentType;
import org.apache.fineract.portfolio.paymenttype.domain.PaymentTypeRepositoryWrapper;
import org.apache.fineract.portfolio.savings.data.SavingsAccountData;
import org.apache.fineract.portfolio.savings.data.SavingsAccountTransactionData;
import org.apache.fineract.portfolio.savings.data.SavingsAccountTransactionDataValidator;
import org.apache.fineract.portfolio.savings.domain.GSIMRepositoy;
import org.apache.fineract.portfolio.savings.domain.GroupSavingsIndividualMonitoring;
import org.apache.fineract.portfolio.savings.domain.SavingsAccount;
import org.apache.fineract.portfolio.savings.domain.SavingsAccountTransaction;
import org.apache.fineract.portfolio.savings.service.SavingsAccountWritePlatformService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * Optimized WritePlatformService that overrides deposit() and withdrawal() with O(1)/O(k) path selection. All other
 * methods delegate to the core SavingsAccountWritePlatformServiceJpaRepositoryImpl via the delegate.
 */
@SuppressWarnings("SpringJavaInjectionPointsAutowiringInspection")
@Slf4j
@Service
@Primary
public class AdvanclySavingsAccountWritePlatformService implements SavingsAccountWritePlatformService {

    private final PlatformSecurityContext context;
    private final SavingsAccountTransactionDataValidator savingsAccountTransactionDataValidator;
    private final AdvanclySavingsAccountAssembler assembler;
    private final AdvanclySavingsAccountDomainService domainService;
    private final AdvanclySavingsAccountTransactionRepository advanclyTransactionRepository;
    private final PaymentDetailWritePlatformService paymentDetailWritePlatformService;
    private final NoteRepository noteRepository;
    private final GSIMRepositoy gsimRepository;
    private final SavingsAccountWritePlatformService delegate;
    private final BulkTransactionDataValidator bulkTransactionDataValidator;
    private final FromJsonHelper fromApiJsonHelper;
    private final PaymentTypeRepositoryWrapper paymentTypeRepositoryWrapper;
    private final PaymentDetailRepository paymentDetailRepository;
    private final AdvanclyInterestChargeApplicationService interestChargeApplicationService;
    private final ProductToGLAccountMappingRepository productToGLAccountMappingRepository;
    private final ConfigurationDomainService configurationDomainService;

    @Autowired
    public AdvanclySavingsAccountWritePlatformService(final PlatformSecurityContext context,
            final SavingsAccountTransactionDataValidator savingsAccountTransactionDataValidator,
            final AdvanclySavingsAccountAssembler assembler, final AdvanclySavingsAccountDomainService domainService,
            final AdvanclySavingsAccountTransactionRepository advanclyTransactionRepository,
            final PaymentDetailWritePlatformService paymentDetailWritePlatformService, final NoteRepository noteRepository,
            final GSIMRepositoy gsimRepository,
            @Qualifier("coreSavingsAccountWritePlatformService") final SavingsAccountWritePlatformService delegate,
            final BulkTransactionDataValidator bulkTransactionDataValidator, final FromJsonHelper fromApiJsonHelper,
            final PaymentTypeRepositoryWrapper paymentTypeRepositoryWrapper, final PaymentDetailRepository paymentDetailRepository,
            final AdvanclyInterestChargeApplicationService interestChargeApplicationService,
            final ProductToGLAccountMappingRepository productToGLAccountMappingRepository,
            final ConfigurationDomainService configurationDomainService) {
        this.context = context;
        this.savingsAccountTransactionDataValidator = savingsAccountTransactionDataValidator;
        this.assembler = assembler;
        this.domainService = domainService;
        this.advanclyTransactionRepository = advanclyTransactionRepository;
        this.paymentDetailWritePlatformService = paymentDetailWritePlatformService;
        this.noteRepository = noteRepository;
        this.gsimRepository = gsimRepository;
        this.delegate = delegate;
        this.bulkTransactionDataValidator = bulkTransactionDataValidator;
        this.fromApiJsonHelper = fromApiJsonHelper;
        this.paymentTypeRepositoryWrapper = paymentTypeRepositoryWrapper;
        this.paymentDetailRepository = paymentDetailRepository;
        this.interestChargeApplicationService = interestChargeApplicationService;
        this.productToGLAccountMappingRepository = productToGLAccountMappingRepository;
        this.configurationDomainService = configurationDomainService;
    }

    @Transactional
    @Override
    public CommandProcessingResult deposit(final Long savingsId, final JsonCommand command) {
        context.authenticatedUser();
        savingsAccountTransactionDataValidator.validate(command);

        final LocalDate transactionDate = command.localDateValueOfParameterNamed("transactionDate");
        final BigDecimal transactionAmount = command.bigDecimalValueOfParameterNamed("transactionAmount");

        Optional<LocalDate> lastTxnDate = advanclyTransactionRepository.findLastTransactionDate(savingsId);
        boolean isBackdated = lastTxnDate.isPresent() && transactionDate.isBefore(lastTxnDate.get());

        if (isBackdated) {
            return delegate.deposit(savingsId, command);
        }

        final AssembledSavingsAccount assembled = assembler.assembleForAppendPath(savingsId);

        final SavingsAccount account = assembled.getAccount();

        // The O(1) append path builds SavingsAccountTransaction rows directly rather than calling
        // SavingsAccount#deposit(...), so any deposit-type-specific entity override (e.g. DynamicDepositAccount's) is
        // never invoked here. Deposit-type accounts (FD/RD/Dynamic Deposit) always go through the core path instead,
        // where their entity overrides run correctly; only plain savings uses the optimized path.
        if (!account.depositAccountType().isSavingsDeposit()) {
            return delegate.deposit(savingsId, command);
        }

        final Map<String, Object> changes = new LinkedHashMap<>();
        final PaymentDetail paymentDetail = paymentDetailWritePlatformService.createAndPersistPaymentDetail(command, changes);

        Money lastRunningBalance = Money.of(account.getCurrency(), account.getSummary().getRunningBalanceOnPivotDate());

        final SavingsAccountTransaction deposit = domainService.handleDepositOptimized(account, transactionDate, transactionAmount,
                paymentDetail, lastRunningBalance, account.getCurrency(), assembled.getLastBalanceBearingTransaction(), false);

        handleGsimDeposit(account, transactionAmount, deposit);
        handleNote(account, deposit, command);

        return new CommandProcessingResultBuilder().withEntityId(deposit.getId()).withOfficeId(account.officeId())
                .withClientId(account.clientId()).withGroupId(account.groupId()).withSavingsId(savingsId).with(changes).build();
    }

    @Transactional
    @Override
    public CommandProcessingResult withdrawal(final Long savingsId, final JsonCommand command) {
        context.authenticatedUser();
        savingsAccountTransactionDataValidator.validate(command);

        final LocalDate transactionDate = command.localDateValueOfParameterNamed("transactionDate");
        final BigDecimal transactionAmount = command.bigDecimalValueOfParameterNamed("transactionAmount");

        Optional<LocalDate> lastTxnDate = advanclyTransactionRepository.findLastTransactionDate(savingsId);
        boolean isBackdated = lastTxnDate.isPresent() && transactionDate.isBefore(lastTxnDate.get());

        // Resolved (and the account-rule guard applied) BEFORE the backdated branch decides whether to delegate to
        // core - otherwise a backdated withdrawal would reach `delegate.withdrawal(...)` (core Fineract, which has no
        // notion of this account-level rule) without ever being checked. assembleForAppendPath(...) is a read-only
        // lookup (no persisted side effects), so resolving it here purely for the guard - even on the path that then
        // delegates rather than using this instance - is safe; delegate.withdrawal(...) does its own fresh load.
        final AssembledSavingsAccount assembled = assembler.assembleForAppendPath(savingsId);
        final SavingsAccount account = assembled.getAccount();

        if (account.isWithdrawalBlockedByAccountRule()) {
            throw new GeneralPlatformDomainRuleException("error.msg.savings.account.withdrawal.not.allowed.account.rule",
                    "Withdrawal is not allowed for this account while withdrawals are disabled for it.", account.getId());
        }

        // See the equivalent check in deposit(...) - deposit-type accounts (FD/RD/Dynamic Deposit) always go through
        // the core path so their entity-level overrides (e.g. DynamicDepositAccount#withdraw) actually run.
        if (isBackdated || !account.depositAccountType().isSavingsDeposit()) {
            final CommandProcessingResult result = delegate.withdrawal(savingsId, command);
            applyInterestChargeIfApplicable(savingsId, result, command);
            return result;
        }

        final Map<String, Object> changes = new LinkedHashMap<>();
        final PaymentDetail paymentDetail = paymentDetailWritePlatformService.createAndPersistPaymentDetail(command, changes);

        Money lastRunningBalance = Money.of(account.getCurrency(), account.getSummary().getRunningBalanceOnPivotDate());

        final SavingsAccountTransaction withdrawal = domainService.handleWithdrawalOptimized(account, transactionDate, transactionAmount,
                paymentDetail, true, lastRunningBalance, account.getCurrency(), assembled.getLastBalanceBearingTransaction(), false);

        // `withdrawal` was appended via addTransactionToExisting(...) (handleWithdrawalOptimized(...) above), i.e. it
        // lives in the account's pivot-config transient list, not its JPA-managed transactions collection. The
        // charge transaction and its journal entries must be added/derived through that same mechanism, so appendPath
        // must be true here - passing false made the charge land in the wrong collection (invisible to
        // deriveAccountingBridgeData(...), so no journal entries were ever posted for it).
        this.interestChargeApplicationService.applyIfApplicable(account, withdrawal, command, true, false);

        handleGsimWithdrawal(account, transactionAmount, withdrawal);
        handleNote(account, withdrawal, command);

        return new CommandProcessingResultBuilder().withEntityId(withdrawal.getId()).withOfficeId(account.officeId())
                .withClientId(account.clientId()).withGroupId(account.groupId()).withSavingsId(savingsId).with(changes).build();
    }

    private void applyInterestChargeIfApplicable(final Long savingsId, final CommandProcessingResult result, final JsonCommand command) {
        validateEarlyWithdrawalChargePercentageOverride(command);
        final Long transactionId = result.getResourceId();
        if (transactionId == null) {
            return;
        }
        final SavingsAccountTransaction withdrawal = this.advanclyTransactionRepository.findById(transactionId).orElse(null);
        if (withdrawal == null || withdrawal.getSavingsAccount() == null || !savingsId.equals(withdrawal.getSavingsAccount().getId())) {
            return;
        }
        // This branch always goes through delegate.withdrawal(...) (core), never the O(1) append path. Core's own
        // SavingsAccountWritePlatformServiceJpaRepositoryImpl decides which collection the withdrawal itself was
        // added through purely from this tenant-wide config - NOT from whether this specific transaction is
        // backdated relative to this account's own history, which is a different, unrelated concept despite both
        // loosely being called "backdated" (this method used to conflate them). The charge must defer to the exact
        // same config value core used, or it can land in the wrong collection regardless of this transaction's own
        // backdated-ness.
        final boolean corePivotConfigStatus = configurationDomainService.retrievePivotDateConfig();
        this.interestChargeApplicationService.applyIfApplicable(withdrawal.getSavingsAccount(), withdrawal, command, false,
                corePivotConfigStatus);
    }

    private void validateEarlyWithdrawalChargePercentageOverride(final JsonCommand command) {
        if (!command.parameterExists(EARLY_WITHDRAWAL_CHARGE_PERCENTAGE)) {
            return;
        }
        final BigDecimal chargePercentageOverride = command.bigDecimalValueOfParameterNamed(EARLY_WITHDRAWAL_CHARGE_PERCENTAGE);
        if (chargePercentageOverride != null && (chargePercentageOverride.compareTo(BigDecimal.ZERO) < 0
                || chargePercentageOverride.compareTo(BigDecimal.valueOf(100)) > 0)) {
            throw new GeneralPlatformDomainRuleException("error.msg.savings.account.early.withdrawal.charge.percentage.invalid",
                    "earlyWithdrawalChargePercentage must be between 0 and 100.");
        }
    }

    @Transactional
    @Override
    public CommandProcessingResult bulkTransaction(final Long savingsId, final JsonCommand command) {
        context.authenticatedUser();
        bulkTransactionDataValidator.validate(command.json());

        final JsonArray transactions = fromApiJsonHelper.extractJsonArrayNamed("transactions", command.parsedJson());
        final JsonObject rootJson = command.parsedJson().getAsJsonObject();
        final String dateFormat = fromApiJsonHelper.extractDateFormatParameter(rootJson);
        final java.util.Locale locale = fromApiJsonHelper.extractLocaleParameter(rootJson);

        // Check if any transaction is backdated — if so, delegate each individually to core
        Optional<LocalDate> lastTxnDate = advanclyTransactionRepository.findLastTransactionDate(savingsId);
        if (lastTxnDate.isPresent()) {
            for (int i = 0; i < transactions.size(); i++) {
                final JsonObject txn = transactions.get(i).getAsJsonObject();
                final LocalDate txnDate = fromApiJsonHelper.extractLocalDateNamed("transactionDate", txn, dateFormat, locale);
                if (txnDate.isBefore(lastTxnDate.get())) {
                    return delegateBulkTransactionToCore(savingsId, transactions, dateFormat, locale);
                }
            }
        }

        final AssembledSavingsAccount assembled = assembler.assembleForAppendPath(savingsId);
        final SavingsAccount account = assembled.getAccount();

        // See the equivalent check in deposit(...)/withdrawal(...) - deposit-type accounts always go through core,
        // one transaction at a time, so their entity-level overrides run correctly.
        if (!account.depositAccountType().isSavingsDeposit()) {
            return delegateBulkTransactionToCore(savingsId, transactions, dateFormat, locale);
        }

        Money lastRunningBalance = Money.of(account.getCurrency(), account.getSummary().getRunningBalanceOnPivotDate());
        SavingsAccountTransaction lastBalanceBearingTxn = assembled.getLastBalanceBearingTransaction();
        final Map<String, Object> changes = new LinkedHashMap<>();

        for (int i = 0; i < transactions.size(); i++) {
            final JsonObject txn = transactions.get(i).getAsJsonObject();
            final String type = fromApiJsonHelper.extractStringNamed("type", txn);
            final LocalDate transactionDate = fromApiJsonHelper.extractLocalDateNamed("transactionDate", txn, dateFormat, locale);
            final BigDecimal transactionAmount = fromApiJsonHelper.extractBigDecimalNamed("transactionAmount", txn, locale);
            final String receiptNumber = fromApiJsonHelper.extractStringNamed("receiptNumber", txn);

            final PaymentDetail paymentDetail = createPaymentDetailFromJsonObject(txn);

            SavingsAccountTransaction savedTxn;
            if ("deposit".equals(type)) {
                savedTxn = domainService.handleDepositOptimized(account, transactionDate, transactionAmount, paymentDetail,
                        lastRunningBalance, account.getCurrency(), lastBalanceBearingTxn, false);
            } else {
                savedTxn = domainService.handleWithdrawalOptimized(account, transactionDate, transactionAmount, paymentDetail, true,
                        lastRunningBalance, account.getCurrency(), lastBalanceBearingTxn, false);
            }

            String txnKey = (receiptNumber != null && !receiptNumber.isBlank()) ? receiptNumber : String.valueOf(i);
            changes.put(txnKey, savedTxn.getId());
            lastRunningBalance = savedTxn.getRunningBalance(account.getCurrency());
            lastBalanceBearingTxn = savedTxn;

            final String noteText = fromApiJsonHelper.extractStringNamed("note", txn);
            if (noteText != null && !noteText.isBlank()) {
                final Note note = Note.savingsTransactionNote(account, savedTxn, noteText);
                noteRepository.save(note);
            }
        }

        return new CommandProcessingResultBuilder().withOfficeId(account.officeId()).withClientId(account.clientId())
                .withGroupId(account.groupId()).withSavingsId(savingsId).with(changes).build();
    }

    private PaymentDetail createPaymentDetailFromJsonObject(JsonObject txn) {
        final Long paymentTypeId = fromApiJsonHelper.extractLongNamed("paymentTypeId", txn);
        if (paymentTypeId == null) {
            return null;
        }
        final PaymentType paymentType = paymentTypeRepositoryWrapper.findOneWithNotFoundDetection(paymentTypeId);
        final String accountNumber = fromApiJsonHelper.extractStringNamed(PaymentDetailConstants.accountNumberParamName, txn);
        final String checkNumber = fromApiJsonHelper.extractStringNamed(PaymentDetailConstants.checkNumberParamName, txn);
        final String routingCode = fromApiJsonHelper.extractStringNamed(PaymentDetailConstants.routingCodeParamName, txn);
        final String receiptNumber = fromApiJsonHelper.extractStringNamed(PaymentDetailConstants.receiptNumberParamName, txn);
        final String bankNumber = fromApiJsonHelper.extractStringNamed(PaymentDetailConstants.bankNumberParamName, txn);
        final PaymentDetail paymentDetail = PaymentDetail.instance(paymentType, accountNumber, checkNumber, routingCode, receiptNumber,
                bankNumber);
        return paymentDetailRepository.saveAndFlush(paymentDetail);
    }

    private CommandProcessingResult delegateBulkTransactionToCore(final Long savingsId, final JsonArray transactions,
            final String dateFormat, final java.util.Locale locale) {
        final Map<String, Object> changes = new LinkedHashMap<>();

        for (int i = 0; i < transactions.size(); i++) {
            final JsonObject txn = transactions.get(i).getAsJsonObject();
            final String type = fromApiJsonHelper.extractStringNamed("type", txn);
            final String receiptNumber = fromApiJsonHelper.extractStringNamed("receiptNumber", txn);

            // Add root-level dateFormat and locale to the per-transaction JSON for the delegate
            final JsonObject txnWithFormat = txn.deepCopy();
            txnWithFormat.addProperty("dateFormat", dateFormat);
            txnWithFormat.addProperty("locale", locale.toLanguageTag());

            final JsonCommand txnCommand = JsonCommand.fromExistingCommand(null, txnWithFormat.toString(),
                    fromApiJsonHelper.parse(txnWithFormat.toString()), fromApiJsonHelper, null, null, null, null, null, null, savingsId,
                    null, null, null, null, null, null, null);

            CommandProcessingResult result;
            if ("deposit".equals(type)) {
                result = delegate.deposit(savingsId, txnCommand);
            } else {
                result = delegate.withdrawal(savingsId, txnCommand);
            }

            String txnKey = (receiptNumber != null && !receiptNumber.isBlank()) ? receiptNumber : String.valueOf(i);
            changes.put(txnKey, result.getResourceId());
        }

        return new CommandProcessingResultBuilder().withSavingsId(savingsId).with(changes).build();
    }

    // === Helper methods ===

    private void handleGsimDeposit(SavingsAccount account, BigDecimal transactionAmount, SavingsAccountTransaction deposit) {
        if (account.getGsim() != null && deposit.getId() != null) {
            GroupSavingsIndividualMonitoring gsim = gsimRepository.findById(account.getGsim().getId()).orElseThrow();
            gsim.setParentDeposit(gsim.getParentDeposit().add(transactionAmount));
            gsimRepository.save(gsim);
        }
    }

    private void handleGsimWithdrawal(SavingsAccount account, BigDecimal transactionAmount, SavingsAccountTransaction withdrawal) {
        if (account.getGsim() != null && withdrawal.getId() != null) {
            GroupSavingsIndividualMonitoring gsim = gsimRepository.findById(account.getGsim().getId()).orElseThrow();
            gsim.setParentDeposit(gsim.getParentDeposit().subtract(transactionAmount));
            gsimRepository.save(gsim);
        }
    }

    private void handleNote(SavingsAccount account, SavingsAccountTransaction transaction, JsonCommand command) {
        final String noteText = command.stringValueOfParameterNamed("note");
        if (StringUtils.hasText(noteText)) {
            final Note note = Note.savingsTransactionNote(account, transaction, noteText);
            noteRepository.save(note);
        }
    }

    // === Delegate all other methods to core implementation ===

    @Override
    public CommandProcessingResult activate(Long savingsId, JsonCommand command) {
        return delegate.activate(savingsId, command);
    }

    @Override
    public CommandProcessingResult applyAnnualFee(Long savingsAccountChargeId, Long accountId) {
        return delegate.applyAnnualFee(savingsAccountChargeId, accountId);
    }

    @Override
    public CommandProcessingResult calculateInterest(Long savingsId) {
        return delegate.calculateInterest(savingsId);
    }

    @Override
    public CommandProcessingResult reverseTransaction(Long savingsId, Long transactionId, boolean allowAccountTransferModification,
            JsonCommand command) {
        return delegate.reverseTransaction(savingsId, transactionId, allowAccountTransferModification, command);
    }

    @Override
    public CommandProcessingResult undoTransaction(Long savingsId, Long transactionId, boolean allowAccountTransferModification) {
        return delegate.undoTransaction(savingsId, transactionId, allowAccountTransferModification);
    }

    @Override
    public CommandProcessingResult adjustSavingsTransaction(Long savingsId, Long transactionId, JsonCommand command) {
        return delegate.adjustSavingsTransaction(savingsId, transactionId, command);
    }

    // @Transactional is retained for consistency with deposit(...)/withdrawal(...); close command handlers already call
    // this synchronously inside a transaction, but keeping the boundary here makes the delegate path safe from future
    // callers too.
    @Transactional
    @Override
    public CommandProcessingResult close(final Long savingsId, final JsonCommand command) {
        return delegate.close(savingsId, command);
    }

    @Override
    public SavingsAccountTransaction initiateSavingsTransfer(SavingsAccount account, LocalDate transferDate) {
        return delegate.initiateSavingsTransfer(account, transferDate);
    }

    @Override
    public SavingsAccountTransaction withdrawSavingsTransfer(SavingsAccount account, LocalDate transferDate) {
        return delegate.withdrawSavingsTransfer(account, transferDate);
    }

    @Override
    public void rejectSavingsTransfer(SavingsAccount account) {
        delegate.rejectSavingsTransfer(account);
    }

    @Override
    public SavingsAccountTransaction acceptSavingsTransfer(SavingsAccount account, LocalDate transferDate, Office acceptedInOffice,
            Staff staff) {
        return delegate.acceptSavingsTransfer(account, transferDate, acceptedInOffice, staff);
    }

    @Override
    public CommandProcessingResult addSavingsAccountCharge(JsonCommand command) {
        return delegate.addSavingsAccountCharge(command);
    }

    @Override
    public CommandProcessingResult updateSavingsAccountCharge(JsonCommand command) {
        return delegate.updateSavingsAccountCharge(command);
    }

    @Override
    public CommandProcessingResult deleteSavingsAccountCharge(Long savingsAccountId, Long savingsAccountChargeId, JsonCommand command) {
        return delegate.deleteSavingsAccountCharge(savingsAccountId, savingsAccountChargeId, command);
    }

    @Override
    public CommandProcessingResult waiveCharge(Long savingsAccountId, Long savingsAccountChargeId) {
        return delegate.waiveCharge(savingsAccountId, savingsAccountChargeId);
    }

    @Override
    public CommandProcessingResult payCharge(Long savingsAccountId, Long savingsAccountChargeId, JsonCommand command) {
        return delegate.payCharge(savingsAccountId, savingsAccountChargeId, command);
    }

    @Override
    public CommandProcessingResult inactivateCharge(Long savingsAccountId, Long savingsAccountChargeId) {
        return delegate.inactivateCharge(savingsAccountId, savingsAccountChargeId);
    }

    @Override
    public CommandProcessingResult assignFieldOfficer(Long savingsAccountId, JsonCommand command) {
        return delegate.assignFieldOfficer(savingsAccountId, command);
    }

    @Override
    public CommandProcessingResult unassignFieldOfficer(Long savingsAccountId, JsonCommand command) {
        return delegate.unassignFieldOfficer(savingsAccountId, command);
    }

    @Override
    public void applyChargeDue(Long savingsAccountChargeId, Long accountId) {
        delegate.applyChargeDue(savingsAccountChargeId, accountId);
    }

    @Override
    public void processPostActiveActions(SavingsAccount account, DateTimeFormatter fmt, Set<Long> existingTransactionIds,
            Set<Long> existingReversedTransactionIds) {
        delegate.processPostActiveActions(account, fmt, existingTransactionIds, existingReversedTransactionIds);
    }

    @Override
    public CommandProcessingResult modifyWithHoldTax(Long savingsAccountId, JsonCommand command) {
        return delegate.modifyWithHoldTax(savingsAccountId, command);
    }

    @Override
    public void setSubStatusInactive(Long savingsId) {
        delegate.setSubStatusInactive(savingsId);
    }

    @Override
    public void setSubStatusDormant(Long savingsId) {
        delegate.setSubStatusDormant(savingsId);
    }

    @Override
    public void escheat(Long savingsId) {
        delegate.escheat(savingsId);
    }

    @Override
    public CommandProcessingResult postInterest(JsonCommand command) {
        return delegate.postInterest(command);
    }

    @Override
    public void postInterest(SavingsAccount account, boolean postInterestAs, LocalDate transactionDate, boolean backdatedTxnsAllowedTill) {
        delegate.postInterest(account, postInterestAs, transactionDate, backdatedTxnsAllowedTill);
    }

    @Override
    public SavingsAccountData postInterest(SavingsAccountData account, boolean postInterestAs, LocalDate transactionDate,
            boolean backdatedTxnsAllowedTill) {
        return delegate.postInterest(account, postInterestAs, transactionDate, backdatedTxnsAllowedTill);
    }

    @Override
    public CommandProcessingResult blockAccount(Long savingsId, JsonCommand command) {
        return delegate.blockAccount(savingsId, command);
    }

    @Override
    public CommandProcessingResult unblockAccount(Long savingsId) {
        return delegate.unblockAccount(savingsId);
    }

    @Override
    public CommandProcessingResult holdAmount(Long savingsId, JsonCommand command) {
        return delegate.holdAmount(savingsId, command);
    }

    @Override
    public CommandProcessingResult blockCredits(Long savingsId, JsonCommand command) {
        return delegate.blockCredits(savingsId, command);
    }

    @Override
    public CommandProcessingResult unblockCredits(Long savingsId) {
        return delegate.unblockCredits(savingsId);
    }

    @Override
    public CommandProcessingResult blockDebits(Long savingsId, JsonCommand command) {
        return delegate.blockDebits(savingsId, command);
    }

    @Override
    public CommandProcessingResult unblockDebits(Long savingsId) {
        return delegate.unblockDebits(savingsId);
    }

    @Override
    public CommandProcessingResult releaseAmount(Long savingsId, Long transactionId) {
        return delegate.releaseAmount(savingsId, transactionId);
    }

    @Override
    public CommandProcessingResult gsimActivate(Long gsimId, JsonCommand command) {
        return delegate.gsimActivate(gsimId, command);
    }

    @Override
    public CommandProcessingResult gsimDeposit(Long gsimId, JsonCommand command) {
        return delegate.gsimDeposit(gsimId, command);
    }

    @Override
    public CommandProcessingResult bulkGSIMClose(Long gsimId, JsonCommand command) {
        return delegate.bulkGSIMClose(gsimId, command);
    }

    @Override
    public void selectAccountId(SavingsAccountTransactionData accountTransaction, SavingsAccountData savingsAccountData) {
        if (accountTransaction.isInterestCharge()) {
            resolvePenaltyIncomeAccounts(accountTransaction, savingsAccountData);
            return;
        }
        delegate.selectAccountId(accountTransaction, savingsAccountData);
    }

    // Core's selectAccountId has no branch for INTEREST_CHARGE, so the custom savings batch pipeline resolves it
    // against the product's base savings-control/penalty-income mapping here.
    // findProductIdAndProductTypeAndFinancialAccountTypeAndChargeId(...) is deliberately NOT used here even though
    // it looks like the natural fit: it is a hand-written @Query ("mapping.charge.id = :chargeId"), not a Spring
    // Data derived query, so passing a null charge id does not get rewritten to "IS NULL" - in JPQL/SQL, "x = NULL"
    // is never true, so it can never match a row. Every existing caller of it (AccountingProcessorHelper, for
    // loan/savings/share charges) always passes an actual, non-null charge id for a charge-specific override; there
    // is no null-charge-id precedent anywhere in this codebase. An INTEREST_CHARGE transaction has no such
    // charge - it is a system-generated interest charge, not tied to any m_charge row - so the mapping we want
    // is the product's base, no-charge SAVINGS_CONTROL/INCOME_FROM_PENALTIES row (the one
    // SavingsProductToGLAccountMappingHelper's mergeSavingsToLiabilityAccountMappingChanges/
    // mergeSavingsToIncomeAccountMappingChanges create without a charge attached). findCoreProductToFinAccountMapping
    // is exactly that lookup, documented and used everywhere else in core for "paymentType is NULL and charge is
    // NULL...".
    private void resolvePenaltyIncomeAccounts(final SavingsAccountTransactionData accountTransaction,
            final SavingsAccountData savingsAccountData) {
        final Long productId = savingsAccountData.getProductId();
        final ProductToGLAccountMapping savingsControlMapping = this.productToGLAccountMappingRepository.findCoreProductToFinAccountMapping(
                productId, PortfolioProductType.SAVING.getValue(), CashAccountsForSavings.SAVINGS_CONTROL.getValue());
        final ProductToGLAccountMapping incomeFromPenaltiesMapping = this.productToGLAccountMappingRepository
                .findCoreProductToFinAccountMapping(productId, PortfolioProductType.SAVING.getValue(),
                        CashAccountsForSavings.INCOME_FROM_PENALTIES.getValue());
        accountTransaction.setAccountDebit(savingsControlMapping.getGlAccount().getId());
        accountTransaction.setAccountCredit(incomeFromPenaltiesMapping.getGlAccount().getId());
    }

}
