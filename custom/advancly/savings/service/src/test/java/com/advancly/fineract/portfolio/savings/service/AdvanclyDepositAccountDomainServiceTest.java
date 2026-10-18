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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.advancly.fineract.portfolio.savings.domain.DynamicDepositAccount;
import com.advancly.fineract.portfolio.savings.testutil.MoneyHelperInitializer;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.fineract.accounting.journalentry.service.JournalEntryWritePlatformService;
import org.apache.fineract.infrastructure.configuration.domain.ConfigurationDomainService;
import org.apache.fineract.infrastructure.core.api.JsonCommand;
import org.apache.fineract.infrastructure.core.exception.GeneralPlatformDomainRuleException;
import org.apache.fineract.infrastructure.core.exception.PlatformApiDataValidationException;
import org.apache.fineract.infrastructure.core.serialization.FromJsonHelper;
import org.apache.fineract.organisation.monetary.domain.MonetaryCurrency;
import org.apache.fineract.portfolio.account.data.AccountTransferDTO;
import org.apache.fineract.portfolio.account.service.AccountTransfersWritePlatformService;
import org.apache.fineract.portfolio.paymentdetail.domain.PaymentDetail;
import org.apache.fineract.portfolio.savings.DepositAccountOnClosureType;
import org.apache.fineract.portfolio.savings.DepositAccountType;
import org.apache.fineract.portfolio.savings.SavingsApiConstants;
import org.apache.fineract.portfolio.savings.data.SavingsAccountStatusEnumData;
import org.apache.fineract.portfolio.savings.domain.DepositAccountAssembler;
import org.apache.fineract.portfolio.savings.domain.DepositAccountDomainServiceJpa;
import org.apache.fineract.portfolio.savings.domain.DepositAccountTermAndPreClosure;
import org.apache.fineract.portfolio.savings.domain.FixedDepositAccount;
import org.apache.fineract.portfolio.savings.domain.SavingsAccount;
import org.apache.fineract.portfolio.savings.domain.SavingsAccountRepositoryWrapper;
import org.apache.fineract.portfolio.savings.domain.SavingsAccountStatusType;
import org.apache.fineract.portfolio.savings.domain.SavingsAccountSummary;
import org.apache.fineract.portfolio.savings.domain.SavingsAccountTransaction;
import org.apache.fineract.portfolio.savings.domain.SavingsAccountTransactionRepository;
import org.apache.fineract.portfolio.savings.service.SavingsEnumerations;
import org.apache.fineract.useradministration.domain.AppUser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

/**
 * Orchestration of a Dynamic Deposit closure in {@link AdvanclyDepositAccountDomainService}: what is settled first,
 * what the payout is, which closure type moves the money where, and the rules that reject a closure before anything is
 * written. The money arithmetic of settlement (forfeiture, interest, tax) lives in the account and its services and is
 * covered by {@code SavingsAccountWritePlatformServiceCloseDynamicDepositTest}.
 */
class AdvanclyDepositAccountDomainServiceTest {

    private static final Long ACCOUNT_ID = 7L;
    private static final Long DESTINATION_ID = 99L;
    private static final Long CLIENT_ID = 11L;
    private static final Long GROUP_ID = 21L;
    private static final LocalDate CLOSED_DATE = LocalDate.of(2026, 1, 20);
    private static final int WITHDRAW = 100;
    private static final int TRANSFER_TO_SAVINGS = 200;
    private static final int REINVEST_PRINCIPAL_AND_INTEREST = 300;
    private static final int REINVEST_PRINCIPAL_ONLY = 400;

    private DepositAccountDomainServiceJpa coreDomainService;
    private SavingsAccountRepositoryWrapper savingsAccountRepository;
    private SavingsAccountTransactionRepository savingsAccountTransactionRepository;
    private JournalEntryWritePlatformService journalEntryWritePlatformService;
    private DepositAccountAssembler depositAccountAssembler;
    private AccountTransfersWritePlatformService accountTransfersWritePlatformService;
    private DynamicDepositReinvestmentService reinvestmentService;
    private AdvanclyDepositAccountDomainService service;

    private DynamicDepositAccount account;
    private DepositAccountTermAndPreClosure term;
    private SavingsAccount destination;
    private final AtomicReference<BigDecimal> balance = new AtomicReference<>(BigDecimal.valueOf(1000));
    private final Map<String, Object> changes = new LinkedHashMap<>();

