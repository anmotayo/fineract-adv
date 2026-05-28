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
package com.advancly.fineract.portfolio.account.service;

import static org.apache.fineract.portfolio.account.AccountDetailConstants.fromAccountIdParamName;
import static org.apache.fineract.portfolio.account.AccountDetailConstants.fromAccountTypeParamName;
import static org.apache.fineract.portfolio.account.AccountDetailConstants.toAccountIdParamName;
import static org.apache.fineract.portfolio.account.AccountDetailConstants.toAccountTypeParamName;
import static org.apache.fineract.portfolio.account.api.AccountTransfersApiConstants.transferAmountParamName;
import static org.apache.fineract.portfolio.account.api.AccountTransfersApiConstants.transferDateParamName;

import com.advancly.fineract.portfolio.savings.domain.AdvanclySavingsAccountAssembler;
import com.advancly.fineract.portfolio.savings.domain.AdvanclySavingsAccountTransactionRepository;
import com.advancly.fineract.portfolio.savings.domain.AssembledSavingsAccount;
import com.advancly.fineract.portfolio.savings.service.AdvanclySavingsAccountDomainService;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Collection;
import java.util.Locale;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.fineract.infrastructure.configuration.domain.ConfigurationDomainService;
import org.apache.fineract.infrastructure.core.api.JsonCommand;
import org.apache.fineract.infrastructure.core.data.CommandProcessingResult;
import org.apache.fineract.infrastructure.core.data.CommandProcessingResultBuilder;
import org.apache.fineract.infrastructure.core.domain.ExternalId;
import org.apache.fineract.infrastructure.core.exception.GeneralPlatformDomainRuleException;
import org.apache.fineract.infrastructure.core.service.ExternalIdFactory;
import org.apache.fineract.organisation.monetary.domain.Money;
import org.apache.fineract.portfolio.account.PortfolioAccountType;
import org.apache.fineract.portfolio.account.data.AccountTransferDTO;
import org.apache.fineract.portfolio.account.data.AccountTransfersDataValidator;
import org.apache.fineract.portfolio.account.domain.AccountTransferAssembler;
import org.apache.fineract.portfolio.account.domain.AccountTransferDetailRepository;
import org.apache.fineract.portfolio.account.domain.AccountTransferDetails;
import org.apache.fineract.portfolio.account.domain.AccountTransferType;
import org.apache.fineract.portfolio.account.exception.DifferentCurrenciesException;
import org.apache.fineract.portfolio.account.service.AccountTransfersWritePlatformService;
import org.apache.fineract.portfolio.loanaccount.data.HolidayDetailDTO;
import org.apache.fineract.portfolio.loanaccount.domain.Loan;
import org.apache.fineract.portfolio.loanaccount.domain.LoanAccountDomainService;
import org.apache.fineract.portfolio.loanaccount.domain.LoanTransaction;
import org.apache.fineract.portfolio.loanaccount.domain.LoanTransactionType;
import org.apache.fineract.portfolio.loanaccount.exception.InvalidPaidInAdvanceAmountException;
import org.apache.fineract.portfolio.loanaccount.service.LoanAssembler;
import org.apache.fineract.portfolio.loanaccount.service.LoanReadPlatformService;
import org.apache.fineract.portfolio.paymentdetail.domain.PaymentDetail;
import org.apache.fineract.portfolio.savings.SavingsTransactionBooleanValues;
import org.apache.fineract.portfolio.savings.domain.GSIMRepositoy;
import org.apache.fineract.portfolio.savings.domain.GroupSavingsIndividualMonitoring;
import org.apache.fineract.portfolio.savings.domain.SavingsAccount;
import org.apache.fineract.portfolio.savings.domain.SavingsAccountAssembler;
import org.apache.fineract.portfolio.savings.domain.SavingsAccountTransaction;
import org.apache.fineract.portfolio.savings.service.SavingsAccountDomainService;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@SuppressWarnings("SpringJavaInjectionPointsAutowiringInspection")
@Slf4j
@RequiredArgsConstructor
@Service
@Primary
public class AdvanclyAccountTransfersWritePlatformService implements AccountTransfersWritePlatformService {

