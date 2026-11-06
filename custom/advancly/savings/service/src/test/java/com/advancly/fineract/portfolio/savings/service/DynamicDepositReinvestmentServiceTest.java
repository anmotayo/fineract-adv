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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.advancly.fineract.portfolio.savings.domain.DepositAccountDynamicDetail;
import com.advancly.fineract.portfolio.savings.domain.DynamicDepositAccount;
import com.advancly.fineract.portfolio.savings.domain.DynamicDepositAccountAssembler;
import com.advancly.fineract.portfolio.savings.domain.DynamicDepositAccountRepository;
import com.google.gson.JsonObject;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Set;
import org.apache.fineract.infrastructure.accountnumberformat.domain.AccountNumberFormat;
import org.apache.fineract.infrastructure.accountnumberformat.domain.AccountNumberFormatRepositoryWrapper;
import org.apache.fineract.infrastructure.accountnumberformat.domain.EntityAccountType;
import org.apache.fineract.infrastructure.core.api.JsonCommand;
import org.apache.fineract.infrastructure.core.serialization.FromJsonHelper;
import org.apache.fineract.organisation.staff.domain.Staff;
import org.apache.fineract.portfolio.account.data.PortfolioAccountData;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

/**
 * The deposit a closing Dynamic Deposit is reinvested into is opened through the same assembly a normal submission
 * uses, then approved and activated on the closure date. These tests pin the request it is assembled from - what is
 * carried over from the closing account and, as importantly, what deliberately is not - and the order of the steps.
 */
class DynamicDepositReinvestmentServiceTest {

    private static final LocalDate ACTIVATION_DATE = LocalDate.of(2026, 7, 1);
    private static final BigDecimal AMOUNT = BigDecimal.valueOf(1050);
    private static final Long LINKED_ACCOUNT_ID = 77L;

    private DynamicDepositAccountAssembler assembler;
    private DynamicDepositAccountRepository repository;
    private AccountNumberGenerator accountNumberGenerator;
    private AccountNumberFormatRepositoryWrapper accountNumberFormatRepository;
    private DepositAccountAssembler depositAccountAssembler;
    private DepositAccountDataValidator depositAccountDataValidator;
    private AccountAssociationsRepository accountAssociationsRepository;
    private AccountAssociationsReadPlatformService accountAssociationsReadPlatformService;
    private SavingsAccountWritePlatformService savingsAccountWritePlatformService;
    private DynamicDepositReinvestmentService service;

    private DynamicDepositAccount closing;
    private DynamicDepositAccount reinvested;

    @BeforeEach
    void setUp() {
        this.assembler = mock(DynamicDepositAccountAssembler.class);
        this.repository = mock(DynamicDepositAccountRepository.class);
        this.accountNumberGenerator = mock(AccountNumberGenerator.class);
        this.accountNumberFormatRepository = mock(AccountNumberFormatRepositoryWrapper.class);
        this.depositAccountAssembler = mock(DepositAccountAssembler.class);
        this.depositAccountDataValidator = mock(DepositAccountDataValidator.class);
        this.accountAssociationsRepository = mock(AccountAssociationsRepository.class);
        this.accountAssociationsReadPlatformService = mock(AccountAssociationsReadPlatformService.class);
        this.savingsAccountWritePlatformService = mock(SavingsAccountWritePlatformService.class);
        this.service = new DynamicDepositReinvestmentService(this.assembler, this.repository, this.accountNumberGenerator,
                this.accountNumberFormatRepository, this.depositAccountAssembler, this.depositAccountDataValidator,
                this.accountAssociationsRepository, this.accountAssociationsReadPlatformService, this.savingsAccountWritePlatformService,
                new FromJsonHelper(), mock(DynamicDepositMaturityAmountService.class));

        this.closing = closingAccount();
        this.reinvested = mock(DynamicDepositAccount.class);
        when(this.assembler.assembleFrom(any(JsonCommand.class))).thenReturn(this.reinvested);
        when(this.repository.saveAndFlush(this.reinvested)).thenReturn(this.reinvested);
    }