    @BeforeEach
    void setUp() {
        MoneyHelperInitializer.initialize();
        this.coreDomainService = mock(DepositAccountDomainServiceJpa.class);
        this.savingsAccountRepository = mock(SavingsAccountRepositoryWrapper.class);
        this.savingsAccountTransactionRepository = mock(SavingsAccountTransactionRepository.class);
        this.journalEntryWritePlatformService = mock(JournalEntryWritePlatformService.class);
        final ConfigurationDomainService configurationDomainService = mock(ConfigurationDomainService.class);
        this.depositAccountAssembler = mock(DepositAccountAssembler.class);
        this.accountTransfersWritePlatformService = mock(AccountTransfersWritePlatformService.class);
        this.reinvestmentService = mock(DynamicDepositReinvestmentService.class);
        this.service = new AdvanclyDepositAccountDomainService(this.coreDomainService, this.savingsAccountRepository,
                this.savingsAccountTransactionRepository, this.journalEntryWritePlatformService, configurationDomainService,
                this.depositAccountAssembler, this.accountTransfersWritePlatformService, this.reinvestmentService,
                mock(org.apache.fineract.portfolio.account.service.AccountAssociationsReadPlatformService.class));

        this.account = mock(DynamicDepositAccount.class);
        this.term = mock(DepositAccountTermAndPreClosure.class);
        final SavingsAccountSummary summary = mock(SavingsAccountSummary.class);
        when(summary.getAccountBalance()).thenAnswer(invocation -> this.balance.get());
        when(this.account.getSummary()).thenReturn(summary);
        when(this.account.getId()).thenReturn(ACCOUNT_ID);
        when(this.account.getStatus()).thenReturn(SavingsAccountStatusType.ACTIVE);
        when(this.account.clientId()).thenReturn(CLIENT_ID);
        // A client's account has no group; Mockito would otherwise answer 0L for a Long.
        when(this.account.groupId()).thenReturn(null);
        when(this.account.accountTermAndPreClosure()).thenReturn(this.term);
        when(this.account.getNominalAnnualInterestRate()).thenReturn(BigDecimal.valueOf(2));
        when(this.account.getCurrency()).thenReturn(new MonetaryCurrency("USD", 2, null));
        when(this.account.getTransactions()).thenReturn(List.of());
        when(this.account.interestPostingTransactionDateForClosure(CLOSED_DATE)).thenReturn(CLOSED_DATE);
        when(this.account.isEarlyWithdrawal(CLOSED_DATE)).thenReturn(true);
        // Matured before CLOSED_DATE, as every maturity-closure scenario below needs; a premature closure ignores it.
        when(this.account.maturityDate()).thenReturn(LocalDate.of(2026, 1, 10));
        when(this.account.close(any(AppUser.class), any(JsonCommand.class))).thenAnswer(invocation -> {
            final Map<String, Object> closeChanges = new LinkedHashMap<>();
            closeChanges.put(SavingsApiConstants.statusParamName, SavingsEnumerations.status(SavingsAccountStatusType.CLOSED));
            return closeChanges;
        });

        this.destination = mock(SavingsAccount.class);
        when(this.destination.isActive()).thenReturn(true);
        when(this.destination.clientId()).thenReturn(CLIENT_ID);
        when(this.destination.groupId()).thenReturn(null);
        when(this.depositAccountAssembler.assembleFrom(DESTINATION_ID, DepositAccountType.SAVINGS_DEPOSIT)).thenReturn(this.destination);

        final SavingsAccountTransaction withdrawal = mock(SavingsAccountTransaction.class);
        when(withdrawal.getId()).thenReturn(555L);
        when(this.coreDomainService.handleWithdrawal(any(), any(), any(), any(), any(), anyBoolean(), anyBoolean())).thenReturn(withdrawal);
    }