    private final AccountTransfersDataValidator accountTransfersDataValidator;
    private final AccountTransferAssembler accountTransferAssembler;
    private final AdvanclySavingsAccountAssembler savingsAccountAssembler;
    private final SavingsAccountAssembler coreSavingsAccountAssembler;
    private final AdvanclySavingsAccountDomainService savingsAccountDomainService;
    private final LoanAssembler loanAccountAssembler;
    private final LoanAccountDomainService loanAccountDomainService;
    private final AccountTransferDetailRepository accountTransferDetailRepository;
    private final LoanReadPlatformService loanReadPlatformService;
    private final GSIMRepositoy gsimRepository;
    private final ConfigurationDomainService configurationDomainService;
    private final ExternalIdFactory externalIdFactory;
    private final AdvanclySavingsAccountTransactionRepository savingsAccountTransactionRepository;
    @Qualifier("coreSavingsAccountDomainService")
    private final SavingsAccountDomainService coreDomainService;
    @Qualifier("coreAccountTransfersWritePlatformService")
    private final AccountTransfersWritePlatformService coreAccountTransfersWritePlatformService;

    @Transactional
    @Override
    public CommandProcessingResult create(final JsonCommand command) {
        this.accountTransfersDataValidator.validate(command);

        final LocalDate transactionDate = command.localDateValueOfParameterNamed(transferDateParamName);
        final BigDecimal transactionAmount = command.bigDecimalValueOfParameterNamed(transferAmountParamName);

        final Integer fromAccountTypeId = command.integerValueSansLocaleOfParameterNamed(fromAccountTypeParamName);
        final PortfolioAccountType fromAccountType = PortfolioAccountType.fromInt(fromAccountTypeId);

        final Locale locale = command.extractLocale();
        final DateTimeFormatter fmt = DateTimeFormatter.ofPattern(command.dateFormat()).withLocale(locale);

        final Integer toAccountTypeId = command.integerValueSansLocaleOfParameterNamed(toAccountTypeParamName);
        final PortfolioAccountType toAccountType = PortfolioAccountType.fromInt(toAccountTypeId);

        boolean isInterestTransfer = false;
        boolean isRegularTransaction = true;
        boolean isAccountTransfer = true;
        boolean isWithdrawBalance = false;
        final boolean backdatedTxnsAllowedTill = false;

        if (isSavingsToSavingsAccountTransfer(fromAccountType, toAccountType)) {
            final Long fromSavingsAccountId = command.longValueOfParameterNamed(fromAccountIdParamName);
            final boolean fromAppendPath = isAppendPath(fromSavingsAccountId, transactionDate);
            final AssembledSavingsAccount fromAssembled = assembleSavingsAccount(fromSavingsAccountId, transactionDate,
                    backdatedTxnsAllowedTill, fromAppendPath);
            final SavingsAccount fromSavingsAccount = fromAssembled.getAccount();

            final Long toSavingsId = command.longValueOfParameterNamed(toAccountIdParamName);
            final boolean toAppendPath = isAppendPath(toSavingsId, transactionDate);
            final AssembledSavingsAccount toAssembled = assembleSavingsAccount(toSavingsId, transactionDate, backdatedTxnsAllowedTill,
                    toAppendPath);
            final SavingsAccount toSavingsAccount = toAssembled.getAccount();

            if (!fromSavingsAccount.getCurrency().getCode().equals(toSavingsAccount.getCurrency().getCode())) {
                throw new DifferentCurrenciesException(fromSavingsAccount.getCurrency().getCode(),
                        toSavingsAccount.getCurrency().getCode());
            }

            final SavingsTransactionBooleanValues transactionBooleanValues = new SavingsTransactionBooleanValues(isAccountTransfer,
                    isRegularTransaction, fromSavingsAccount.isWithdrawalFeeApplicableForTransfer(), isInterestTransfer, isWithdrawBalance);

            final SavingsAccountTransaction withdrawal = postOptimizedWithdrawal(fromAssembled, fmt, transactionDate, transactionAmount,
                    transactionBooleanValues, backdatedTxnsAllowedTill, fromAppendPath);
            final SavingsAccountTransaction deposit = postOptimizedDeposit(toAssembled, fmt, transactionDate, transactionAmount,
                    transactionBooleanValues, backdatedTxnsAllowedTill, toAppendPath);

            final AccountTransferDetails accountTransferDetails = this.accountTransferAssembler.assembleSavingsToSavingsTransfer(command,
                    fromSavingsAccount, toSavingsAccount, withdrawal, deposit);
            this.accountTransferDetailRepository.saveAndFlush(accountTransferDetails);

            return new CommandProcessingResultBuilder().withEntityId(accountTransferDetails.getId()).withSavingsId(fromSavingsAccountId)
                    .build();
        } else if (isSavingsToLoanAccountTransfer(fromAccountType, toAccountType)) {
            final Long fromSavingsAccountId = command.longValueOfParameterNamed(fromAccountIdParamName);
            final boolean fromAppendPath = isAppendPath(fromSavingsAccountId, transactionDate);
            final AssembledSavingsAccount fromAssembled = assembleSavingsAccount(fromSavingsAccountId, transactionDate,
                    backdatedTxnsAllowedTill, fromAppendPath);
            final SavingsAccount fromSavingsAccount = fromAssembled.getAccount();
            final PaymentDetail paymentDetail = null;

            final SavingsTransactionBooleanValues transactionBooleanValues = new SavingsTransactionBooleanValues(isAccountTransfer,
                    isRegularTransaction, fromSavingsAccount.isWithdrawalFeeApplicableForTransfer(), isInterestTransfer, isWithdrawBalance);

            final SavingsAccountTransaction withdrawal = postOptimizedWithdrawal(fromAssembled, fmt, transactionDate, transactionAmount,
                    transactionBooleanValues, backdatedTxnsAllowedTill, fromAppendPath);

            final Long toLoanAccountId = command.longValueOfParameterNamed(toAccountIdParamName);
            Loan toLoanAccount = this.loanAccountAssembler.assembleFrom(toLoanAccountId);
            final ExternalId externalId = externalIdFactory.create();
            final LoanTransaction loanRepaymentTransaction = this.loanAccountDomainService.makeRepayment(LoanTransactionType.REPAYMENT,
                    toLoanAccount, transactionDate, transactionAmount, paymentDetail, null, externalId, false, null, true, null, false);
            toLoanAccount = loanRepaymentTransaction.getLoan();

            final AccountTransferDetails accountTransferDetails = this.accountTransferAssembler.assembleSavingsToLoanTransfer(command,
                    fromSavingsAccount, toLoanAccount, withdrawal, loanRepaymentTransaction);
            this.accountTransferDetailRepository.saveAndFlush(accountTransferDetails);

            return new CommandProcessingResultBuilder().withEntityId(accountTransferDetails.getId()).withSavingsId(fromSavingsAccountId)
                    .build();
        } else if (isLoanToSavingsAccountTransfer(fromAccountType, toAccountType)) {
            final Long fromLoanAccountId = command.longValueOfParameterNamed(fromAccountIdParamName);
            final Loan fromLoanAccount = this.loanAccountAssembler.assembleFrom(fromLoanAccountId);
            final PaymentDetail paymentDetail = null;

            final ExternalId externalId = externalIdFactory.create();
            final LoanTransaction loanRefundTransaction = this.loanAccountDomainService.makeRefund(fromLoanAccountId,
                    new CommandProcessingResultBuilder(), transactionDate, transactionAmount, paymentDetail, null, externalId);

            final Long toSavingsAccountId = command.longValueOfParameterNamed(toAccountIdParamName);
            final boolean toAppendPath = isAppendPath(toSavingsAccountId, transactionDate);
            final AssembledSavingsAccount toAssembled = assembleSavingsAccount(toSavingsAccountId, transactionDate,
                    backdatedTxnsAllowedTill, toAppendPath);
            final SavingsAccount toSavingsAccount = toAssembled.getAccount();
            final SavingsTransactionBooleanValues transactionBooleanValues = new SavingsTransactionBooleanValues(isAccountTransfer,
                    isRegularTransaction, false, isInterestTransfer, isWithdrawBalance);
            final SavingsAccountTransaction deposit = postOptimizedDeposit(toAssembled, fmt, transactionDate, transactionAmount,
                    transactionBooleanValues, backdatedTxnsAllowedTill, toAppendPath);

            final AccountTransferDetails accountTransferDetails = this.accountTransferAssembler.assembleLoanToSavingsTransfer(command,
                    fromLoanAccount, toSavingsAccount, deposit, loanRefundTransaction);
            this.accountTransferDetailRepository.saveAndFlush(accountTransferDetails);

            return new CommandProcessingResultBuilder().withEntityId(accountTransferDetails.getId()).withLoanId(fromLoanAccountId).build();
        }

        throw new GeneralPlatformDomainRuleException("error.msg.accounttransfer.unsupported.optimized.path",
                "Optimized account transfer path is not yet implemented for this account transfer type");
    }

