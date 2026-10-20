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
import static org.apache.fineract.portfolio.savings.DepositsApiConstants.onAccountClosureIdParamName;
import static org.apache.fineract.portfolio.savings.DepositsApiConstants.toSavingsAccountIdParamName;
import static org.apache.fineract.portfolio.savings.DepositsApiConstants.transferDescriptionParamName;

import com.advancly.fineract.portfolio.savings.domain.DynamicDepositAccount;
import java.math.BigDecimal;
import java.math.MathContext;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.apache.fineract.accounting.journalentry.service.JournalEntryWritePlatformService;
import org.apache.fineract.infrastructure.configuration.domain.ConfigurationDomainService;
import org.apache.fineract.infrastructure.core.api.JsonCommand;
import org.apache.fineract.infrastructure.core.data.ApiParameterError;
import org.apache.fineract.infrastructure.core.data.DataValidatorBuilder;
import org.apache.fineract.infrastructure.core.domain.ExternalId;
import org.apache.fineract.infrastructure.core.exception.GeneralPlatformDomainRuleException;
import org.apache.fineract.infrastructure.core.exception.PlatformApiDataValidationException;
import org.apache.fineract.infrastructure.core.service.DateUtils;
import org.apache.fineract.organisation.monetary.domain.MoneyHelper;
import org.apache.fineract.portfolio.account.PortfolioAccountType;
import org.apache.fineract.portfolio.account.data.AccountTransferDTO;
import org.apache.fineract.portfolio.account.data.PortfolioAccountData;
import org.apache.fineract.portfolio.account.domain.AccountTransferType;
import org.apache.fineract.portfolio.account.service.AccountAssociationsReadPlatformService;
import org.apache.fineract.portfolio.account.service.AccountTransfersWritePlatformService;
import org.apache.fineract.portfolio.paymentdetail.domain.PaymentDetail;
import org.apache.fineract.portfolio.savings.DepositAccountOnClosureType;
import org.apache.fineract.portfolio.savings.DepositAccountType;
import org.apache.fineract.portfolio.savings.DepositsApiConstants;
import org.apache.fineract.portfolio.savings.SavingsApiConstants;
import org.apache.fineract.portfolio.savings.domain.DepositAccountAssembler;
import org.apache.fineract.portfolio.savings.domain.DepositAccountDomainService;
import org.apache.fineract.portfolio.savings.domain.DepositAccountDomainServiceJpa;
import org.apache.fineract.portfolio.savings.domain.FixedDepositAccount;
import org.apache.fineract.portfolio.savings.domain.RecurringDepositAccount;
import org.apache.fineract.portfolio.savings.domain.SavingsAccount;
import org.apache.fineract.portfolio.savings.domain.SavingsAccountRepositoryWrapper;
import org.apache.fineract.portfolio.savings.domain.SavingsAccountStatusType;
import org.apache.fineract.portfolio.savings.domain.SavingsAccountTransaction;
import org.apache.fineract.portfolio.savings.domain.SavingsAccountTransactionComparator;
import org.apache.fineract.portfolio.savings.domain.SavingsAccountTransactionRepository;
import org.apache.fineract.portfolio.savings.service.SavingsEnumerations;
import org.apache.fineract.useradministration.domain.AppUser;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Closes a Dynamic Deposit the way {@link DepositAccountDomainServiceJpa} closes a Fixed or Recurring Deposit: the
 * closure type picks what happens to the money (withdraw it, or transfer it to a savings account).
 *
 * Core's {@code DepositAccountDomainService} is not modified. This {@code @Primary} implementation delegates every
 * method core declares, unchanged, to {@link DepositAccountDomainServiceJpa} and adds the two Dynamic Deposit closures,
 * which core cannot declare because it has no compile-time visibility of {@link DynamicDepositAccount}.
 *
 * <b>Order matters.</b> The closure settles first - forced interest posting, withholding tax and, before maturity, the
 * early-withdrawal forfeiture ({@link DynamicDepositAccount#beginClosureSettlement}) - and only then pays out whatever
 * is left, as ONE withdrawal or ONE transfer. Settlement and payout each journal their own transactions as they write
 * them, exactly as core's closure does, so there is deliberately no closing journal pass here: a pass keyed on a
 * "transaction ids that already existed" snapshot taken before settlement would journal the forfeiture a second time.
 */
@Service
@Primary
public class AdvanclyDepositAccountDomainService implements DepositAccountDomainService {

    private final DepositAccountDomainServiceJpa coreDomainService;
    private final SavingsAccountRepositoryWrapper savingsAccountRepository;
    private final SavingsAccountTransactionRepository savingsAccountTransactionRepository;
    private final JournalEntryWritePlatformService journalEntryWritePlatformService;
    private final ConfigurationDomainService configurationDomainService;
    private final DepositAccountAssembler depositAccountAssembler;
    private final AccountTransfersWritePlatformService accountTransfersWritePlatformService;
    private final DynamicDepositReinvestmentService reinvestmentService;
    private final AccountAssociationsReadPlatformService accountAssociationsReadPlatformService;

    @Autowired
    public AdvanclyDepositAccountDomainService(final DepositAccountDomainServiceJpa coreDomainService,
            final SavingsAccountRepositoryWrapper savingsAccountRepository,
            final SavingsAccountTransactionRepository savingsAccountTransactionRepository,
            final JournalEntryWritePlatformService journalEntryWritePlatformService,
            final ConfigurationDomainService configurationDomainService, final DepositAccountAssembler depositAccountAssembler,
            final AccountTransfersWritePlatformService accountTransfersWritePlatformService,
            final DynamicDepositReinvestmentService reinvestmentService,
            final AccountAssociationsReadPlatformService accountAssociationsReadPlatformService) {
        this.coreDomainService = coreDomainService;
        this.savingsAccountRepository = savingsAccountRepository;
        this.savingsAccountTransactionRepository = savingsAccountTransactionRepository;
        this.journalEntryWritePlatformService = journalEntryWritePlatformService;
        this.configurationDomainService = configurationDomainService;
        this.depositAccountAssembler = depositAccountAssembler;
        this.accountTransfersWritePlatformService = accountTransfersWritePlatformService;
        this.reinvestmentService = reinvestmentService;
        this.accountAssociationsReadPlatformService = accountAssociationsReadPlatformService;
    }

    /**
     * @return the id of the closing withdrawal, or {@code null} when the closure was a transfer (the transfer owns its
     *         own withdrawal and deposit legs) or the balance was already zero.
     */
    @Transactional
    public Long handleDynamicDepositPreMatureClosure(final DynamicDepositAccount account, final PaymentDetail paymentDetail,
            final AppUser user, final JsonCommand command, final Map<String, Object> changes) {
        return settleAndClose(account, paymentDetail, user, command, changes, true);
    }

    /** See {@link #handleDynamicDepositPreMatureClosure}. */
    @Transactional
    public Long handleDynamicDepositClosure(final DynamicDepositAccount account, final PaymentDetail paymentDetail, final AppUser user,
            final JsonCommand command, final Map<String, Object> changes) {
        return settleAndClose(account, paymentDetail, user, command, changes, false);
    }

    private Long settleAndClose(final DynamicDepositAccount account, final PaymentDetail paymentDetail, final AppUser user,
            final JsonCommand command, final Map<String, Object> changes, final boolean isPreMatureClosure) {
        final LocalDate closedDate = command.localDateValueOfParameterNamed(SavingsApiConstants.closedOnDateParamName);
        final Locale locale = command.extractLocale();
        final DateTimeFormatter fmt = DateTimeFormatter.ofPattern(command.dateFormat()).withLocale(locale);
        final DepositAccountOnClosureType closureType = DepositAccountOnClosureType
                .fromInt(command.integerValueOfParameterNamed(onAccountClosureIdParamName));

        validateClosure(account, closedDate, closureType, isPreMatureClosure);
        if (!isPreMatureClosure) {
            // A maturity closure closes a matured account. One that has reached its maturity date but has not yet been
            // matured by the maturity job is matured here first, so the final interest is posted exactly once - by the
            // maturity - and never again by the closure.
            matureAccount(account);
        }
        final SavingsAccount transferDestination = closureType.isTransferToSavings() ? resolveTransferDestination(account, command) : null;
        // Reinvesting the principal only sends the interest, less tax, to the linked savings account when there is one.
        final SavingsAccount interestDestination = closureType.isReinvestPrincipal() ? resolveLinkedAccount(account) : null;

        Long savingsTransactionId = null;
        // A premature closure may carry its own early-withdrawal charge percentage; the forfeiture settlement reads it
        // from the account, so it is set for the duration of the closure only.
        final boolean hasPercentageOverride = command.parameterExists(EARLY_WITHDRAWAL_CHARGE_PERCENTAGE)
                && command.bigDecimalValueOfParameterNamed(EARLY_WITHDRAWAL_CHARGE_PERCENTAGE) != null;
        if (hasPercentageOverride) {
            account.setWithdrawalChargePercentageOverride(command.bigDecimalValueOfParameterNamed(EARLY_WITHDRAWAL_CHARGE_PERCENTAGE));
        }
        // allowWithdrawal=false stops an early exit, not the closure at maturity: lift it for the payout (the transfer
        // wrapper checks it too), and put it back whatever happens.
        if (!isPreMatureClosure) {
            account.markClosingAtMaturity(true);
        }
        try {
            if (account.getSummary().getAccountBalance().signum() > 0) {
                // Only a premature closure settles interest, tax and the early-withdrawal charge: at maturity they were
                // settled when the account matured.
                if (isPreMatureClosure && account.beginClosureSettlement(closedDate)) {
                    postClosureInterest(account, account.interestPostingTransactionDateForClosure(closedDate), closedDate);
                }

                // Read AFTER settlement (premature) or maturity: this is principal plus the final interest, net of
                // withholding tax and any forfeiture - the customer's complete payout.
                final BigDecimal payout = account.getSummary().getAccountBalance();
                if (payout.signum() > 0) {
                    if (transferDestination != null) {
                        transferPayout(account, transferDestination, payout, closedDate, fmt, locale,
                                command.stringValueOfParameterNamed(transferDescriptionParamName));
                    } else if (closureType.isReinvestPrincipal()) {
                        // The principal moves to the new deposit; the interest, less tax, goes to the linked account or
                        // is
                        // paid out. The interest leaves FIRST: the engine labels a withdrawal as interest first, so
                        // this
                        // one is booked as interest and the principal withdrawal after it as principal.
                        final BigDecimal reinvestAmount = principalOf(account).min(payout);
                        final BigDecimal interestLessTax = payout.subtract(reinvestAmount);
                        if (interestLessTax.signum() > 0) {
                            if (interestDestination != null) {
                                transferPayout(account, interestDestination, interestLessTax, closedDate, fmt, locale,
                                        "Interest on closure of account " + account.getAccountNumber());
                            } else {
                                withdraw(account, interestLessTax, closedDate, fmt, paymentDetail);
                            }
                        }
                        savingsTransactionId = withdraw(account, reinvestAmount, closedDate, fmt, paymentDetail);
                        changes.put("reinvestedDepositId",
                                this.reinvestmentService.reinvest(account, reinvestAmount, closedDate, user).getId());
                    } else if (closureType.isReinvest()) {
                        final BigDecimal reinvestAmount = payout;
                        savingsTransactionId = withdraw(account, reinvestAmount, closedDate, fmt, paymentDetail);
                        changes.put("reinvestedDepositId",
                                this.reinvestmentService.reinvest(account, reinvestAmount, closedDate, user).getId());
                    } else {
                        savingsTransactionId = withdraw(account, payout, closedDate, fmt, paymentDetail);
                    }
                }
            }
        } finally {
            if (!isPreMatureClosure) {
                account.markClosingAtMaturity(false);
            }
            if (hasPercentageOverride) {
                account.setWithdrawalChargePercentageOverride(null);
            }
        }

        account.accountTermAndPreClosure().updateOnAccountClosureStatus(closureType);
        changes.putAll(account.close(user, command));
        if (isPreMatureClosure) {
            // close() finalises every account as CLOSED; a premature closure is recorded as PRE_MATURE_CLOSURE, the
            // status Fixed and Recurring Deposits end up in, so clients can tell it from a normal closure.
            account.setStatus(SavingsAccountStatusType.PRE_MATURE_CLOSURE.getValue());
            changes.put(SavingsApiConstants.statusParamName, SavingsEnumerations.status(SavingsAccountStatusType.PRE_MATURE_CLOSURE));
        }
        this.savingsAccountRepository.save(account);
        return savingsTransactionId;
    }

    /**
     * Brings an account that has reached its maturity date to the matured state. The final interest, and any tax
     * configured to be withheld at maturity, are posted on the maturity date - the settlement a maturity closure used
     * to make itself - and the account is marked matured, after which it takes no transaction but its closure.
     *
     * @return whether the account was matured: {@code false} for one that is not active, has no maturity date, or has
     *         not reached it
     */
    @Transactional
    public boolean matureAccount(final DynamicDepositAccount account) {
        final LocalDate maturityDate = account.maturityDate();
        if (!SavingsAccountStatusType.ACTIVE.hasStateOf(account.getStatus()) || maturityDate == null
                || DateUtils.isDateInTheFuture(maturityDate)) {
            return false;
        }
        postClosureInterest(account, account.interestPostingTransactionDateForClosure(maturityDate), maturityDate);
        account.markMatured();
        this.savingsAccountRepository.saveAndFlush(account);
        return true;
    }

    /**
     * The amount invested - what a principal-only reinvestment carries over - as it stands now, after the closing
     * interest and tax have been written and before any of the payout leaves. Computed from the transactions, not read
     * from the {@code deposit_amount} cache, which an account that predates the correct calculation may still hold
     * wrongly.
     */
    private static BigDecimal principalOf(final DynamicDepositAccount account) {
        final List<SavingsAccountTransaction> transactions = account.getTransactions();
        if (transactions.isEmpty()) {
            return BigDecimal.ZERO;
        }
        final SavingsAccountTransaction last = transactions.stream().max(new SavingsAccountTransactionComparator()).orElseThrow();
        return DynamicDepositInvestedAmountCalculator
                .investedAmountAsOf(transactions, account.getCurrency(), account.interestTransferTransactionIds(), last)
                .max(BigDecimal.ZERO);
    }

    private Long withdraw(final DynamicDepositAccount account, final BigDecimal amount, final LocalDate closedDate,
            final DateTimeFormatter fmt, final PaymentDetail paymentDetail) {
        final boolean applyWithdrawFee = false;
        final boolean isRegularTransaction = false;
        return this.coreDomainService
                .handleWithdrawal(account, fmt, closedDate, amount, paymentDetail, applyWithdrawFee, isRegularTransaction).getId();
    }

    private void validateClosure(final DynamicDepositAccount account, final LocalDate closedDate,
            final DepositAccountOnClosureType closureType, final boolean isPreMatureClosure) {
        final List<ApiParameterError> dataValidationErrors = new ArrayList<>();
        final DataValidatorBuilder baseDataValidator = new DataValidatorBuilder(dataValidationErrors)
                .resource(DYNAMIC_DEPOSIT_ACCOUNT_RESOURCE_NAME
                        + (isPreMatureClosure ? DepositsApiConstants.preMatureCloseAction : SavingsApiConstants.closeAction));

        // A premature closure is of an active account; a maturity closure is of one that has matured, or that has
        // reached its maturity date and is about to be matured by the closure itself.
        final boolean isActive = SavingsAccountStatusType.ACTIVE.hasStateOf(account.getStatus());
        if (isPreMatureClosure ? !isActive : !(isActive || account.isMatured())) {
            baseDataValidator.reset().failWithCodeNoParameterAddedToErrorCode("not.in.active.state");
        } else if (isPreMatureClosure && !account.isEarlyWithdrawal(closedDate)) {
            baseDataValidator.reset().parameter(SavingsApiConstants.closedOnDateParamName).value(closedDate)
                    .failWithCode("must.be.before.maturity.date");
        } else if (!isPreMatureClosure && (account.maturityDate() == null || account.isEarlyWithdrawal(closedDate))) {
            // isEarlyWithdrawal(...) is false for an account with no maturity date, so "not early" is not enough: a
            // maturity closure - which lifts the withdrawal lock for its payout - needs maturity positively
            // established.
            baseDataValidator.reset().parameter(SavingsApiConstants.closedOnDateParamName).value(closedDate)
                    .failWithCode("must.be.on.or.after.maturity.date");
        }
        if (!dataValidationErrors.isEmpty()) {
            throw new PlatformApiDataValidationException(dataValidationErrors);
        }

        if (closureType.isReinvest()) {
            if (isPreMatureClosure) {
                throw new GeneralPlatformDomainRuleException("error.msg.dynamicdepositaccount.prematureclosure.reinvest.not.allowed",
                        "Re-Invest is not supported for a Dynamic Deposit account pre mature close.", account.getId());
            }
            if (account.getSummary().getAccountBalance().signum() <= 0
                    || (closureType.isReinvestPrincipal() && principalOf(account).signum() <= 0)) {
                throw new GeneralPlatformDomainRuleException("error.msg.dynamicdepositaccount.closure.nothing.to.reinvest",
                        "The Dynamic Deposit account has no balance to reinvest.", account.getId());
            }
        }
        if (isPreMatureClosure && account.getSummary().getAccountBalance().signum() > 0 && account.isWithdrawalBlockedByAccountRule()) {
            throw new GeneralPlatformDomainRuleException("error.msg.savings.account.close.withdrawal.not.allowed.account.rule",
                    "This account cannot be closed with a fund withdrawal while withdrawals are disabled for it.", account.getId());
        }
    }

    /**
     * The destination of a closure transfer must be an active savings account of the same client - or, for a group
     * account, the same group - as the deposit being closed. Currency is checked by the transfer itself.
     */
    private SavingsAccount resolveTransferDestination(final DynamicDepositAccount account, final JsonCommand command) {
        final Long toSavingsAccountId = command.longValueOfParameterNamed(toSavingsAccountIdParamName);
        final SavingsAccount destination = this.depositAccountAssembler.assembleFrom(toSavingsAccountId,
                DepositAccountType.SAVINGS_DEPOSIT);
        if (!destination.isActive()) {
            throw new GeneralPlatformDomainRuleException("error.msg.dynamicdepositaccount.closure.transfer.destination.not.active",
                    "The savings account to transfer the closure proceeds to must be active.", toSavingsAccountId);
        }
        final boolean sameClient = account.clientId() != null && Objects.equals(account.clientId(), destination.clientId());
        final boolean sameGroup = account.groupId() != null && Objects.equals(account.groupId(), destination.groupId());
        if (!sameClient && !sameGroup) {
            throw new GeneralPlatformDomainRuleException(
                    "error.msg.dynamicdepositaccount.closure.transfer.destination.must.belong.to.same.client",
                    "The savings account to transfer the closure proceeds to must belong to the same client as the deposit account.",
                    toSavingsAccountId);
        }
        return destination;
    }

    /** The savings account interest is transferred to, or {@code null} when the account has none. */
    private SavingsAccount resolveLinkedAccount(final DynamicDepositAccount account) {
        final PortfolioAccountData linked = this.accountAssociationsReadPlatformService.retriveSavingsLinkedAssociation(account.getId());
        if (linked == null) {
            return null;
        }
        final SavingsAccount destination = this.depositAccountAssembler.assembleFrom(linked.getId(), DepositAccountType.SAVINGS_DEPOSIT);
        if (!destination.isActive()) {
            throw new GeneralPlatformDomainRuleException("error.msg.dynamicdepositaccount.closure.linked.account.not.active",
                    "The linked savings account the interest is sent to must be active.", linked.getId());
        }
        return destination;
    }

    private void transferPayout(final DynamicDepositAccount account, final SavingsAccount destination, final BigDecimal payout,
            final LocalDate closedDate, final DateTimeFormatter fmt, final Locale locale, final String transferDescription) {
        final boolean isRegularTransaction = false;
        final boolean isExceptionForBalanceCheck = false;
        final AccountTransferDTO accountTransferDTO = new AccountTransferDTO(closedDate, payout, PortfolioAccountType.SAVINGS,
                PortfolioAccountType.SAVINGS, null, null, transferDescription, locale, fmt, null, null, null, null, null,
                AccountTransferType.ACCOUNT_TRANSFER.getValue(), null, null, ExternalId.empty(), null, destination, account,
                isRegularTransaction, isExceptionForBalanceCheck);
        this.accountTransfersWritePlatformService.transferFunds(accountTransferDTO);
    }

    /**
     * What core's {@code close()} does for its closure interest: post through the account's own
     * {@code postInterest(...)}, bounded by the closure date rather than today's business date so a backdated closure
     * cannot earn interest for days after it. Core keeps that method private, hence this copy.
     */
    private void postClosureInterest(final DynamicDepositAccount account, final LocalDate postingDate,
            final LocalDate interestPostingUpToDate) {
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
        account.postInterest(mc, interestPostingUpToDate, isInterestTransfer, isSavingsInterestPostingAtCurrentPeriodEnd,
                financialYearBeginningMonth, postingDate, backdatedTxnsAllowedTill, postReversals);
        // Tax configured to be withheld at maturity is not written by the posting above; write it with the interest.
        account.withholdMaturityTaxAtClosure(postingDate);

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

    @Override
    public SavingsAccountTransaction handleWithdrawal(final SavingsAccount account, final DateTimeFormatter fmt,
            final LocalDate transactionDate, final BigDecimal transactionAmount, final PaymentDetail paymentDetail,
            final boolean applyWithdrawFee, final boolean isRegularTransaction) {
        return this.coreDomainService.handleWithdrawal(account, fmt, transactionDate, transactionAmount, paymentDetail, applyWithdrawFee,
                isRegularTransaction);
    }

    @Override
    public SavingsAccountTransaction handleFDDeposit(final FixedDepositAccount account, final DateTimeFormatter fmt,
            final LocalDate transactionDate, final BigDecimal transactionAmount, final PaymentDetail paymentDetail) {
        return this.coreDomainService.handleFDDeposit(account, fmt, transactionDate, transactionAmount, paymentDetail);
    }

    @Override
    public SavingsAccountTransaction handleRDDeposit(final RecurringDepositAccount account, final DateTimeFormatter fmt,
            final LocalDate transactionDate, final BigDecimal transactionAmount, final PaymentDetail paymentDetail,
            final boolean isRegularTransaction) {
        return this.coreDomainService.handleRDDeposit(account, fmt, transactionDate, transactionAmount, paymentDetail,
                isRegularTransaction);
    }

    @Override
    public SavingsAccountTransaction handleSavingDeposit(final SavingsAccount account, final DateTimeFormatter fmt,
            final LocalDate transactionDate, final BigDecimal transactionAmount, final PaymentDetail paymentDetail,
            final boolean isRegularTransaction) {
        return this.coreDomainService.handleSavingDeposit(account, fmt, transactionDate, transactionAmount, paymentDetail,
                isRegularTransaction);
    }

    @Override
    public Long handleFDAccountClosure(final FixedDepositAccount account, final PaymentDetail paymentDetail, final AppUser user,
            final JsonCommand command, final Map<String, Object> changes) {
        return this.coreDomainService.handleFDAccountClosure(account, paymentDetail, user, command, changes);
    }

    @Override
    public Long handleFDAccountMaturityClosure(final FixedDepositAccount account, final PaymentDetail paymentDetail, final AppUser user,
            final DateTimeFormatter fmt, final LocalDate closedDate, final Integer onAccountClosureId, final Long toSavingsId,
            final String transferDescription, final Map<String, Object> changes) {
        return this.coreDomainService.handleFDAccountMaturityClosure(account, paymentDetail, user, fmt, closedDate, onAccountClosureId,
                toSavingsId, transferDescription, changes);
    }

    @Override
    public Long handleRDAccountClosure(final RecurringDepositAccount account, final PaymentDetail paymentDetail, final AppUser user,
            final JsonCommand command, final Map<String, Object> changes) {
        return this.coreDomainService.handleRDAccountClosure(account, paymentDetail, user, command, changes);
    }

    @Override
    public Long handleFDAccountPreMatureClosure(final FixedDepositAccount account, final PaymentDetail paymentDetail, final AppUser user,
            final JsonCommand command, final Map<String, Object> changes) {
        return this.coreDomainService.handleFDAccountPreMatureClosure(account, paymentDetail, user, command, changes);
    }

    @Override
    public Long handleRDAccountPreMatureClosure(final RecurringDepositAccount account, final PaymentDetail paymentDetail,
            final AppUser user, final JsonCommand command, final Map<String, Object> changes) {
        return this.coreDomainService.handleRDAccountPreMatureClosure(account, paymentDetail, user, command, changes);
    }
}