    @Test
    void aWithdrawalClosureSettlesFirstThenPaysOutWhatSettlementLeftInOneWithdrawal() {
        when(this.account.beginClosureSettlement(CLOSED_DATE)).thenAnswer(invocation -> {
            this.balance.set(BigDecimal.valueOf(700)); // the forfeiture came out of the balance
            return false; // ... and already force-posted the closing interest
        });
        final PaymentDetail paymentDetail = mock(PaymentDetail.class);

        final Long transactionId = this.service.handleDynamicDepositPreMatureClosure(this.account, paymentDetail, mock(AppUser.class),
                command(WITHDRAW, null), this.changes);

        assertThat(transactionId).isEqualTo(555L);
        final InOrder order = inOrder(this.account, this.coreDomainService);
        order.verify(this.account).beginClosureSettlement(CLOSED_DATE);
        order.verify(this.coreDomainService).handleWithdrawal(eq(this.account), any(DateTimeFormatter.class), eq(CLOSED_DATE),
                eq(BigDecimal.valueOf(700)), eq(paymentDetail), eq(false), eq(false));
        order.verify(this.account).close(any(AppUser.class), any(JsonCommand.class));
        verify(this.accountTransfersWritePlatformService, never()).transferFunds(any());
        // The settlement already posted the closing interest, so the service must not post it a second time.
        verify(this.account, never()).postInterest(any(), any(), anyBoolean(), anyBoolean(), any(), any(), anyBoolean(), anyBoolean());
    }

    @Test
    void whenSettlementLeavesInterestToPostItIsPostedBoundedByTheClosureDateAndJournalled() {
        when(this.account.beginClosureSettlement(CLOSED_DATE)).thenReturn(true);
        when(this.account.deriveAccountingBridgeData(any(), any(), any(), anyBoolean(), anyBoolean())).thenReturn(Map.of());

        this.service.handleDynamicDepositPreMatureClosure(this.account, null, mock(AppUser.class), command(WITHDRAW, null), this.changes);

        final InOrder order = inOrder(this.account, this.savingsAccountRepository, this.journalEntryWritePlatformService,
                this.coreDomainService);
        order.verify(this.account).postInterest(any(), eq(CLOSED_DATE), eq(false), anyBoolean(), any(), eq(CLOSED_DATE), eq(false),
                eq(false));
        order.verify(this.savingsAccountRepository).saveAndFlush(this.account);
        order.verify(this.journalEntryWritePlatformService).createJournalEntriesForSavings(any());
        order.verify(this.coreDomainService).handleWithdrawal(any(), any(), any(), any(), any(), anyBoolean(), anyBoolean());
    }

    @Test
    void aTransferClosureMovesThePayoutToTheDestinationInsteadOfWithdrawingIt() {
        when(this.account.beginClosureSettlement(CLOSED_DATE)).thenAnswer(invocation -> {
            this.balance.set(BigDecimal.valueOf(700));
            return false;
        });

        final Long transactionId = this.service.handleDynamicDepositPreMatureClosure(this.account, null, mock(AppUser.class),
                command(TRANSFER_TO_SAVINGS, DESTINATION_ID), this.changes);

        assertThat(transactionId).isNull();
        final ArgumentCaptor<AccountTransferDTO> transfer = ArgumentCaptor.forClass(AccountTransferDTO.class);
        verify(this.accountTransfersWritePlatformService).transferFunds(transfer.capture());
        assertThat(transfer.getValue().getTransactionAmount()).isEqualByComparingTo(BigDecimal.valueOf(700));
        assertThat(transfer.getValue().getTransactionDate()).isEqualTo(CLOSED_DATE);
        assertThat(transfer.getValue().getToSavingsAccount()).isSameAs(this.destination);
        assertThat(transfer.getValue().getFromSavingsAccount()).isSameAs(this.account);
        assertThat(transfer.getValue().getDescription()).isEqualTo("closure proceeds");
        verify(this.coreDomainService, never()).handleWithdrawal(any(), any(), any(), any(), any(), anyBoolean(), anyBoolean());
        verify(this.term).updateOnAccountClosureStatus(DepositAccountOnClosureType.TRANSFER_TO_SAVINGS);
    }

    @Test
    void aTransferToAnotherClientsAccountIsRejectedBeforeAnythingIsSettled() {
        when(this.destination.clientId()).thenReturn(CLIENT_ID + 1);

        assertThatThrownBy(() -> this.service.handleDynamicDepositPreMatureClosure(this.account, null, mock(AppUser.class),
                command(TRANSFER_TO_SAVINGS, DESTINATION_ID), this.changes)).isInstanceOf(GeneralPlatformDomainRuleException.class)
                .hasMessageContaining("same client");

        verify(this.account, never()).beginClosureSettlement(any());
        verify(this.accountTransfersWritePlatformService, never()).transferFunds(any());
    }