    private boolean isAppendPath(final Long savingsId, final LocalDate transactionDate) {
        final Optional<LocalDate> lastTransactionDate = savingsAccountTransactionRepository.findLastTransactionDate(savingsId);
        return lastTransactionDate.isEmpty() || !transactionDate.isBefore(lastTransactionDate.get());
    }

    private AssembledSavingsAccount assembleSavingsAccount(final Long savingsId, final LocalDate transactionDate,
            final boolean backdatedTxnsAllowedTill, final boolean appendPath) {
        if (appendPath) {
            return savingsAccountAssembler.assembleForAppendPath(savingsId);
        }
        final SavingsAccount account = coreSavingsAccountAssembler.assembleFrom(savingsId, backdatedTxnsAllowedTill);
        return AssembledSavingsAccount.of(account, null);
    }

    private SavingsAccountTransaction postOptimizedDeposit(final AssembledSavingsAccount assembled, final DateTimeFormatter fmt,
            final LocalDate transactionDate, final BigDecimal transactionAmount,
            final SavingsTransactionBooleanValues transactionBooleanValues, final boolean backdatedTxnsAllowedTill,
            final boolean appendPath) {
        final SavingsAccount account = assembled.getAccount();
        final PaymentDetail paymentDetail = null;

        if (appendPath) {
            Money lastRunningBalance = Money.of(account.getCurrency(), account.getSummary().getRunningBalanceOnPivotDate());
            return savingsAccountDomainService.handleDepositOptimized(account, transactionDate, transactionAmount, paymentDetail,
                    lastRunningBalance, account.getCurrency(), assembled.getLastNonReversedTransaction(),
                    transactionBooleanValues.isAccountTransfer());
        }

        return coreDomainService.handleDeposit(account, fmt, transactionDate, transactionAmount, paymentDetail,
                transactionBooleanValues.isAccountTransfer(), transactionBooleanValues.isRegularTransaction(), backdatedTxnsAllowedTill);
    }

