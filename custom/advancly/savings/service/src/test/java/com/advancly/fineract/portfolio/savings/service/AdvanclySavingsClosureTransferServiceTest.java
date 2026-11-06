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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.advancly.fineract.portfolio.account.service.AdvanclyAccountTransfersWritePlatformService;
import com.advancly.fineract.portfolio.account.service.AdvanclyAccountTransfersWritePlatformService.ClosureCharge;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import org.apache.fineract.accounting.journalentry.service.JournalEntryWritePlatformService;
import org.apache.fineract.infrastructure.businessdate.domain.BusinessDateType;
import org.apache.fineract.infrastructure.configuration.domain.ConfigurationDomainService;
import org.apache.fineract.infrastructure.core.api.JsonCommand;
import org.apache.fineract.infrastructure.core.data.CommandProcessingResult;
import org.apache.fineract.infrastructure.core.exception.GeneralPlatformDomainRuleException;
import org.apache.fineract.infrastructure.core.exception.UnsupportedParameterException;
import org.apache.fineract.infrastructure.core.serialization.FromJsonHelper;
import org.apache.fineract.infrastructure.core.service.ThreadLocalContextUtil;
import org.apache.fineract.infrastructure.security.service.PlatformSecurityContext;
import org.apache.fineract.portfolio.account.data.AccountTransferDTO;
import org.apache.fineract.portfolio.savings.DepositAccountType;
import org.apache.fineract.portfolio.savings.domain.SavingsAccount;
import org.apache.fineract.portfolio.savings.domain.SavingsAccountAssembler;
import org.apache.fineract.portfolio.savings.domain.SavingsAccountRepositoryWrapper;
import org.apache.fineract.portfolio.savings.domain.SavingsAccountSummary;
import org.apache.fineract.portfolio.savings.domain.SavingsAccountTransactionRepository;
import org.apache.fineract.portfolio.savings.service.SavingsAccountWritePlatformService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AdvanclySavingsClosureTransferServiceTest {

    private static final Long SAVINGS_ID = 7L;
    private static final Long DESTINATION_ID = 9L;
    private static final Long CLIENT_ID = 100L;

    @Mock
    private PlatformSecurityContext context;
    @Mock
    private SavingsAccountAssembler savingsAccountAssembler;
    @Mock
    private SavingsAccountRepositoryWrapper savingsAccountRepository;
    @Mock
    private SavingsAccountTransactionRepository savingsAccountTransactionRepository;
    @Mock
    private ConfigurationDomainService configurationDomainService;
    @Mock
    private JournalEntryWritePlatformService journalEntryWritePlatformService;
    @Mock
    private AdvanclyInterestChargeApplicationService interestChargeApplicationService;
    @Mock
    private AdvanclyAccountTransfersWritePlatformService accountTransfersWritePlatformService;
    @Mock
    private SavingsAccountWritePlatformService coreSavingsAccountWritePlatformService;
    @Mock
    private EntityManager entityManager;
    @Mock
    private SavingsAccount account;
    @Mock
    private SavingsAccount destination;
    @Mock
    private SavingsAccountSummary summary;

    private AdvanclySavingsClosureTransferService service;
    private final LocalDate closedDate = LocalDate.of(2026, 10, 19);

    @BeforeEach
    void setUp() {
        final HashMap<BusinessDateType, LocalDate> businessDates = new HashMap<>();
        businessDates.put(BusinessDateType.BUSINESS_DATE, closedDate);
        businessDates.put(BusinessDateType.COB_DATE, closedDate.minusDays(1));
        ThreadLocalContextUtil.setBusinessDates(businessDates);
        service = new AdvanclySavingsClosureTransferService(context, savingsAccountAssembler, savingsAccountRepository,
                savingsAccountTransactionRepository, configurationDomainService, journalEntryWritePlatformService,
                interestChargeApplicationService, accountTransfersWritePlatformService, new FromJsonHelper(),
                coreSavingsAccountWritePlatformService);
        ReflectionTestUtils.setField(service, "entityManager", entityManager);

        when(savingsAccountAssembler.assembleFrom(SAVINGS_ID, false)).thenReturn(account);
        when(savingsAccountAssembler.assembleFrom(DESTINATION_ID, false)).thenReturn(destination);
        when(account.getId()).thenReturn(SAVINGS_ID);
        when(account.depositAccountType()).thenReturn(DepositAccountType.SAVINGS_DEPOSIT);
        when(account.getSummary()).thenReturn(summary);
        when(account.getTransactions()).thenReturn(new ArrayList<>());
        when(account.getNominalAnnualInterestRate()).thenReturn(BigDecimal.ZERO);
        when(account.clientId()).thenReturn(CLIENT_ID);
        when(destination.isActive()).thenReturn(true);
        when(destination.clientId()).thenReturn(CLIENT_ID);
        // an unstubbed Long comes back from Mockito as 0, which would make every pair of accounts look like one group
        when(account.groupId()).thenReturn(null);
        when(destination.groupId()).thenReturn(null);
        when(summary.getAccountBalance()).thenReturn(new BigDecimal("1000.00"));
        when(coreSavingsAccountWritePlatformService.close(eq(SAVINGS_ID), any(JsonCommand.class)))
                .thenReturn(mock(CommandProcessingResult.class));
        when(interestChargeApplicationService.quoteClosureCharge(any(), any(), any(Boolean.class), any(), any(), any()))
                .thenReturn(BigDecimal.ZERO);
    }

    @Test
    void aCloseWithoutADestinationIsLeftToCore() {
        assertThat(AdvanclySavingsClosureTransferService.isCloseWithTransfer(command(base()))).isFalse();
        assertThat(AdvanclySavingsClosureTransferService.isCloseWithTransfer(command(base().addProperty("toSavingsAccountId", 9))))
                .isTrue();
    }

    @Test
    void transfersTheBalanceLessTheChargeThenClosesWithCoreCloseParametersOnly() {
        when(interestChargeApplicationService.quoteClosureCharge(eq(account), eq(closedDate), eq(true), any(), any(), any()))
                .thenReturn(new BigDecimal("123.45"));

        service.closeWithTransfer(SAVINGS_ID,
                command(base().addProperty("toSavingsAccountId", 9).addProperty("applyEarlyWithdrawalCharge", true)
                        .addProperty("transferDescription", "closure").addProperty("earlyWithdrawalChargePercentage", 25)));

        final ArgumentCaptor<AccountTransferDTO> transfer = ArgumentCaptor.forClass(AccountTransferDTO.class);
        final ArgumentCaptor<ClosureCharge> charge = ArgumentCaptor.forClass(ClosureCharge.class);
        verify(accountTransfersWritePlatformService).transferClosurePayout(transfer.capture(), charge.capture());
        assertThat(transfer.getValue().getTransactionAmount()).isEqualByComparingTo("876.55");
        assertThat(transfer.getValue().getTransactionDate()).isEqualTo(closedDate);
        assertThat(charge.getValue().applyEarlyWithdrawalCharge()).isTrue();
        assertThat(charge.getValue().earlyWithdrawalChargePercentage()).isEqualByComparingTo("25");

        final ArgumentCaptor<JsonCommand> close = ArgumentCaptor.forClass(JsonCommand.class);
        verify(coreSavingsAccountWritePlatformService).close(eq(SAVINGS_ID), close.capture());
        final JsonObject sent = close.getValue().parsedJson().getAsJsonObject();
        assertThat(sent.has("toSavingsAccountId")).isFalse();
        assertThat(sent.has("transferDescription")).isFalse();
        assertThat(sent.has("earlyWithdrawalChargePercentage")).isFalse();
        assertThat(sent.get("withdrawBalance").getAsBoolean()).isTrue();
        assertThat(sent.get("closedOnDate").getAsString()).isEqualTo(base().get("closedOnDate"));
    }

    @Test
    void flushesAndClearsBetweenTheTransferAndTheCloseSoCoreLoadsTheAccountFresh() {
        service.closeWithTransfer(SAVINGS_ID, command(base().addProperty("toSavingsAccountId", 9)));

        final InOrder order = inOrder(accountTransfersWritePlatformService, entityManager, coreSavingsAccountWritePlatformService);
        order.verify(accountTransfersWritePlatformService).transferClosurePayout(any(), any());
        order.verify(entityManager).flush();
        order.verify(entityManager).clear();
        order.verify(coreSavingsAccountWritePlatformService).close(eq(SAVINGS_ID), any(JsonCommand.class));
    }

    @Test
    void noChargeWithoutTheFlagMeansTheWholeBalanceMoves() {
        service.closeWithTransfer(SAVINGS_ID, command(base().addProperty("toSavingsAccountId", 9)));

        final ArgumentCaptor<AccountTransferDTO> transfer = ArgumentCaptor.forClass(AccountTransferDTO.class);
        verify(accountTransfersWritePlatformService).transferClosurePayout(transfer.capture(), any());
        assertThat(transfer.getValue().getTransactionAmount()).isEqualByComparingTo("1000.00");
    }

    @Test
    void aZeroBalanceIsJustClosed() {
        when(summary.getAccountBalance()).thenReturn(BigDecimal.ZERO.setScale(2));

        service.closeWithTransfer(SAVINGS_ID, command(base().addProperty("toSavingsAccountId", 9)));

        verify(accountTransfersWritePlatformService, never()).transferClosurePayout(any(), any());
        verify(coreSavingsAccountWritePlatformService).close(eq(SAVINGS_ID), any(JsonCommand.class));
    }

    @Test
    void aChargeThatTakesTheWholeBalanceLeavesNothingToTransfer() {
        when(interestChargeApplicationService.quoteClosureCharge(any(), any(), any(Boolean.class), any(), any(), any()))
                .thenReturn(new BigDecimal("1000.00"));

        assertThatThrownBy(() -> service.closeWithTransfer(SAVINGS_ID,
                command(base().addProperty("toSavingsAccountId", 9).addProperty("applyEarlyWithdrawalCharge", true))))
                        .isInstanceOf(GeneralPlatformDomainRuleException.class);
        verify(accountTransfersWritePlatformService, never()).transferClosurePayout(any(), any());
        verify(coreSavingsAccountWritePlatformService, never()).close(any(), any());
    }

    @Test
    void rejectsParametersNeitherCoreNorThisClosureKnows() {
        assertThatThrownBy(() -> service.closeWithTransfer(SAVINGS_ID,
                command(base().addProperty("toSavingsAccountId", 9).addProperty("somethingElse", "x"))))
                .isInstanceOf(UnsupportedParameterException.class);
    }

    @Test
    void rejectsADestinationOfAnotherClient() {
        when(destination.clientId()).thenReturn(CLIENT_ID + 1);

        assertThatThrownBy(() -> service.closeWithTransfer(SAVINGS_ID, command(base().addProperty("toSavingsAccountId", 9))))
                .isInstanceOf(GeneralPlatformDomainRuleException.class);
        verify(accountTransfersWritePlatformService, never()).transferClosurePayout(any(), any());
    }

    @Test
    void rejectsAnInactiveDestination() {
        when(destination.isActive()).thenReturn(false);

        assertThatThrownBy(() -> service.closeWithTransfer(SAVINGS_ID, command(base().addProperty("toSavingsAccountId", 9))))
                .isInstanceOf(GeneralPlatformDomainRuleException.class);
    }

    @Test
    void rejectsTheAccountItselfAsTheDestination() {
        assertThatThrownBy(() -> service.closeWithTransfer(SAVINGS_ID, command(base().addProperty("toSavingsAccountId", 7))))
                .isInstanceOf(GeneralPlatformDomainRuleException.class);
    }

    @Test
    void rejectsADepositAccount() {
        when(account.depositAccountType()).thenReturn(DepositAccountType.FIXED_DEPOSIT);

        assertThatThrownBy(() -> service.closeWithTransfer(SAVINGS_ID, command(base().addProperty("toSavingsAccountId", 9))))
                .isInstanceOf(GeneralPlatformDomainRuleException.class);
    }

    @Test
    void rejectsAWithdrawalBlockedAccount() {
        when(account.isWithdrawalBlockedByAccountRule()).thenReturn(true);

        assertThatThrownBy(() -> service.closeWithTransfer(SAVINGS_ID, command(base().addProperty("toSavingsAccountId", 9))))
                .isInstanceOf(GeneralPlatformDomainRuleException.class);
    }

    @Test
    void rejectsAFutureClosedOnDate() {
        final Builder future = base();
        future.addProperty("closedOnDate", closedDate.plusDays(3).format(java.time.format.DateTimeFormatter.ofPattern("dd MMMM yyyy")));

        assertThatThrownBy(() -> service.closeWithTransfer(SAVINGS_ID, command(future.addProperty("toSavingsAccountId", 9))))
                .isInstanceOf(GeneralPlatformDomainRuleException.class);
    }

    /** A JSON body under construction: {@code addProperty} returns it so calls chain. */
    private static final class Builder {

        private final JsonObject json = new JsonObject();

        Builder addProperty(final String name, final Object value) {
            if (value instanceof Number number) {
                json.addProperty(name, number);
            } else if (value instanceof Boolean flag) {
                json.addProperty(name, flag);
            } else {
                json.addProperty(name, String.valueOf(value));
            }
            return this;
        }

        String get(final String name) {
            return json.get(name).getAsString();
        }
    }

    private Builder base() {
        final Builder payload = new Builder();
        payload.addProperty("dateFormat", "dd MMMM yyyy");
        payload.addProperty("locale", "en");
        payload.addProperty("closedOnDate", closedDate.format(java.time.format.DateTimeFormatter.ofPattern("dd MMMM yyyy")));
        return payload;
    }

    private JsonCommand command(final Builder payload) {
        final String json = payload.json.toString();
        return JsonCommand.fromExistingCommand(null, json, JsonParser.parseString(json), new FromJsonHelper(), null, null, null, null, null,
                null, SAVINGS_ID, null, null, null, null, null, null, null);
    }
}