    @Test
    void aTransferToAnInactiveAccountIsRejectedBeforeAnythingIsSettled() {
        when(this.destination.isActive()).thenReturn(false);

        assertThatThrownBy(() -> this.service.handleDynamicDepositPreMatureClosure(this.account, null, mock(AppUser.class),
                command(TRANSFER_TO_SAVINGS, DESTINATION_ID), this.changes)).isInstanceOf(GeneralPlatformDomainRuleException.class)
                .hasMessageContaining("must be active");

        verify(this.account, never()).beginClosureSettlement(any());
    }

    @Test
    void aGroupAccountMayTransferToAnotherAccountOfTheSameGroup() {
        when(this.account.clientId()).thenReturn(null);
        when(this.account.groupId()).thenReturn(GROUP_ID);
        when(this.destination.clientId()).thenReturn(null);
        when(this.destination.groupId()).thenReturn(GROUP_ID);
        when(this.account.beginClosureSettlement(CLOSED_DATE)).thenReturn(false);

        this.service.handleDynamicDepositPreMatureClosure(this.account, null, mock(AppUser.class),
                command(TRANSFER_TO_SAVINGS, DESTINATION_ID), this.changes);

        verify(this.accountTransfersWritePlatformService).transferFunds(any());
    }

    @Test
    void aPrematureClosureIsRecordedAsPrematureClosureNotClosed() {
        when(this.account.beginClosureSettlement(CLOSED_DATE)).thenReturn(false);

        this.service.handleDynamicDepositPreMatureClosure(this.account, null, mock(AppUser.class), command(WITHDRAW, null), this.changes);

        verify(this.account).setStatus(SavingsAccountStatusType.PRE_MATURE_CLOSURE.getValue());
        verify(this.term).updateOnAccountClosureStatus(DepositAccountOnClosureType.WITHDRAW_DEPOSIT);
        verify(this.savingsAccountRepository).save(this.account);
        final SavingsAccountStatusEnumData reported = (SavingsAccountStatusEnumData) this.changes.get(SavingsApiConstants.statusParamName);
        assertThat(reported.isPrematureClosed()).isTrue();
        assertThat(reported.isClosed()).isFalse();
    }

    @Test
    void aMaturityClosureKeepsTheClosedStatus() {
        when(this.account.isEarlyWithdrawal(CLOSED_DATE)).thenReturn(false);
        when(this.account.beginClosureSettlement(CLOSED_DATE)).thenReturn(true);
        when(this.account.deriveAccountingBridgeData(any(), any(), any(), anyBoolean(), anyBoolean())).thenReturn(Map.of());

        this.service.handleDynamicDepositClosure(this.account, null, mock(AppUser.class), command(WITHDRAW, null), this.changes);

        verify(this.account, never()).setStatus(any());
        final SavingsAccountStatusEnumData reported = (SavingsAccountStatusEnumData) this.changes.get(SavingsApiConstants.statusParamName);
        assertThat(reported.isClosed()).isTrue();
    }

    @Test
    void aPrematureClosureOnOrAfterMaturityIsRejected() {
        when(this.account.isEarlyWithdrawal(CLOSED_DATE)).thenReturn(false);

        assertThatThrownBy(() -> this.service.handleDynamicDepositPreMatureClosure(this.account, null, mock(AppUser.class),
                command(WITHDRAW, null), this.changes)).isInstanceOf(PlatformApiDataValidationException.class);

        verify(this.account, never()).beginClosureSettlement(any());
    }

    @Test
    void aMaturityClosureBeforeMaturityIsRejected() {
        assertThatThrownBy(() -> this.service.handleDynamicDepositClosure(this.account, null, mock(AppUser.class), command(WITHDRAW, null),
                this.changes)).isInstanceOf(PlatformApiDataValidationException.class);

        verify(this.account, never()).beginClosureSettlement(any());
    }