    private SavingsAccountTransaction postOptimizedWithdrawal(final AssembledSavingsAccount assembled, final DateTimeFormatter fmt,
            final LocalDate transactionDate, final BigDecimal transactionAmount,
            final SavingsTransactionBooleanValues transactionBooleanValues, final boolean backdatedTxnsAllowedTill,
            final boolean appendPath) {
        final SavingsAccount account = assembled.getAccount();
        final PaymentDetail paymentDetail = null;
        if (appendPath) {
            Money lastRunningBalance = Money.of(account.getCurrency(), account.getSummary().getRunningBalanceOnPivotDate());
            return savingsAccountDomainService.handleWithdrawalOptimized(account, transactionDate, transactionAmount, paymentDetail,
                    transactionBooleanValues.isApplyWithdrawFee(), lastRunningBalance, account.getCurrency(),
                    assembled.getLastNonReversedTransaction(), transactionBooleanValues.isAccountTransfer());
        }

        return coreDomainService.handleWithdrawal(account, fmt, transactionDate, transactionAmount, paymentDetail, transactionBooleanValues,
                backdatedTxnsAllowedTill);
    }

    private boolean isSavingsToSavingsAccountTransfer(final PortfolioAccountType fromAccountType,
            final PortfolioAccountType toAccountType) {
        return fromAccountType.isSavingsAccount() && toAccountType.isSavingsAccount();
    }

    private boolean isLoanToSavingsAccountTransfer(final PortfolioAccountType fromAccountType, final PortfolioAccountType toAccountType) {
        return fromAccountType.isLoanAccount() && toAccountType.isSavingsAccount();
    }

    private boolean isSavingsToLoanAccountTransfer(final PortfolioAccountType fromAccountType, final PortfolioAccountType toAccountType) {
        return fromAccountType.isSavingsAccount() && toAccountType.isLoanAccount();
    }

    @Transactional
    @Override
    public void reverseTransfersWithFromAccountType(final Long accountNumber, final PortfolioAccountType accountTypeId) {
        coreAccountTransfersWritePlatformService.reverseTransfersWithFromAccountType(accountNumber, accountTypeId);
    }

