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
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.fineract.infrastructure.configuration.domain.ConfigurationDomainService;
import org.apache.fineract.infrastructure.core.api.JsonCommand;
import org.apache.fineract.infrastructure.core.config.FineractProperties;
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
import org.apache.fineract.portfolio.account.domain.AccountTransferRepository;
import org.apache.fineract.portfolio.account.domain.AccountTransferTransaction;
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
import org.apache.fineract.portfolio.savings.domain.GSIMRepositoy;
import org.apache.fineract.portfolio.savings.domain.GroupSavingsIndividualMonitoring;
import org.apache.fineract.portfolio.savings.domain.SavingsAccount;
import org.apache.fineract.portfolio.savings.domain.SavingsAccountTransaction;
import org.apache.fineract.portfolio.savings.service.SavingsAccountWritePlatformService;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@RequiredArgsConstructor
public class AdvanclyAccountTransfersWritePlatformService implements AccountTransfersWritePlatformService {

    private final AccountTransfersDataValidator accountTransfersDataValidator;
    private final AccountTransferAssembler accountTransferAssembler;
    private final AccountTransferRepository accountTransferRepository;
    private final AdvanclySavingsAccountAssembler savingsAccountAssembler;
    private final AdvanclySavingsAccountDomainService savingsAccountDomainService;
    private final LoanAssembler loanAccountAssembler;
    private final LoanAccountDomainService loanAccountDomainService;
    private final SavingsAccountWritePlatformService savingsAccountWritePlatformService;
    private final AccountTransferDetailRepository accountTransferDetailRepository;
    private final LoanReadPlatformService loanReadPlatformService;
    private final GSIMRepositoy gsimRepository;
    private final ConfigurationDomainService configurationDomainService;
    private final ExternalIdFactory externalIdFactory;
    private final FineractProperties fineractProperties;
    private final AdvanclySavingsAccountTransactionRepository transactionRepository;
    private boolean isFromJob = false;