    @Test
    void aClosureOfAnAccountThatIsNotActiveIsRejected() {
        when(this.account.getStatus()).thenReturn(SavingsAccountStatusType.CLOSED);

        assertThatThrownBy(() -> this.service.handleDynamicDepositPreMatureClosure(this.account, null, mock(AppUser.class),
                command(WITHDRAW, null), this.changes)).isInstanceOf(PlatformApiDataValidationException.class);

        verify(this.account, never()).beginClosureSettlement(any());
    }

    @Test
    void reinvestingPrincipalAndInterestWithdrawsTheWholePayoutAndOpensTheNewDepositWithIt() {
        when(this.account.isEarlyWithdrawal(CLOSED_DATE)).thenReturn(false);
        when(this.account.beginClosureSettlement(CLOSED_DATE)).thenAnswer(invocation -> {
            this.balance.set(BigDecimal.valueOf(1050));
            return false;
        });
        final DynamicDepositAccount reinvested = reinvestedAccount(808L);
        final AppUser user = mock(AppUser.class);
        when(this.reinvestmentService.reinvest(this.account, BigDecimal.valueOf(1050), CLOSED_DATE, user)).thenReturn(reinvested);

        final Long transactionId = this.service.handleDynamicDepositClosure(this.account, null, user,
                command(REINVEST_PRINCIPAL_AND_INTEREST, null), this.changes);

        assertThat(transactionId).isEqualTo(555L);
        assertThat(this.changes).containsEntry("reinvestedDepositId", 808L);
        final InOrder order = inOrder(this.account, this.coreDomainService, this.reinvestmentService);
        order.verify(this.account).beginClosureSettlement(CLOSED_DATE);
        order.verify(this.coreDomainService).handleWithdrawal(eq(this.account), any(DateTimeFormatter.class), eq(CLOSED_DATE),
                eq(BigDecimal.valueOf(1050)), any(), eq(false), eq(false));
        order.verify(this.reinvestmentService).reinvest(this.account, BigDecimal.valueOf(1050), CLOSED_DATE, user);
        order.verify(this.account).close(any(AppUser.class), any(JsonCommand.class));
        verify(this.term).updateOnAccountClosureStatus(DepositAccountOnClosureType.REINVEST_PRINCIPAL_AND_INTEREST);
        final SavingsAccountStatusEnumData reported = (SavingsAccountStatusEnumData) this.changes.get(SavingsApiConstants.statusParamName);
        assertThat(reported.isClosed()).isTrue();
    }

    @Test
    void reinvestingPrincipalOnlyPaysTheInterestOutFirstThenMovesJustThePrincipalAndClosesTheAccount() {
        when(this.account.isEarlyWithdrawal(CLOSED_DATE)).thenReturn(false);
        investedAmountOf("1000");
        when(this.account.beginClosureSettlement(CLOSED_DATE)).thenAnswer(invocation -> {
            this.balance.set(BigDecimal.valueOf(1050));
            return false;
        });
        final AppUser user = mock(AppUser.class);
        final DynamicDepositAccount reinvested = reinvestedAccount(808L);
        when(this.reinvestmentService.reinvest(eq(this.account), org.mockito.ArgumentMatchers.argThat(amount -> amount.compareTo(BigDecimal.valueOf(1000)) == 0),
                eq(CLOSED_DATE), eq(user))).thenReturn(reinvested);

        this.service.handleDynamicDepositClosure(this.account, null, user, command(REINVEST_PRINCIPAL_ONLY, null), this.changes);

        // No linked account here, so the 50 of interest (less tax) is withdrawn - and BEFORE the principal moves on.
        final InOrder order = inOrder(this.coreDomainService, this.reinvestmentService, this.account);
        order.verify(this.coreDomainService).handleWithdrawal(eq(this.account), any(DateTimeFormatter.class), eq(CLOSED_DATE),
                org.mockito.ArgumentMatchers.argThat(amount -> amount.compareTo(BigDecimal.valueOf(50)) == 0), any(), eq(false), eq(false));
        order.verify(this.coreDomainService).handleWithdrawal(eq(this.account), any(DateTimeFormatter.class), eq(CLOSED_DATE),
                org.mockito.ArgumentMatchers.argThat(amount -> amount.compareTo(BigDecimal.valueOf(1000)) == 0), any(), eq(false), eq(false));
        order.verify(this.reinvestmentService).reinvest(eq(this.account),
                org.mockito.ArgumentMatchers.argThat(amount -> amount.compareTo(BigDecimal.valueOf(1000)) == 0), eq(CLOSED_DATE), eq(user));
        order.verify(this.account).close(any(AppUser.class), any(JsonCommand.class));
        verify(this.term).updateOnAccountClosureStatus(DepositAccountOnClosureType.REINVEST_PRINCIPAL_ONLY);
        assertThat(this.changes).containsEntry("reinvestedDepositId", 808L);
    }