    @Transactional
    @Override
    public Long transferFunds(final AccountTransferDTO accountTransferDTO) {
        Long transferTransactionId;
        AccountTransferDetails accountTransferDetails = accountTransferDTO.getAccountTransferDetails();
        final boolean isAccountTransfer = true;
        final boolean isRegularTransaction = accountTransferDTO.isRegularTransaction();
        final boolean backdatedTxnsAllowedTill = false;

        if (isSavingsToLoanAccountTransfer(accountTransferDTO.getFromAccountType(), accountTransferDTO.getToAccountType())) {
            final Long fromSavingsAccountId = fromSavingsAccountId(accountTransferDTO, accountTransferDetails);
            final boolean fromAppendPath = isAppendPath(fromSavingsAccountId, accountTransferDTO.getTransactionDate());
            final AssembledSavingsAccount fromAssembled = assembleSavingsAccount(fromSavingsAccountId,
                    accountTransferDTO.getTransactionDate(), backdatedTxnsAllowedTill, fromAppendPath);
            final SavingsAccount fromSavingsAccount = fromAssembled.getAccount();

            Loan toLoanAccount = resolveToLoanAccount(accountTransferDTO, accountTransferDetails);

            final SavingsTransactionBooleanValues transactionBooleanValues = new SavingsTransactionBooleanValues(isAccountTransfer,
                    isRegularTransaction, fromSavingsAccount.isWithdrawalFeeApplicableForTransfer(),
                    AccountTransferType.fromInt(accountTransferDTO.getTransferType()).isInterestTransfer(),
                    accountTransferDTO.isExceptionForBalanceCheck());

            final SavingsAccountTransaction withdrawal = postOptimizedWithdrawal(fromAssembled, accountTransferDTO.getFmt(),
                    accountTransferDTO.getTransactionDate(), accountTransferDTO.getTransactionAmount(), transactionBooleanValues,
                    backdatedTxnsAllowedTill, fromAppendPath);

            final LoanTransaction loanTransaction = makeLoanTransactionForSavingsToLoanTransfer(accountTransferDTO, toLoanAccount);
            if (!AccountTransferType.fromInt(accountTransferDTO.getTransferType()).isChargePayment()) {
                toLoanAccount = loanTransaction.getLoan();
            }

            accountTransferDetails = this.accountTransferAssembler.assembleSavingsToLoanTransfer(accountTransferDTO, fromSavingsAccount,
                    toLoanAccount, withdrawal, loanTransaction);
            this.accountTransferDetailRepository.saveAndFlush(accountTransferDetails);
            transferTransactionId = accountTransferDetails.getId();
        } else if (isSavingsToSavingsAccountTransfer(accountTransferDTO.getFromAccountType(), accountTransferDTO.getToAccountType())) {
            LocalDate transactionDate = resolveSavingsToSavingsTransferDate(accountTransferDTO);
            final Long fromSavingsAccountId = fromSavingsAccountId(accountTransferDTO, accountTransferDetails);
            final boolean fromAppendPath = isAppendPath(fromSavingsAccountId, transactionDate);
            final AssembledSavingsAccount fromAssembled = assembleSavingsAccount(fromSavingsAccountId, transactionDate,
                    backdatedTxnsAllowedTill, fromAppendPath);
            final SavingsAccount fromSavingsAccount = fromAssembled.getAccount();

            final Long toSavingsAccountId = toSavingsAccountId(accountTransferDTO, accountTransferDetails);
            final boolean toAppendPath = isAppendPath(toSavingsAccountId, transactionDate);
            final AssembledSavingsAccount toAssembled = assembleSavingsAccount(toSavingsAccountId, transactionDate,
                    backdatedTxnsAllowedTill, toAppendPath);
            final SavingsAccount toSavingsAccount = toAssembled.getAccount();

            if (!fromSavingsAccount.getCurrency().getCode().equals(toSavingsAccount.getCurrency().getCode())) {
                throw new DifferentCurrenciesException(fromSavingsAccount.getCurrency().getCode(),
                        toSavingsAccount.getCurrency().getCode());
            }

            log.info("Transfer funds from {} to {}", fromSavingsAccount.getId(), toSavingsAccount.getId());

            final SavingsTransactionBooleanValues transactionBooleanValues = new SavingsTransactionBooleanValues(isAccountTransfer,
                    isRegularTransaction, fromSavingsAccount.isWithdrawalFeeApplicableForTransfer(),
                    AccountTransferType.fromInt(accountTransferDTO.getTransferType()).isInterestTransfer(),
                    accountTransferDTO.isExceptionForBalanceCheck());

            final SavingsAccountTransaction withdrawal = postOptimizedWithdrawal(fromAssembled, accountTransferDTO.getFmt(),
                    transactionDate, accountTransferDTO.getTransactionAmount(), transactionBooleanValues, backdatedTxnsAllowedTill,
                    fromAppendPath);

            final SavingsAccountTransaction deposit = postOptimizedDeposit(toAssembled, accountTransferDTO.getFmt(), transactionDate,
                    accountTransferDTO.getTransactionAmount(), transactionBooleanValues, backdatedTxnsAllowedTill, toAppendPath);

            accountTransferDetails = this.accountTransferAssembler.assembleSavingsToSavingsTransfer(accountTransferDTO, fromSavingsAccount,
                    toSavingsAccount, withdrawal, deposit);
            this.accountTransferDetailRepository.saveAndFlush(accountTransferDetails);
            transferTransactionId = accountTransferDetails.getId();
        } else if (isLoanToSavingsAccountTransfer(accountTransferDTO.getFromAccountType(), accountTransferDTO.getToAccountType())) {
            final Loan fromLoanAccount = resolveFromLoanAccount(accountTransferDTO, accountTransferDetails);

            final LoanTransaction loanTransaction = makeLoanTransactionForLoanToSavingsTransfer(accountTransferDTO);

            final Long toSavingsAccountId = toSavingsAccountId(accountTransferDTO, accountTransferDetails);
            final boolean toAppendPath = isAppendPath(toSavingsAccountId, accountTransferDTO.getTransactionDate());
            final AssembledSavingsAccount toAssembled = assembleSavingsAccount(toSavingsAccountId, accountTransferDTO.getTransactionDate(),
                    backdatedTxnsAllowedTill, toAppendPath);
            final SavingsAccount toSavingsAccount = toAssembled.getAccount();

            final SavingsTransactionBooleanValues transactionBooleanValues = new SavingsTransactionBooleanValues(isAccountTransfer,
                    isRegularTransaction, false, AccountTransferType.fromInt(accountTransferDTO.getTransferType()).isInterestTransfer(),
                    accountTransferDTO.isExceptionForBalanceCheck());
            final SavingsAccountTransaction deposit = postOptimizedDeposit(toAssembled, accountTransferDTO.getFmt(),
                    accountTransferDTO.getTransactionDate(), accountTransferDTO.getTransactionAmount(), transactionBooleanValues,
                    backdatedTxnsAllowedTill, toAppendPath);
            accountTransferDetails = this.accountTransferAssembler.assembleLoanToSavingsTransfer(accountTransferDTO, fromLoanAccount,
                    toSavingsAccount, deposit, loanTransaction);
            this.accountTransferDetailRepository.saveAndFlush(accountTransferDetails);
            transferTransactionId = accountTransferDetails.getId();

            handleGsimDeposit(toSavingsAccount, accountTransferDTO.getTransactionAmount());
        } else {
            throw new GeneralPlatformDomainRuleException("error.msg.accounttransfer.loan.to.loan.not.supported",
                    "Account transfer from loan to another loan is not supported");
        }

        return transferTransactionId;
    }