    @Test
    void theNewDepositIsAssembledFromTheClosingAccountsTermsForTheReinvestedAmountAndWithoutACopiedRate() {
        this.service.reinvest(this.closing, AMOUNT, ACTIVATION_DATE, mock(AppUser.class));

        final JsonObject submitted = assembledRequest();
        assertThat(submitted.get("clientId").getAsLong()).isEqualTo(11L);
        assertThat(submitted.has("groupId")).isFalse();
        assertThat(submitted.get("productId").getAsLong()).isEqualTo(10L);
        assertThat(submitted.get("fieldOfficerId").getAsLong()).isEqualTo(5L);
        assertThat(submitted.get("submittedOnDate").getAsString()).isEqualTo("2026-07-01");
        assertThat(submitted.get("dateFormat").getAsString()).isEqualTo("yyyy-MM-dd");
        assertThat(submitted.get("depositAmount").getAsBigDecimal()).isEqualByComparingTo(AMOUNT);
        assertThat(submitted.get("depositPeriod").getAsInt()).isEqualTo(12);
        assertThat(submitted.get("depositPeriodFrequencyId").getAsInt()).isEqualTo(2);
        assertThat(submitted.get("interestCompoundingPeriodType").getAsInt()).isEqualTo(8);
        assertThat(submitted.get("interestPostingPeriodType").getAsInt()).isEqualTo(4);
        assertThat(submitted.get("interestCalculationType").getAsInt()).isEqualTo(1);
        assertThat(submitted.get("interestCalculationDaysInYearType").getAsInt()).isEqualTo(365);
        assertThat(submitted.get("withHoldTax").getAsBoolean()).isTrue();
        assertThat(submitted.get("withHoldTaxPostingTypeId").getAsInt()).isEqualTo(1);
        assertThat(submitted.get("transferInterestToSavings").getAsBoolean()).isTrue();
        assertThat(submitted.get("allowWithdrawal").getAsBoolean()).isFalse();
        assertThat(submitted.get("dynamicRateEnabled").getAsBoolean()).isTrue();
        // A dynamic deposit's rate depends on how much is invested, so it is resolved for the new amount, not copied.
        assertThat(submitted.has("nominalAnnualInterestRate")).isFalse();
        // Fees are not carried over (as for a reinvested Fixed Deposit); the product's own early-withdrawal charge is
        // attached by the assembly itself.
        assertThat(submitted.has("charges")).isFalse();
        assertThat(submitted.has("accountNo")).isFalse();
    }

    @Test
    void aGroupAccountIsReinvestedForTheSameGroup() {
        when(this.closing.clientId()).thenReturn(null);
        when(this.closing.groupId()).thenReturn(21L);

        this.service.reinvest(this.closing, AMOUNT, ACTIVATION_DATE, mock(AppUser.class));

        final JsonObject submitted = assembledRequest();
        assertThat(submitted.has("clientId")).isFalse();
        assertThat(submitted.get("groupId").getAsLong()).isEqualTo(21L);
    }

    @Test
    void theNewDepositIsSavedThenApprovedAndActivatedOnTheClosureDateAndItsOpeningDepositIsPosted() {
        final AppUser user = mock(AppUser.class);

        final DynamicDepositAccount result = this.service.reinvest(this.closing, AMOUNT, ACTIVATION_DATE, user);

        assertThat(result).isSameAs(this.reinvested);
        final InOrder order = inOrder(this.assembler, this.repository, this.reinvested, this.savingsAccountWritePlatformService);
        order.verify(this.assembler).assembleFrom(any(JsonCommand.class));
        order.verify(this.repository).saveAndFlush(this.reinvested);
        order.verify(this.reinvested).approveAndActivateApplication(ACTIVATION_DATE, user);
        order.verify(this.reinvested).updateMaturityDate();
        order.verify(this.savingsAccountWritePlatformService).processPostActiveActions(eq(this.reinvested), any(DateTimeFormatter.class),
                any(Set.class), any(Set.class));
        order.verify(this.repository).saveAndFlush(this.reinvested);
    }