    @Test
    void aPrincipalOnlyReinvestmentNeverCarriesOverMoreThanThePayout() {
        when(this.account.isEarlyWithdrawal(CLOSED_DATE)).thenReturn(false);
        investedAmountOf("5000");
        when(this.account.beginClosureSettlement(CLOSED_DATE)).thenReturn(false);
        final DynamicDepositAccount reinvested = reinvestedAccount(808L);
        when(this.reinvestmentService.reinvest(any(), any(), any(), any())).thenReturn(reinvested);

        this.service.handleDynamicDepositClosure(this.account, null, mock(AppUser.class), command(REINVEST_PRINCIPAL_ONLY, null),
                this.changes);

        verify(this.reinvestmentService).reinvest(eq(this.account), eq(BigDecimal.valueOf(1000)), eq(CLOSED_DATE), any());
    }

    @Test
    void reinvestIsRejectedOnPrematureClosureAndNothingIsSettled() {
        assertThatThrownBy(() -> this.service.handleDynamicDepositPreMatureClosure(this.account, null, mock(AppUser.class),
                command(REINVEST_PRINCIPAL_AND_INTEREST, null), this.changes)).isInstanceOf(GeneralPlatformDomainRuleException.class)
                .hasMessageContaining("pre mature close");

        verify(this.account, never()).beginClosureSettlement(any());
        verify(this.reinvestmentService, never()).reinvest(any(), any(), any(), any());
    }

    @Test
    void reinvestIsRejectedWhenThereIsNothingToReinvest() {
        when(this.account.isEarlyWithdrawal(CLOSED_DATE)).thenReturn(false);
        this.balance.set(BigDecimal.ZERO);

        assertThatThrownBy(() -> this.service.handleDynamicDepositClosure(this.account, null, mock(AppUser.class),
                command(REINVEST_PRINCIPAL_AND_INTEREST, null), this.changes)).isInstanceOf(GeneralPlatformDomainRuleException.class)
                .hasMessageContaining("no balance to reinvest");

        verify(this.reinvestmentService, never()).reinvest(any(), any(), any(), any());
    }

    /** The account holds this much principal: the invested amount is worked out from its transactions. */
    private void investedAmountOf(final String amount) {
        final org.apache.fineract.portfolio.savings.domain.SavingsAccountTransaction deposit = new com.advancly.fineract.portfolio.savings.testutil.SavingsAccountTransactionTestBuilder()
                .withId(1L).withType(org.apache.fineract.portfolio.savings.SavingsAccountTransactionType.DEPOSIT)
                .withDate(LocalDate.of(2026, 1, 1)).withAmount(new BigDecimal(amount)).build();
        when(this.account.getTransactions()).thenReturn(List.of(deposit));
    }

    private static DynamicDepositAccount reinvestedAccount(final Long id) {
        final DynamicDepositAccount reinvested = mock(DynamicDepositAccount.class);
        when(reinvested.getId()).thenReturn(id);
        return reinvested;
    }

    @Test
    void aPrematureWithdrawalIsRejectedWhenTheAccountRuleDisallowsWithdrawals() {
        when(this.account.isWithdrawalBlockedByAccountRule()).thenReturn(true);

        assertThatThrownBy(() -> this.service.handleDynamicDepositPreMatureClosure(this.account, null, mock(AppUser.class),
                command(WITHDRAW, null), this.changes)).isInstanceOf(GeneralPlatformDomainRuleException.class);

        verify(this.account, never()).beginClosureSettlement(any());
    }