    @Transactional
    @Override
    public void reverseAllTransactions(final Long accountId, final PortfolioAccountType accountTypeId) {
        coreAccountTransfersWritePlatformService.reverseAllTransactions(accountId, accountTypeId);
    }

    @Transactional
    @Override
    public CommandProcessingResult refundByTransfer(final JsonCommand command) {
        this.accountTransfersDataValidator.validate(command);

        final LocalDate transactionDate = command.localDateValueOfParameterNamed(transferDateParamName);
        final BigDecimal transactionAmount = command.bigDecimalValueOfParameterNamed(transferAmountParamName);

        final Locale locale = command.extractLocale();
        final DateTimeFormatter fmt = DateTimeFormatter.ofPattern(command.dateFormat()).withLocale(locale);

        final PaymentDetail paymentDetail = null;

        final Long fromLoanAccountId = command.longValueOfParameterNamed(fromAccountIdParamName);
        final Loan fromLoanAccount = this.loanAccountAssembler.assembleFrom(fromLoanAccountId);

        BigDecimal overpaid = this.loanReadPlatformService.retrieveTotalPaidInAdvance(fromLoanAccountId).getPaidInAdvance();
        final boolean backdatedTxnsAllowedTill = false;

        if (overpaid == null || overpaid.compareTo(BigDecimal.ZERO) == 0 || transactionAmount.floatValue() > overpaid.floatValue()) {
            if (overpaid == null) {
                overpaid = BigDecimal.ZERO;
            }
            throw new InvalidPaidInAdvanceAmountException(overpaid.toPlainString());
        }

        ExternalId externalId = externalIdFactory.create();

        final LoanTransaction loanRefundTransaction = this.loanAccountDomainService.makeRefundForActiveLoan(fromLoanAccountId,
                new CommandProcessingResultBuilder(), transactionDate, transactionAmount, paymentDetail, null, externalId);

        final Long toSavingsAccountId = command.longValueOfParameterNamed(toAccountIdParamName);
        final boolean toAppendPath = isAppendPath(toSavingsAccountId, transactionDate);
        final AssembledSavingsAccount toAssembled = assembleSavingsAccount(toSavingsAccountId, transactionDate, backdatedTxnsAllowedTill,
                toAppendPath);
        final SavingsAccount toSavingsAccount = toAssembled.getAccount();

        final SavingsTransactionBooleanValues transactionBooleanValues = new SavingsTransactionBooleanValues(true, true, false, false,
                false);
        final SavingsAccountTransaction deposit = postOptimizedDeposit(toAssembled, fmt, transactionDate, transactionAmount,
                transactionBooleanValues, backdatedTxnsAllowedTill, toAppendPath);

        final AccountTransferDetails accountTransferDetails = this.accountTransferAssembler.assembleLoanToSavingsTransfer(command,
                fromLoanAccount, toSavingsAccount, deposit, loanRefundTransaction);
        this.accountTransferDetailRepository.saveAndFlush(accountTransferDetails);

        return new CommandProcessingResultBuilder().withEntityId(accountTransferDetails.getId()).withSavingsId(toSavingsAccountId).build();
    }