    @Test
    void theLinkedSavingsAccountIsCarriedOverAndTheOpeningDepositIsNotPulledFromIt() {
        final PortfolioAccountData linked = mock(PortfolioAccountData.class);
        when(linked.getId()).thenReturn(LINKED_ACCOUNT_ID);
        when(this.accountAssociationsReadPlatformService.retriveSavingsLinkedAssociation(1L)).thenReturn(linked);
        final SavingsAccount linkedSavings = mock(SavingsAccount.class);
        when(this.depositAccountAssembler.assembleFrom(LINKED_ACCOUNT_ID, DepositAccountType.SAVINGS_DEPOSIT)).thenReturn(linkedSavings);

        this.service.reinvest(this.closing, AMOUNT, ACTIVATION_DATE, mock(AppUser.class));

        assertThat(assembledRequest().get("linkAccountId").getAsLong()).isEqualTo(LINKED_ACCOUNT_ID);
        verify(this.depositAccountDataValidator).validatelinkedSavingsAccount(linkedSavings, this.reinvested);
        verify(this.accountAssociationsRepository).save(any(AccountAssociations.class));
        // The money is the closing account's payout - activation must not also debit the linked account for it.
        verify(this.savingsAccountWritePlatformService).processPostActiveActions(eq(this.reinvested), any(), any(), any());
        verify(this.reinvested, never()).markOpeningDepositFundedByLinkedTransfer();
    }

    @Test
    void withoutALinkedAccountNoAssociationIsCreated() {
        this.service.reinvest(this.closing, AMOUNT, ACTIVATION_DATE, mock(AppUser.class));

        assertThat(assembledRequest().has("linkAccountId")).isFalse();
        verify(this.accountAssociationsRepository, never()).save(any());
    }

    @Test
    void anAccountNumberIsGeneratedWhenTheProductNumbersAccountsAutomatically() {
        when(this.reinvested.isAccountNumberRequiresAutoGeneration()).thenReturn(true);
        final AccountNumberFormat format = mock(AccountNumberFormat.class);
        when(this.accountNumberFormatRepository.findByAccountType(EntityAccountType.SAVINGS)).thenReturn(format);
        when(this.accountNumberGenerator.generate(this.reinvested, format)).thenReturn("000000099");

        this.service.reinvest(this.closing, AMOUNT, ACTIVATION_DATE, mock(AppUser.class));

        verify(this.reinvested).updateAccountNo("000000099");
    }

    private JsonObject assembledRequest() {
        final ArgumentCaptor<JsonCommand> captor = ArgumentCaptor.forClass(JsonCommand.class);
        verify(this.assembler).assembleFrom(captor.capture());
        return com.google.gson.JsonParser.parseString(captor.getValue().json()).getAsJsonObject();
    }

    private static DynamicDepositAccount closingAccount() {
        final DynamicDepositAccount account = mock(DynamicDepositAccount.class);
        when(account.getId()).thenReturn(1L);
        when(account.clientId()).thenReturn(11L);
        when(account.groupId()).thenReturn(null);
        when(account.productId()).thenReturn(10L);
        final Staff officer = mock(Staff.class);
        when(officer.getId()).thenReturn(5L);
        when(account.getSavingsOfficer()).thenReturn(officer);
        when(account.getInterestCompoundingPeriodType()).thenReturn(8);
        when(account.getInterestPostingPeriodType()).thenReturn(4);
        when(account.getInterestCalculationType()).thenReturn(1);
        when(account.getInterestCalculationDaysInYearType()).thenReturn(365);
        when(account.getLockinPeriodFrequency()).thenReturn(null);
        when(account.isWithdrawalFeeApplicableForTransfer()).thenReturn(false);
        when(account.isWithHoldTax()).thenReturn(true);

        final DepositAccountTermAndPreClosure term = mock(DepositAccountTermAndPreClosure.class);
        when(term.depositPeriod()).thenReturn(12);
        when(term.depositPeriodFrequency()).thenReturn(2);
        when(term.getWithHoldTaxPostingType()).thenReturn(1);
        when(term.isTransferInterestToLinkedAccount()).thenReturn(true);
        when(account.accountTermAndPreClosure()).thenReturn(term);

        final DepositAccountDynamicDetail detail = mock(DepositAccountDynamicDetail.class);
        when(detail.isAllowWithdrawal()).thenReturn(false);
        when(detail.isDynamicRateEnabled()).thenReturn(true);
        when(account.dynamicDetail()).thenReturn(detail);
        return account;
    }
}