    // The lock stops an early exit; it must never stop the account being closed at maturity.
    @Test
    void aMaturityClosureIsNotBlockedByTheWithdrawalLockForWithdrawTransferOrReinvest() {
        when(this.account.isEarlyWithdrawal(CLOSED_DATE)).thenReturn(false);
        when(this.account.isWithdrawalBlockedByAccountRule()).thenReturn(true);
        when(this.account.beginClosureSettlement(CLOSED_DATE)).thenReturn(false);
        investedAmountOf("1000");
        final DynamicDepositAccount reinvested = reinvestedAccount(808L);
        when(this.reinvestmentService.reinvest(any(), any(), any(), any())).thenReturn(reinvested);

        this.service.handleDynamicDepositClosure(this.account, null, mock(AppUser.class), command(WITHDRAW, null), this.changes);
        this.service.handleDynamicDepositClosure(this.account, null, mock(AppUser.class), command(TRANSFER_TO_SAVINGS, DESTINATION_ID),
                this.changes);
        this.service.handleDynamicDepositClosure(this.account, null, mock(AppUser.class), command(REINVEST_PRINCIPAL_AND_INTEREST, null),
                this.changes);

        verify(this.coreDomainService, org.mockito.Mockito.times(2)).handleWithdrawal(any(), any(), any(), any(), any(), anyBoolean(),
                anyBoolean());
        verify(this.accountTransfersWritePlatformService).transferFunds(any());
        verify(this.reinvestmentService).reinvest(any(), any(), any(), any());
    }

    @Test
    void theLockIsLiftedOnlyForTheDurationOfAMaturityClosureAndLiftedAgainEvenWhenItFails() {
        when(this.account.isEarlyWithdrawal(CLOSED_DATE)).thenReturn(false);
        when(this.account.beginClosureSettlement(CLOSED_DATE)).thenReturn(false);
        final InOrder order = inOrder(this.account, this.coreDomainService);

        this.service.handleDynamicDepositClosure(this.account, null, mock(AppUser.class), command(WITHDRAW, null), this.changes);

        order.verify(this.account).markClosingAtMaturity(true);
        order.verify(this.coreDomainService).handleWithdrawal(any(), any(), any(), any(), any(), anyBoolean(), anyBoolean());
        order.verify(this.account).markClosingAtMaturity(false);

        when(this.coreDomainService.handleWithdrawal(any(), any(), any(), any(), any(), anyBoolean(), anyBoolean()))
                .thenThrow(new IllegalStateException("payout failed"));
        assertThatThrownBy(() -> this.service.handleDynamicDepositClosure(this.account, null, mock(AppUser.class), command(WITHDRAW, null),
                this.changes)).isInstanceOf(IllegalStateException.class);
        verify(this.account, org.mockito.Mockito.times(2)).markClosingAtMaturity(false);
    }

    // isEarlyWithdrawal(...) is false for an account with no maturity date, so "not early" alone would let such an
    // account through a maturity closure - and past the withdrawal lock. Maturity must be positively established.
    @Test
    void aMaturityClosureOfAnAccountWithNoMaturityDateIsRejectedAndNeverLiftsTheLock() {
        when(this.account.isEarlyWithdrawal(CLOSED_DATE)).thenReturn(false);
        when(this.account.maturityDate()).thenReturn(null);
        when(this.account.isWithdrawalBlockedByAccountRule()).thenReturn(true);

        assertThatThrownBy(() -> this.service.handleDynamicDepositClosure(this.account, null, mock(AppUser.class), command(WITHDRAW, null),
                this.changes)).isInstanceOf(PlatformApiDataValidationException.class);

        verify(this.account, never()).markClosingAtMaturity(anyBoolean());
        verify(this.account, never()).beginClosureSettlement(any());
        verify(this.coreDomainService, never()).handleWithdrawal(any(), any(), any(), any(), any(), anyBoolean(), anyBoolean());
    }

    @Test
    void aPercentageOverrideInTheRequestIsSetOnTheAccountBeforeSettlementAndClearedAfterwards() {
        when(this.account.beginClosureSettlement(CLOSED_DATE)).thenReturn(false);
        final InOrder order = inOrder(this.account);

        this.service.handleDynamicDepositPreMatureClosure(this.account, null, mock(AppUser.class), commandWithPercentage(25), this.changes);

        order.verify(this.account).setWithdrawalChargePercentageOverride(new BigDecimal("25"));
        order.verify(this.account).beginClosureSettlement(CLOSED_DATE);
        order.verify(this.account).setWithdrawalChargePercentageOverride(null);
    }