    @Transactional
    @Override
    public void reverseTransfersWithFromAccountTransactions(final Collection<Long> fromTransactionIds,
            final PortfolioAccountType accountTypeId) {
        coreAccountTransfersWritePlatformService.reverseTransfersWithFromAccountTransactions(fromTransactionIds, accountTypeId);
    }

    @Transactional
    @Override
    public AccountTransferDetails repayLoanWithTopup(final AccountTransferDTO accountTransferDTO) {
        return coreAccountTransfersWritePlatformService.repayLoanWithTopup(accountTransferDTO);
    }

    private Long fromSavingsAccountId(final AccountTransferDTO accountTransferDTO, final AccountTransferDetails accountTransferDetails) {
        if (accountTransferDTO.getFromAccountId() != null) {
            return accountTransferDTO.getFromAccountId();
        }
        if (accountTransferDTO.getFromSavingsAccount() != null) {
            return accountTransferDTO.getFromSavingsAccount().getId();
        }
        return accountTransferDetails.fromSavingsAccount().getId();
    }

    private Long toSavingsAccountId(final AccountTransferDTO accountTransferDTO, final AccountTransferDetails accountTransferDetails) {
        if (accountTransferDTO.getToAccountId() != null) {
            return accountTransferDTO.getToAccountId();
        }
        if (accountTransferDTO.getToSavingsAccount() != null) {
            return accountTransferDTO.getToSavingsAccount().getId();
        }
        return accountTransferDetails.toSavingsAccount().getId();
    }

    private Loan resolveToLoanAccount(final AccountTransferDTO accountTransferDTO, final AccountTransferDetails accountTransferDetails) {
        if (accountTransferDetails != null) {
            return accountTransferDetails.toLoanAccount();
        }
        if (accountTransferDTO.getLoan() != null) {
            return accountTransferDTO.getLoan();
        }
        return this.loanAccountAssembler.assembleFrom(accountTransferDTO.getToAccountId());
    }