    @Transactional
    @Override
    public CommandProcessingResult create(final JsonCommand command) {
        this.accountTransfersDataValidator.validate(command);

        final LocalDate transactionDate = command.localDateValueOfParameterNamed(transferDateParamName);
        final BigDecimal transactionAmount = command.bigDecimalValueOfParameterNamed(transferAmountParamName);

        final Integer fromAccountTypeId = command.integerValueSansLocaleOfParameterNamed(fromAccountTypeParamName);
        final PortfolioAccountType fromAccountType = PortfolioAccountType.fromInt(fromAccountTypeId);

        final Integer toAccountTypeId = command.integerValueSansLocaleOfParameterNamed(toAccountTypeParamName);
        final PortfolioAccountType toAccountType = PortfolioAccountType.fromInt(toAccountTypeId);

        if (isSavingsToSavingsAccountTransfer(fromAccountType, toAccountType)) {
            final Long fromSavingsAccountId = command.longValueOfParameterNamed(fromAccountIdParamName);
            final AssembledSavingsAccount fromAssembled = assembleSavingsForPosting(fromSavingsAccountId, transactionDate);
            final SavingsAccount fromSavingsAccount = fromAssembled.getAccount();

            final Long toSavingsId = command.longValueOfParameterNamed(toAccountIdParamName);
            final AssembledSavingsAccount toAssembled = assembleSavingsForPosting(toSavingsId, transactionDate);
            final SavingsAccount toSavingsAccount = toAssembled.getAccount();

            if (!fromSavingsAccount.getCurrency().getCode().equals(toSavingsAccount.getCurrency().getCode())) {
                throw new DifferentCurrenciesException(fromSavingsAccount.getCurrency().getCode(),
                        toSavingsAccount.getCurrency().getCode());
            }

            final PaymentDetail paymentDetail = null;
            final SavingsAccountTransaction withdrawal = postOptimizedWithdrawal(fromAssembled, transactionDate, transactionAmount,
                    paymentDetail, fromSavingsAccount.isWithdrawalFeeApplicableForTransfer());
            final SavingsAccountTransaction deposit = postOptimizedDeposit(toAssembled, transactionDate, transactionAmount, paymentDetail);

            final AccountTransferDetails accountTransferDetails = this.accountTransferAssembler.assembleSavingsToSavingsTransfer(command,
                    fromSavingsAccount, toSavingsAccount, withdrawal, deposit);
            this.accountTransferDetailRepository.saveAndFlush(accountTransferDetails);

            return new CommandProcessingResultBuilder().withEntityId(accountTransferDetails.getId()).withSavingsId(fromSavingsAccountId)
                    .build();
        } else if (isSavingsToLoanAccountTransfer(fromAccountType, toAccountType)) {
            final Long fromSavingsAccountId = command.longValueOfParameterNamed(fromAccountIdParamName);
            final AssembledSavingsAccount fromAssembled = assembleSavingsForPosting(fromSavingsAccountId, transactionDate);
            final SavingsAccount fromSavingsAccount = fromAssembled.getAccount();
            final PaymentDetail paymentDetail = null;

            final SavingsAccountTransaction withdrawal = postOptimizedWithdrawal(fromAssembled, transactionDate, transactionAmount,
                    paymentDetail, fromSavingsAccount.isWithdrawalFeeApplicableForTransfer());

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
            final AssembledSavingsAccount toAssembled = assembleSavingsForPosting(toSavingsAccountId, transactionDate);
            final SavingsAccount toSavingsAccount = toAssembled.getAccount();
            final SavingsAccountTransaction deposit = postOptimizedDeposit(toAssembled, transactionDate, transactionAmount, paymentDetail);

            final AccountTransferDetails accountTransferDetails = this.accountTransferAssembler.assembleLoanToSavingsTransfer(command,
                    fromLoanAccount, toSavingsAccount, deposit, loanRefundTransaction);
            this.accountTransferDetailRepository.saveAndFlush(accountTransferDetails);

            return new CommandProcessingResultBuilder().withEntityId(accountTransferDetails.getId()).withLoanId(fromLoanAccountId).build();
        }

        throw new GeneralPlatformDomainRuleException("error.msg.accounttransfer.unsupported.optimized.path",
                "Optimized account transfer path is not yet implemented for this account transfer type");
    }

    private AssembledSavingsAccount assembleSavingsForPosting(final Long savingsId, final LocalDate transactionDate) {
        if (isAppendPath(savingsId, transactionDate)) {
            return savingsAccountAssembler.assembleForAppendPath(savingsId);
        }
        final SavingsAccount account = savingsAccountAssembler.assembleForAppendPath(savingsId).getAccount();
        final boolean hasInterest = account.hasInterestCalculation() || account.hasOverdraftInterestCalculation();
        return savingsAccountAssembler.assembleForInsertPath(savingsId, transactionDate, hasInterest);
    }

    private boolean isAppendPath(final Long savingsId, final LocalDate transactionDate) {
        final Optional<LocalDate> lastTransactionDate = transactionRepository.findLastTransactionDate(savingsId);
        return lastTransactionDate.isEmpty() || !transactionDate.isBefore(lastTransactionDate.get());
    }

    private SavingsAccountTransaction postOptimizedDeposit(final AssembledSavingsAccount assembled, final LocalDate transactionDate,
            final BigDecimal transactionAmount, final PaymentDetail paymentDetail) {
        final SavingsAccount account = assembled.getAccount();
        account.validateForAccountBlock();
        account.validateForCreditBlock();
        final Money lastRunningBalance = Money.of(account.getCurrency(), account.getSummary().getRunningBalanceOnPivotDate());
        return savingsAccountDomainService.handleDepositOptimized(account, transactionDate, transactionAmount, paymentDetail,
                assembled.getInterestAndOverdraftTransactions(), lastRunningBalance, account.getCurrency(),
                assembled.getLastNonReversedTransaction());
    }