    @Test
    void theOverrideIsClearedEvenWhenTheClosureFails() {
        when(this.account.beginClosureSettlement(CLOSED_DATE)).thenReturn(false);
        when(this.coreDomainService.handleWithdrawal(any(), any(), any(), any(), any(), anyBoolean(), anyBoolean()))
                .thenThrow(new IllegalStateException("payout failed"));

        assertThatThrownBy(() -> this.service.handleDynamicDepositPreMatureClosure(this.account, null, mock(AppUser.class),
                commandWithPercentage(25), this.changes)).isInstanceOf(IllegalStateException.class);

        verify(this.account).setWithdrawalChargePercentageOverride(null);
    }

    @Test
    void aRequestWithoutAPercentageLeavesTheAccountsOverrideAlone() {
        when(this.account.beginClosureSettlement(CLOSED_DATE)).thenReturn(false);

        this.service.handleDynamicDepositPreMatureClosure(this.account, null, mock(AppUser.class), command(WITHDRAW, null), this.changes);

        verify(this.account, never()).setWithdrawalChargePercentageOverride(any());
    }

    @Test
    void aPrematureClosureNeverLiftsTheWithdrawalLock() {
        when(this.account.beginClosureSettlement(CLOSED_DATE)).thenReturn(false);

        this.service.handleDynamicDepositPreMatureClosure(this.account, null, mock(AppUser.class), command(WITHDRAW, null), this.changes);

        verify(this.account, never()).markClosingAtMaturity(anyBoolean());
    }

    @Test
    void anEmptyAccountIsClosedWithoutSettlementOrPayout() {
        this.balance.set(BigDecimal.ZERO);

        final Long transactionId = this.service.handleDynamicDepositPreMatureClosure(this.account, null, mock(AppUser.class),
                command(WITHDRAW, null), this.changes);

        assertThat(transactionId).isNull();
        verify(this.account, never()).beginClosureSettlement(any());
        verify(this.coreDomainService, never()).handleWithdrawal(any(), any(), any(), any(), any(), anyBoolean(), anyBoolean());
        verify(this.account).close(any(AppUser.class), any(JsonCommand.class));
    }

    @Test
    void fixedAndRecurringDepositClosuresAreDelegatedToCoreUnchanged() {
        final FixedDepositAccount fixedDeposit = mock(FixedDepositAccount.class);
        final JsonCommand command = command(WITHDRAW, null);
        when(this.coreDomainService.handleFDAccountPreMatureClosure(fixedDeposit, null, null, command, this.changes)).thenReturn(42L);

        assertThat(this.service.handleFDAccountPreMatureClosure(fixedDeposit, null, null, command, this.changes)).isEqualTo(42L);
        verify(this.coreDomainService).handleFDAccountPreMatureClosure(fixedDeposit, null, null, command, this.changes);
    }

    private static JsonCommand commandWithPercentage(final int percentage) {
        final JsonObject payload = new JsonObject();
        payload.addProperty("dateFormat", "dd MMMM yyyy");
        payload.addProperty("locale", "en");
        payload.addProperty("closedOnDate", "20 January 2026");
        payload.addProperty("onAccountClosureId", WITHDRAW);
        payload.addProperty("earlyWithdrawalChargePercentage", percentage);
        final String json = payload.toString();
        return JsonCommand.fromExistingCommand(null, json, JsonParser.parseString(json), new FromJsonHelper(), null, null, null, null, null,
                null, ACCOUNT_ID, null, null, null, null, null, null, null);
    }

    private static JsonCommand command(final int onAccountClosureId, final Long toSavingsAccountId) {
        final JsonObject payload = new JsonObject();
        payload.addProperty("dateFormat", "dd MMMM yyyy");
        payload.addProperty("locale", "en");
        payload.addProperty("closedOnDate", "20 January 2026");
        payload.addProperty("onAccountClosureId", onAccountClosureId);
        if (toSavingsAccountId != null) {
            payload.addProperty("toSavingsAccountId", toSavingsAccountId);
            payload.addProperty("transferDescription", "closure proceeds");
        }
        final String json = payload.toString();
        return JsonCommand.fromExistingCommand(null, json, JsonParser.parseString(json), new FromJsonHelper(), null, null, null, null, null,
                null, ACCOUNT_ID, null, null, null, null, null, null, null);
    }
}