    private Loan resolveFromLoanAccount(final AccountTransferDTO accountTransferDTO, final AccountTransferDetails accountTransferDetails) {
        if (accountTransferDetails != null) {
            return accountTransferDetails.fromLoanAccount();
        }
        if (accountTransferDTO.getLoan() != null) {
            return accountTransferDTO.getLoan();
        }
        return this.loanAccountAssembler.assembleFrom(accountTransferDTO.getFromAccountId());
    }

    private LoanTransaction makeLoanTransactionForSavingsToLoanTransfer(final AccountTransferDTO accountTransferDTO,
            final Loan toLoanAccount) {
        final boolean isAccountTransfer = true;
        final ExternalId txnExternalId = accountTransferDTO.getTxnExternalId();
        final ExternalId externalId = externalIdFactory.create(txnExternalId.getValue());

        if (AccountTransferType.fromInt(accountTransferDTO.getTransferType()).isChargePayment()) {
            return this.loanAccountDomainService.makeChargePayment(toLoanAccount, accountTransferDTO.getChargeId(),
                    accountTransferDTO.getTransactionDate(), accountTransferDTO.getTransactionAmount(),
                    accountTransferDTO.getPaymentDetail(), null, externalId, accountTransferDTO.getToTransferType(),
                    accountTransferDTO.getLoanInstallmentNumber());
        }

        final boolean isRecoveryRepayment = false;
        final Boolean isHolidayValidationDone = false;
        final HolidayDetailDTO holidayDetailDto = null;
        final String chargeRefundChargeType = null;
        final LoanTransactionType repaymentType = AccountTransferType.fromInt(accountTransferDTO.getTransferType()).isLoanDownPayment()
                ? LoanTransactionType.DOWN_PAYMENT
                : LoanTransactionType.REPAYMENT;
        return this.loanAccountDomainService.makeRepayment(repaymentType, toLoanAccount, accountTransferDTO.getTransactionDate(),
                accountTransferDTO.getTransactionAmount(), accountTransferDTO.getPaymentDetail(), null, externalId, isRecoveryRepayment,
                chargeRefundChargeType, isAccountTransfer, holidayDetailDto, isHolidayValidationDone);
    }

    private LoanTransaction makeLoanTransactionForLoanToSavingsTransfer(final AccountTransferDTO accountTransferDTO) {
        final ExternalId txnExternalId = accountTransferDTO.getTxnExternalId();
        final ExternalId externalId = externalIdFactory.create(txnExternalId.getValue());

        if (LoanTransactionType.DISBURSEMENT.getValue().equals(accountTransferDTO.getFromTransferType())) {
            return this.loanAccountDomainService.makeDisburseTransaction(accountTransferDTO.getFromAccountId(),
                    accountTransferDTO.getTransactionDate(), accountTransferDTO.getTransactionAmount(),
                    accountTransferDTO.getPaymentDetail(), accountTransferDTO.getNoteText(), externalId);
        }
        return this.loanAccountDomainService.makeRefund(accountTransferDTO.getFromAccountId(), new CommandProcessingResultBuilder(),
                accountTransferDTO.getTransactionDate(), accountTransferDTO.getTransactionAmount(), accountTransferDTO.getPaymentDetail(),
                accountTransferDTO.getNoteText(), externalId);
    }

    private LocalDate resolveSavingsToSavingsTransferDate(final AccountTransferDTO accountTransferDTO) {
        LocalDate transactionDate = accountTransferDTO.getTransactionDate();
        if (configurationDomainService.isSavingsInterestPostingAtCurrentPeriodEnd()
                && configurationDomainService.isNextDayFixedDepositInterestTransferEnabledForPeriodEnd()
                && AccountTransferType.fromInt(accountTransferDTO.getTransferType()).isInterestTransfer()) {
            transactionDate = transactionDate.plusDays(1);
        }
        return transactionDate;
    }

    private void handleGsimDeposit(final SavingsAccount toSavingsAccount, final BigDecimal transactionAmount) {
        if (toSavingsAccount.getGsim() != null) {
            GroupSavingsIndividualMonitoring gsim = gsimRepository.findById(toSavingsAccount.getGsim().getId()).orElseThrow();
            BigDecimal currentBalance = gsim.getParentDeposit();
            BigDecimal newBalance = currentBalance.add(transactionAmount);
            gsim.setParentDeposit(newBalance);
            gsimRepository.save(gsim);
        }
    }
}