    private SavingsAccountTransaction postOptimizedWithdrawal(final AssembledSavingsAccount assembled, final LocalDate transactionDate,
            final BigDecimal transactionAmount, final PaymentDetail paymentDetail, final boolean applyWithdrawFee) {
        final SavingsAccount account = assembled.getAccount();
        account.validateForAccountBlock();
        account.validateForDebitBlock();
        final Money lastRunningBalance = Money.of(account.getCurrency(), account.getSummary().getRunningBalanceOnPivotDate());
        return savingsAccountDomainService.handleWithdrawalOptimized(account, transactionDate, transactionAmount, paymentDetail,
                applyWithdrawFee, assembled.getInterestAndOverdraftTransactions(), lastRunningBalance, account.getCurrency(),
                assembled.getLastNonReversedTransaction());
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
    public CommandProcessingResult adjust(final JsonCommand command) {
        final Long accountTransferId = command.entityId();

        Optional<AccountTransferTransaction> optAccountTransfer = this.accountTransferRepository.findById(accountTransferId);
        if (optAccountTransfer.isEmpty()) {
            throw new GeneralPlatformDomainRuleException("error.msg.accounttransfer.was.not.found", "Account transfer was not found");
        }
        final boolean backdatedTxnsAllowedTill = this.configurationDomainService.retrievePivotDateConfig();

        AccountTransferTransaction accountTransfer = optAccountTransfer.get();
        if (accountTransfer.getToSavingsTransaction() != null) {
            log.info("Reverse savings transfer to {} {}", accountTransfer.getToSavingsTransaction().getSavingsAccount().getAccountNumber(),
                    accountTransfer.getToSavingsTransaction().getId());
            savingsAccountDomainService.reverseTransfer(accountTransfer.getToSavingsTransaction(), backdatedTxnsAllowedTill);
        }
        if (accountTransfer.getFromSavingsTransaction() != null) {
            log.info("Reverse savings transfer from {} {}",
                    accountTransfer.getFromSavingsTransaction().getSavingsAccount().getAccountNumber(),
                    accountTransfer.getFromSavingsTransaction().getId());
            savingsAccountDomainService.reverseTransfer(accountTransfer.getFromSavingsTransaction(), backdatedTxnsAllowedTill);
        }

        accountTransfer.reverse();
        this.accountTransferRepository.save(accountTransfer);

        return new CommandProcessingResultBuilder().withEntityId(accountTransferId).build();
    }

    @Transactional
    @Override
    public void reverseTransfersWithFromAccountType(final Long accountNumber, final PortfolioAccountType accountTypeId) {
        List<AccountTransferTransaction> accountTransfers = null;
        if (accountTypeId.isLoanAccount()) {
            accountTransfers = this.accountTransferRepository.findByFromLoanId(accountNumber);
        }
        if (accountTransfers != null && !accountTransfers.isEmpty()) {
            undoTransactions(accountTransfers);
        }
    }

    @Transactional
    @Override
    public Long transferFunds(final AccountTransferDTO accountTransferDTO) {
        Long transferTransactionId;
        AccountTransferDetails accountTransferDetails = accountTransferDTO.getAccountTransferDetails();

        if (isSavingsToLoanAccountTransfer(accountTransferDTO.getFromAccountType(), accountTransferDTO.getToAccountType())) {
            final Long fromSavingsAccountId = fromSavingsAccountId(accountTransferDTO, accountTransferDetails);
            final AssembledSavingsAccount fromAssembled = assembleSavingsForPosting(fromSavingsAccountId,
                    accountTransferDTO.getTransactionDate());
            final SavingsAccount fromSavingsAccount = fromAssembled.getAccount();

            Loan toLoanAccount = resolveToLoanAccount(accountTransferDTO, accountTransferDetails);

            final SavingsAccountTransaction withdrawal = postOptimizedWithdrawal(fromAssembled, accountTransferDTO.getTransactionDate(),
                    accountTransferDTO.getTransactionAmount(), accountTransferDTO.getPaymentDetail(),
                    fromSavingsAccount.isWithdrawalFeeApplicableForTransfer());

            final LoanTransaction loanTransaction = makeLoanTransactionForSavingsToLoanTransfer(accountTransferDTO, toLoanAccount);
            if (!AccountTransferType.fromInt(accountTransferDTO.getTransferType()).isChargePayment()) {
                toLoanAccount = loanTransaction.getLoan();
            }

            accountTransferDetails = this.accountTransferAssembler.assembleSavingsToLoanTransfer(accountTransferDTO, fromSavingsAccount,
                    toLoanAccount, withdrawal, loanTransaction);
            this.accountTransferDetailRepository.saveAndFlush(accountTransferDetails);
            transferTransactionId = accountTransferDetails.getId();
        } else if (isSavingsToSavingsAccountTransfer(accountTransferDTO.getFromAccountType(), accountTransferDTO.getToAccountType())) {
            final LocalDate transactionDate = resolveSavingsToSavingsTransferDate(accountTransferDTO);
            final Long fromSavingsAccountId = fromSavingsAccountId(accountTransferDTO, accountTransferDetails);
            final AssembledSavingsAccount fromAssembled = assembleSavingsForPosting(fromSavingsAccountId, transactionDate);
            final SavingsAccount fromSavingsAccount = fromAssembled.getAccount();

            final Long toSavingsAccountId = toSavingsAccountId(accountTransferDTO, accountTransferDetails);
            final AssembledSavingsAccount toAssembled = assembleSavingsForPosting(toSavingsAccountId, transactionDate);
            final SavingsAccount toSavingsAccount = toAssembled.getAccount();

            if (!fromSavingsAccount.getCurrency().getCode().equals(toSavingsAccount.getCurrency().getCode())) {
                throw new DifferentCurrenciesException(fromSavingsAccount.getCurrency().getCode(),
                        toSavingsAccount.getCurrency().getCode());
            }

            log.info("Transfer funds from {} to {}", fromSavingsAccount.getId(), toSavingsAccount.getId());

            final SavingsAccountTransaction withdrawal = postOptimizedWithdrawal(fromAssembled, transactionDate,
                    accountTransferDTO.getTransactionAmount(), accountTransferDTO.getPaymentDetail(),
                    fromSavingsAccount.isWithdrawalFeeApplicableForTransfer());

            final SavingsAccountTransaction deposit = postOptimizedDeposit(toAssembled, transactionDate,
                    accountTransferDTO.getTransactionAmount(), accountTransferDTO.getPaymentDetail());

            accountTransferDetails = this.accountTransferAssembler.assembleSavingsToSavingsTransfer(accountTransferDTO, fromSavingsAccount,
                    toSavingsAccount, withdrawal, deposit);
            this.accountTransferDetailRepository.saveAndFlush(accountTransferDetails);
            transferTransactionId = accountTransferDetails.getId();
        } else if (isLoanToSavingsAccountTransfer(accountTransferDTO.getFromAccountType(), accountTransferDTO.getToAccountType())) {
            final Loan fromLoanAccount = resolveFromLoanAccount(accountTransferDTO, accountTransferDetails);

            final LoanTransaction loanTransaction = makeLoanTransactionForLoanToSavingsTransfer(accountTransferDTO);

            final Long toSavingsAccountId = toSavingsAccountId(accountTransferDTO, accountTransferDetails);
            final AssembledSavingsAccount toAssembled = assembleSavingsForPosting(toSavingsAccountId,
                    accountTransferDTO.getTransactionDate());
            final SavingsAccount toSavingsAccount = toAssembled.getAccount();

            final SavingsAccountTransaction deposit = postOptimizedDeposit(toAssembled, accountTransferDTO.getTransactionDate(),
                    accountTransferDTO.getTransactionAmount(), accountTransferDTO.getPaymentDetail());
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
        List<AccountTransferTransaction> accountTransfers = null;
        if (accountTypeId.isLoanAccount()) {
            accountTransfers = this.accountTransferRepository.findAllByLoanId(accountId);
        }
        if (accountTransfers != null && !accountTransfers.isEmpty()) {
            undoTransactions(accountTransfers);
        }
    }

    @Transactional
    @Override
    public void updateLoanTransaction(final Long loanTransactionId, final LoanTransaction newLoanTransaction) {
        final AccountTransferTransaction transferTransaction = this.accountTransferRepository.findByToLoanTransactionId(loanTransactionId);
        if (transferTransaction != null) {
            transferTransaction.updateToLoanTransaction(newLoanTransaction);
            this.accountTransferRepository.save(transferTransaction);
        }
    }

    @Transactional
    @Override
    public CommandProcessingResult refundByTransfer(final JsonCommand command) {
        this.accountTransfersDataValidator.validate(command);

        final LocalDate transactionDate = command.localDateValueOfParameterNamed(transferDateParamName);
        final BigDecimal transactionAmount = command.bigDecimalValueOfParameterNamed(transferAmountParamName);

        final PaymentDetail paymentDetail = null;

        final Long fromLoanAccountId = command.longValueOfParameterNamed(fromAccountIdParamName);
        final Loan fromLoanAccount = this.loanAccountAssembler.assembleFrom(fromLoanAccountId);

        BigDecimal overpaid = this.loanReadPlatformService.retrieveTotalPaidInAdvance(fromLoanAccountId).getPaidInAdvance();

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
        final AssembledSavingsAccount toAssembled = assembleSavingsForPosting(toSavingsAccountId, transactionDate);
        final SavingsAccount toSavingsAccount = toAssembled.getAccount();

        final SavingsAccountTransaction deposit = postOptimizedDeposit(toAssembled, transactionDate, transactionAmount, paymentDetail);

        final AccountTransferDetails accountTransferDetails = this.accountTransferAssembler.assembleLoanToSavingsTransfer(command,
                fromLoanAccount, toSavingsAccount, deposit, loanRefundTransaction);
        this.accountTransferDetailRepository.saveAndFlush(accountTransferDetails);

        return new CommandProcessingResultBuilder().withEntityId(accountTransferDetails.getId()).withSavingsId(toSavingsAccountId).build();
    }

    @Transactional
    @Override
    public void reverseTransfersWithFromAccountTransactions(final Collection<Long> fromTransactionIds,
            final PortfolioAccountType accountTypeId) {
        List<AccountTransferTransaction> accountTransfers = new ArrayList<>();
        if (accountTypeId.isLoanAccount()) {
            final List<Long> transactionIds = fromTransactionIds.stream().toList();
            final int partitionSize = fineractProperties.getQuery().getInClauseParameterSizeLimit();
            for (int fromIndex = 0; fromIndex < transactionIds.size(); fromIndex += partitionSize) {
                final int toIndex = Math.min(fromIndex + partitionSize, transactionIds.size());
                accountTransfers
                        .addAll(this.accountTransferRepository.findByFromLoanTransactions(transactionIds.subList(fromIndex, toIndex)));
            }
        }
        if (!accountTransfers.isEmpty()) {
            undoTransactions(accountTransfers);
        }
    }

    @Transactional
    @Override
    public AccountTransferDetails repayLoanWithTopup(final AccountTransferDTO accountTransferDTO) {
        final boolean isAccountTransfer = true;
        Loan fromLoanAccount;
        if (accountTransferDTO.getFromLoan() == null) {
            fromLoanAccount = this.loanAccountAssembler.assembleFrom(accountTransferDTO.getFromAccountId());
        } else {
            fromLoanAccount = accountTransferDTO.getFromLoan();
            this.loanAccountAssembler.setHelpers(fromLoanAccount);
        }
        Loan toLoanAccount;
        if (accountTransferDTO.getToLoan() == null) {
            toLoanAccount = this.loanAccountAssembler.assembleFrom(accountTransferDTO.getToAccountId());
        } else {
            toLoanAccount = accountTransferDTO.getToLoan();
            this.loanAccountAssembler.setHelpers(toLoanAccount);
        }

        ExternalId externalIdForDisbursement = accountTransferDTO.getTxnExternalId();

        LoanTransaction disburseTransaction = this.loanAccountDomainService.makeDisburseTransaction(accountTransferDTO.getFromAccountId(),
                accountTransferDTO.getTransactionDate(), accountTransferDTO.getTransactionAmount(), accountTransferDTO.getPaymentDetail(),
                accountTransferDTO.getNoteText(), externalIdForDisbursement, true);
        final String chargeRefundChargeType = null;

        ExternalId externalIdForRepayment = externalIdFactory.create();

        LoanTransaction repayTransaction = this.loanAccountDomainService.makeRepayment(LoanTransactionType.REPAYMENT, toLoanAccount,
                accountTransferDTO.getTransactionDate(), accountTransferDTO.getTransactionAmount(), accountTransferDTO.getPaymentDetail(),
                null, externalIdForRepayment, false, chargeRefundChargeType, isAccountTransfer, null, false, true);

        AccountTransferDetails accountTransferDetails = this.accountTransferAssembler.assembleLoanToLoanTransfer(accountTransferDTO,
                fromLoanAccount, toLoanAccount, disburseTransaction, repayTransaction);
        this.accountTransferDetailRepository.saveAndFlush(accountTransferDetails);

        return accountTransferDetails;
    }

    @Override
    public void setIsFromJob(final boolean isFromJob) {
        this.isFromJob = isFromJob;
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
            final Loan toLoanAccount = accountTransferDetails.toLoanAccount();
            this.loanAccountAssembler.setHelpers(toLoanAccount);
            return toLoanAccount;
        }
        if (accountTransferDTO.getLoan() != null) {
            final Loan toLoanAccount = accountTransferDTO.getLoan();
            this.loanAccountAssembler.setHelpers(toLoanAccount);
            return toLoanAccount;
        }
        return this.loanAccountAssembler.assembleFrom(accountTransferDTO.getToAccountId());
    }

    private Loan resolveFromLoanAccount(final AccountTransferDTO accountTransferDTO, final AccountTransferDetails accountTransferDetails) {
        if (accountTransferDetails != null) {
            final Loan fromLoanAccount = accountTransferDetails.fromLoanAccount();
            this.loanAccountAssembler.setHelpers(fromLoanAccount);
            return fromLoanAccount;
        }
        if (accountTransferDTO.getLoan() != null) {
            final Loan fromLoanAccount = accountTransferDTO.getLoan();
            this.loanAccountAssembler.setHelpers(fromLoanAccount);
            return fromLoanAccount;
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

    private void undoTransactions(final List<AccountTransferTransaction> accountTransfers) {
        for (final AccountTransferTransaction accountTransfer : accountTransfers) {
            if (accountTransfer.getFromLoanTransaction() != null) {
                this.loanAccountDomainService.reverseTransfer(accountTransfer.getFromLoanTransaction());
            }
            if (accountTransfer.getToLoanTransaction() != null) {
                this.loanAccountDomainService.reverseTransfer(accountTransfer.getToLoanTransaction());
            }
            if (accountTransfer.getFromTransaction() != null) {
                this.savingsAccountWritePlatformService.undoTransaction(
                        accountTransfer.accountTransferDetails().fromSavingsAccount().getId(), accountTransfer.getFromTransaction().getId(),
                        true);
            }
            if (accountTransfer.getToSavingsTransaction() != null) {
                this.savingsAccountWritePlatformService.undoTransaction(accountTransfer.accountTransferDetails().toSavingsAccount().getId(),
                        accountTransfer.getToSavingsTransaction().getId(), true);
            }
            accountTransfer.reverse();
            this.accountTransferRepository.save(accountTransfer);
        }
    }
}
