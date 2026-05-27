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
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.advancly.fineract.portfolio.savings.domain.AdvanclySavingsAccountAssembler;
import com.advancly.fineract.portfolio.savings.domain.AdvanclySavingsAccountTransactionRepository;
import com.advancly.fineract.portfolio.savings.domain.AssembledSavingsAccount;
import com.advancly.fineract.portfolio.savings.service.AdvanclySavingsAccountDomainService;
import com.advancly.fineract.portfolio.savings.testutil.MoneyHelperInitializer;
import com.advancly.fineract.portfolio.savings.testutil.SavingsAccountSummaryTestBuilder;
import com.advancly.fineract.portfolio.savings.testutil.SavingsAccountTestBuilder;
import com.advancly.fineract.portfolio.savings.testutil.SavingsAccountTransactionTestBuilder;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collections;
import java.util.Optional;
import org.apache.fineract.infrastructure.configuration.domain.ConfigurationDomainService;
import org.apache.fineract.infrastructure.core.api.JsonCommand;
import org.apache.fineract.infrastructure.core.config.FineractProperties;
import org.apache.fineract.infrastructure.core.data.CommandProcessingResult;
import org.apache.fineract.infrastructure.core.domain.ExternalId;
import org.apache.fineract.infrastructure.core.service.ExternalIdFactory;
import org.apache.fineract.organisation.monetary.domain.MonetaryCurrency;
import org.apache.fineract.organisation.monetary.domain.Money;
import org.apache.fineract.portfolio.account.PortfolioAccountType;
import org.apache.fineract.portfolio.account.data.AccountTransferDTO;
import org.apache.fineract.portfolio.account.data.AccountTransfersDataValidator;
import org.apache.fineract.portfolio.account.domain.AccountTransferAssembler;
import org.apache.fineract.portfolio.account.domain.AccountTransferDetailRepository;
import org.apache.fineract.portfolio.account.domain.AccountTransferDetails;
import org.apache.fineract.portfolio.account.domain.AccountTransferRepository;
import org.apache.fineract.portfolio.account.domain.AccountTransferType;
import org.apache.fineract.portfolio.account.exception.DifferentCurrenciesException;
import org.apache.fineract.portfolio.loanaccount.domain.Loan;
import org.apache.fineract.portfolio.loanaccount.domain.LoanAccountDomainService;
import org.apache.fineract.portfolio.loanaccount.domain.LoanTransaction;
import org.apache.fineract.portfolio.loanaccount.domain.LoanTransactionType;
import org.apache.fineract.portfolio.loanaccount.service.LoanAssembler;
import org.apache.fineract.portfolio.loanaccount.service.LoanReadPlatformService;
import org.apache.fineract.portfolio.savings.domain.GSIMRepositoy;
import org.apache.fineract.portfolio.savings.domain.SavingsAccount;
import org.apache.fineract.portfolio.savings.domain.SavingsAccountTransaction;
import org.apache.fineract.portfolio.savings.service.SavingsAccountWritePlatformService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class AdvanclyAccountTransfersWritePlatformServiceTest {

    @Mock
    private AccountTransfersDataValidator accountTransfersDataValidator;
    @Mock
    private AccountTransferAssembler accountTransferAssembler;
    @Mock
    private AccountTransferRepository accountTransferRepository;
    @Mock
    private AdvanclySavingsAccountAssembler savingsAccountAssembler;
    @Mock
    private AdvanclySavingsAccountDomainService savingsAccountDomainService;
    @Mock
    private LoanAssembler loanAccountAssembler;
    @Mock
    private LoanAccountDomainService loanAccountDomainService;
    @Mock
    private SavingsAccountWritePlatformService savingsAccountWritePlatformService;
    @Mock
    private AccountTransferDetailRepository accountTransferDetailRepository;
    @Mock
    private LoanReadPlatformService loanReadPlatformService;
    @Mock
    private GSIMRepositoy gsimRepository;
    @Mock
    private ConfigurationDomainService configurationDomainService;
    @Mock
    private ExternalIdFactory externalIdFactory;
    @Mock
    private FineractProperties fineractProperties;
    @Mock
    private AdvanclySavingsAccountTransactionRepository transactionRepository;

    private AdvanclyAccountTransfersWritePlatformService service;

    @BeforeEach
    void setUp() {
        MoneyHelperInitializer.initialize();
        service = new AdvanclyAccountTransfersWritePlatformService(accountTransfersDataValidator, accountTransferAssembler,
                accountTransferRepository, savingsAccountAssembler, savingsAccountDomainService, loanAccountAssembler,
                loanAccountDomainService, savingsAccountWritePlatformService, accountTransferDetailRepository, loanReadPlatformService,
                gsimRepository, configurationDomainService, externalIdFactory, fineractProperties, transactionRepository);
    }

    @Test
    void createSavingsToSavingsUsesOptimizedWithdrawalAndDepositForSameDayAppendPath() {
        LocalDate transferDate = LocalDate.of(2026, 5, 27);
        BigDecimal amount = BigDecimal.valueOf(1000);
        Long fromSavingsId = 10L;
        Long toSavingsId = 20L;
        JsonCommand command = savingsToSavingsCommand(transferDate, amount, fromSavingsId, toSavingsId);

        SavingsAccount fromAccount = savingsAccount(fromSavingsId, BigDecimal.valueOf(5000));
        SavingsAccount toAccount = savingsAccount(toSavingsId, BigDecimal.valueOf(2000));
        SavingsAccountTransaction fromLastTxn = transaction(101L, fromAccount, BigDecimal.valueOf(5000));
        SavingsAccountTransaction toLastTxn = transaction(201L, toAccount, BigDecimal.valueOf(2000));
        AssembledSavingsAccount fromAssembly = AssembledSavingsAccount.of(fromAccount, Collections.emptyList(), fromLastTxn);
        AssembledSavingsAccount toAssembly = AssembledSavingsAccount.of(toAccount, Collections.emptyList(), toLastTxn);

        when(transactionRepository.findLastTransactionDate(fromSavingsId)).thenReturn(Optional.of(transferDate));
        when(transactionRepository.findLastTransactionDate(toSavingsId)).thenReturn(Optional.of(transferDate));
        when(savingsAccountAssembler.assembleForAppendPath(fromSavingsId)).thenReturn(fromAssembly);
        when(savingsAccountAssembler.assembleForAppendPath(toSavingsId)).thenReturn(toAssembly);

        SavingsAccountTransaction withdrawal = transaction(301L, fromAccount, BigDecimal.valueOf(4000));
        SavingsAccountTransaction deposit = transaction(302L, toAccount, BigDecimal.valueOf(3000));
        when(savingsAccountDomainService.handleWithdrawalOptimized(eq(fromAccount), eq(transferDate), eq(amount), isNull(), eq(false),
                anyList(), any(Money.class), eq(fromAccount.getCurrency()), eq(fromLastTxn))).thenReturn(withdrawal);
        when(savingsAccountDomainService.handleDepositOptimized(eq(toAccount), eq(transferDate), eq(amount), isNull(), anyList(),
                any(Money.class), eq(toAccount.getCurrency()), eq(toLastTxn))).thenReturn(deposit);

        AccountTransferDetails transferDetails = org.mockito.Mockito.mock(AccountTransferDetails.class);
        when(transferDetails.getId()).thenReturn(99L);
        when(accountTransferAssembler.assembleSavingsToSavingsTransfer(command, fromAccount, toAccount, withdrawal, deposit))
                .thenReturn(transferDetails);

        CommandProcessingResult result = service.create(command);

        assertThat(result.getResourceId()).isEqualTo(99L);
        verify(savingsAccountAssembler).assembleForAppendPath(fromSavingsId);
        verify(savingsAccountAssembler).assembleForAppendPath(toSavingsId);
        verify(savingsAccountAssembler, never()).assembleForInsertPath(eq(fromSavingsId), any(LocalDate.class), anyBoolean());
        verify(savingsAccountAssembler, never()).assembleForInsertPath(eq(toSavingsId), any(LocalDate.class), anyBoolean());
        verify(accountTransferDetailRepository).saveAndFlush(transferDetails);
    }

    @Test
    void createSavingsToSavingsChecksCurrencyBeforeOptimizedPostings() {
        LocalDate transferDate = LocalDate.of(2026, 5, 27);
        JsonCommand command = savingsToSavingsCommand(transferDate, BigDecimal.valueOf(1000), 10L, 20L);

        SavingsAccount fromAccount = savingsAccount(10L, BigDecimal.valueOf(5000));
        SavingsAccount toAccount = savingsAccount(20L, BigDecimal.valueOf(2000));
        ReflectionTestUtils.setField(toAccount, "currency", new MonetaryCurrency("NGN", 2, null));

        when(transactionRepository.findLastTransactionDate(10L)).thenReturn(Optional.empty());
        when(transactionRepository.findLastTransactionDate(20L)).thenReturn(Optional.empty());
        when(savingsAccountAssembler.assembleForAppendPath(10L))
                .thenReturn(AssembledSavingsAccount.of(fromAccount, Collections.emptyList(), null));
        when(savingsAccountAssembler.assembleForAppendPath(20L))
                .thenReturn(AssembledSavingsAccount.of(toAccount, Collections.emptyList(), null));

        assertThatThrownBy(() -> service.create(command)).isInstanceOf(DifferentCurrenciesException.class);

        verify(savingsAccountDomainService, never()).handleWithdrawalOptimized(any(), any(), any(), any(), anyBoolean(), anyList(), any(),
                any(), any());
        verify(savingsAccountDomainService, never()).handleDepositOptimized(any(), any(), any(), any(), anyList(), any(), any(), any());
    }

    @Test
    void transferFundsSavingsToSavingsDerivesPathIdsFromSavingsEntitiesWhenDtoIdsAreAbsent() {
        LocalDate transferDate = LocalDate.of(2026, 5, 27);
        BigDecimal amount = BigDecimal.valueOf(1000);
        Long fromSavingsId = 10L;
        Long toSavingsId = 20L;

        SavingsAccount fromAccount = savingsAccount(fromSavingsId, BigDecimal.valueOf(5000));
        SavingsAccount toAccount = savingsAccount(toSavingsId, BigDecimal.valueOf(2000));
        AccountTransferDTO dto = new AccountTransferDTO(transferDate, amount, PortfolioAccountType.SAVINGS, PortfolioAccountType.SAVINGS,
                null, null, "Transfer to savings", null, null, null, null, null, null, null,
                AccountTransferType.ACCOUNT_TRANSFER.getValue(), null, null, ExternalId.empty(), null, toAccount, fromAccount, true, false);

        SavingsAccountTransaction fromLastTxn = transaction(101L, fromAccount, BigDecimal.valueOf(5000));
        SavingsAccountTransaction toLastTxn = transaction(201L, toAccount, BigDecimal.valueOf(2000));
        AssembledSavingsAccount fromAssembly = AssembledSavingsAccount.of(fromAccount, Collections.emptyList(), fromLastTxn);
        AssembledSavingsAccount toAssembly = AssembledSavingsAccount.of(toAccount, Collections.emptyList(), toLastTxn);

        when(configurationDomainService.isSavingsInterestPostingAtCurrentPeriodEnd()).thenReturn(false);
        when(transactionRepository.findLastTransactionDate(fromSavingsId)).thenReturn(Optional.of(transferDate));
        when(transactionRepository.findLastTransactionDate(toSavingsId)).thenReturn(Optional.of(transferDate));
        when(savingsAccountAssembler.assembleForAppendPath(fromSavingsId)).thenReturn(fromAssembly);
        when(savingsAccountAssembler.assembleForAppendPath(toSavingsId)).thenReturn(toAssembly);

        SavingsAccountTransaction withdrawal = transaction(301L, fromAccount, BigDecimal.valueOf(4000));
        SavingsAccountTransaction deposit = transaction(302L, toAccount, BigDecimal.valueOf(3000));
        when(savingsAccountDomainService.handleWithdrawalOptimized(eq(fromAccount), eq(transferDate), eq(amount), isNull(), eq(false),
                anyList(), any(Money.class), eq(fromAccount.getCurrency()), eq(fromLastTxn))).thenReturn(withdrawal);
        when(savingsAccountDomainService.handleDepositOptimized(eq(toAccount), eq(transferDate), eq(amount), isNull(), anyList(),
                any(Money.class), eq(toAccount.getCurrency()), eq(toLastTxn))).thenReturn(deposit);

        AccountTransferDetails transferDetails = org.mockito.Mockito.mock(AccountTransferDetails.class);
        when(transferDetails.getId()).thenReturn(100L);
        when(accountTransferAssembler.assembleSavingsToSavingsTransfer(dto, fromAccount, toAccount, withdrawal, deposit))
                .thenReturn(transferDetails);

        Long transferId = service.transferFunds(dto);

        assertThat(transferId).isEqualTo(100L);
        verify(transactionRepository).findLastTransactionDate(fromSavingsId);
        verify(transactionRepository).findLastTransactionDate(toSavingsId);
        verify(accountTransferDetailRepository).saveAndFlush(transferDetails);
    }

    @Test
    void createSavingsToLoanUsesOptimizedWithdrawalForSourceSavings() {
        LocalDate transferDate = LocalDate.of(2026, 5, 27);
        BigDecimal amount = BigDecimal.valueOf(1000);
        Long fromSavingsId = 10L;
        Long toLoanId = 30L;
        JsonCommand command = transferCommand(transferDate, amount, PortfolioAccountType.SAVINGS, PortfolioAccountType.LOAN, fromSavingsId,
                toLoanId);

        SavingsAccount fromAccount = savingsAccount(fromSavingsId, BigDecimal.valueOf(5000));
        SavingsAccountTransaction fromLastTxn = transaction(101L, fromAccount, BigDecimal.valueOf(5000));
        AssembledSavingsAccount fromAssembly = AssembledSavingsAccount.of(fromAccount, Collections.emptyList(), fromLastTxn);

        when(transactionRepository.findLastTransactionDate(fromSavingsId)).thenReturn(Optional.of(transferDate));
        when(savingsAccountAssembler.assembleForAppendPath(fromSavingsId)).thenReturn(fromAssembly);

        SavingsAccountTransaction withdrawal = transaction(301L, fromAccount, BigDecimal.valueOf(4000));
        when(savingsAccountDomainService.handleWithdrawalOptimized(eq(fromAccount), eq(transferDate), eq(amount), isNull(), eq(false),
                anyList(), any(Money.class), eq(fromAccount.getCurrency()), eq(fromLastTxn))).thenReturn(withdrawal);

        Loan toLoan = org.mockito.Mockito.mock(Loan.class);
        LoanTransaction repayment = org.mockito.Mockito.mock(LoanTransaction.class);
        when(repayment.getLoan()).thenReturn(toLoan);
        when(loanAccountAssembler.assembleFrom(toLoanId)).thenReturn(toLoan);
        when(externalIdFactory.create()).thenReturn(org.apache.fineract.infrastructure.core.domain.ExternalId.generate());
        when(loanAccountDomainService.makeRepayment(eq(LoanTransactionType.REPAYMENT), eq(toLoan), eq(transferDate), eq(amount), isNull(),
                isNull(), any(), eq(false), isNull(), eq(true), isNull(), eq(false))).thenReturn(repayment);

        AccountTransferDetails transferDetails = org.mockito.Mockito.mock(AccountTransferDetails.class);
        when(transferDetails.getId()).thenReturn(88L);
        when(accountTransferAssembler.assembleSavingsToLoanTransfer(command, fromAccount, toLoan, withdrawal, repayment))
                .thenReturn(transferDetails);

        CommandProcessingResult result = service.create(command);

        assertThat(result.getResourceId()).isEqualTo(88L);
        verify(savingsAccountDomainService).handleWithdrawalOptimized(eq(fromAccount), eq(transferDate), eq(amount), isNull(), eq(false),
                anyList(), any(Money.class), eq(fromAccount.getCurrency()), eq(fromLastTxn));
        verify(accountTransferDetailRepository).saveAndFlush(transferDetails);
    }

    @Test
    void createLoanToSavingsUsesOptimizedDepositForDestinationSavings() {
        LocalDate transferDate = LocalDate.of(2026, 5, 27);
        BigDecimal amount = BigDecimal.valueOf(1000);
        Long fromLoanId = 30L;
        Long toSavingsId = 20L;
        JsonCommand command = transferCommand(transferDate, amount, PortfolioAccountType.LOAN, PortfolioAccountType.SAVINGS, fromLoanId,
                toSavingsId);

        SavingsAccount toAccount = savingsAccount(toSavingsId, BigDecimal.valueOf(2000));
        SavingsAccountTransaction toLastTxn = transaction(201L, toAccount, BigDecimal.valueOf(2000));
        AssembledSavingsAccount toAssembly = AssembledSavingsAccount.of(toAccount, Collections.emptyList(), toLastTxn);

        when(transactionRepository.findLastTransactionDate(toSavingsId)).thenReturn(Optional.of(transferDate));
        when(savingsAccountAssembler.assembleForAppendPath(toSavingsId)).thenReturn(toAssembly);

        SavingsAccountTransaction deposit = transaction(302L, toAccount, BigDecimal.valueOf(3000));
        when(savingsAccountDomainService.handleDepositOptimized(eq(toAccount), eq(transferDate), eq(amount), isNull(), anyList(),
                any(Money.class), eq(toAccount.getCurrency()), eq(toLastTxn))).thenReturn(deposit);

        Loan fromLoan = org.mockito.Mockito.mock(Loan.class);
        LoanTransaction refund = org.mockito.Mockito.mock(LoanTransaction.class);
        when(loanAccountAssembler.assembleFrom(fromLoanId)).thenReturn(fromLoan);
        when(externalIdFactory.create()).thenReturn(org.apache.fineract.infrastructure.core.domain.ExternalId.generate());
        when(loanAccountDomainService.makeRefund(eq(fromLoanId), any(), eq(transferDate), eq(amount), isNull(), isNull(), any()))
                .thenReturn(refund);

        AccountTransferDetails transferDetails = org.mockito.Mockito.mock(AccountTransferDetails.class);
        when(transferDetails.getId()).thenReturn(77L);
        when(accountTransferAssembler.assembleLoanToSavingsTransfer(command, fromLoan, toAccount, deposit, refund))
                .thenReturn(transferDetails);

        CommandProcessingResult result = service.create(command);

        assertThat(result.getResourceId()).isEqualTo(77L);
        verify(savingsAccountDomainService).handleDepositOptimized(eq(toAccount), eq(transferDate), eq(amount), isNull(), anyList(),
                any(Money.class), eq(toAccount.getCurrency()), eq(toLastTxn));
        verify(accountTransferDetailRepository).saveAndFlush(transferDetails);
    }

    private JsonCommand savingsToSavingsCommand(LocalDate transferDate, BigDecimal amount, Long fromSavingsId, Long toSavingsId) {
        return transferCommand(transferDate, amount, PortfolioAccountType.SAVINGS, PortfolioAccountType.SAVINGS, fromSavingsId,
                toSavingsId);
    }

    private JsonCommand transferCommand(LocalDate transferDate, BigDecimal amount, PortfolioAccountType fromAccountType,
            PortfolioAccountType toAccountType, Long fromAccountId, Long toAccountId) {
        JsonCommand command = org.mockito.Mockito.mock(JsonCommand.class);
        when(command.localDateValueOfParameterNamed(transferDateParamName)).thenReturn(transferDate);
        when(command.bigDecimalValueOfParameterNamed(transferAmountParamName)).thenReturn(amount);
        when(command.integerValueSansLocaleOfParameterNamed(fromAccountTypeParamName)).thenReturn(fromAccountType.getValue());
        when(command.integerValueSansLocaleOfParameterNamed(toAccountTypeParamName)).thenReturn(toAccountType.getValue());
        when(command.longValueOfParameterNamed(fromAccountIdParamName)).thenReturn(fromAccountId);
        when(command.longValueOfParameterNamed(toAccountIdParamName)).thenReturn(toAccountId);
        return command;
    }

    private SavingsAccount savingsAccount(Long savingsId, BigDecimal runningBalance) {
        return new SavingsAccountTestBuilder().withId(savingsId).withSummary(new SavingsAccountSummaryTestBuilder()
                .withAccountBalance(runningBalance).withRunningBalanceOnPivotDate(runningBalance).build()).build();
    }

    private SavingsAccountTransaction transaction(Long id, SavingsAccount account, BigDecimal runningBalance) {
        return new SavingsAccountTransactionTestBuilder().withId(id).withSavingsAccount(account).withRunningBalance(runningBalance).build();
    }
}
