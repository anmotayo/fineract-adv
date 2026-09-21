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

import static com.advancly.fineract.portfolio.savings.DynamicDepositApiConstants.linkedAccountParamName;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.advancly.fineract.portfolio.savings.domain.DepositAccountDynamicDetail;
import com.advancly.fineract.portfolio.savings.domain.DynamicDepositAccount;
import com.advancly.fineract.portfolio.savings.domain.DynamicDepositAccountAssembler;
import com.advancly.fineract.portfolio.savings.domain.DynamicDepositAccountRepository;
import com.advancly.fineract.portfolio.savings.domain.DynamicDepositProduct;
import com.advancly.fineract.portfolio.savings.validator.DynamicDepositAccountDataValidator;
import java.lang.reflect.Constructor;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Optional;
import org.apache.fineract.accounting.common.AccountingRuleType;
import org.apache.fineract.infrastructure.accountnumberformat.domain.AccountNumberFormatRepositoryWrapper;
import org.apache.fineract.infrastructure.businessdate.domain.BusinessDateType;
import org.apache.fineract.infrastructure.core.api.JsonCommand;
import org.apache.fineract.infrastructure.core.data.CommandProcessingResult;
import org.apache.fineract.infrastructure.core.domain.ExternalId;
import org.apache.fineract.infrastructure.core.serialization.FromJsonHelper;
import org.apache.fineract.infrastructure.core.service.ThreadLocalContextUtil;
import org.apache.fineract.infrastructure.security.service.PlatformSecurityContext;
import org.apache.fineract.organisation.monetary.domain.MonetaryCurrency;
import org.apache.fineract.portfolio.account.domain.AccountAssociationType;
import org.apache.fineract.portfolio.account.domain.AccountAssociations;
import org.apache.fineract.portfolio.account.domain.AccountAssociationsRepository;
import org.apache.fineract.portfolio.account.service.AccountNumberGenerator;
import org.apache.fineract.portfolio.accountdetails.domain.AccountType;
import org.apache.fineract.portfolio.note.domain.NoteRepository;
import org.apache.fineract.portfolio.savings.DepositAccountType;
import org.apache.fineract.portfolio.savings.DepositsApiConstants;
import org.apache.fineract.portfolio.savings.SavingsCompoundingInterestPeriodType;
import org.apache.fineract.portfolio.savings.SavingsInterestCalculationDaysInYearType;
import org.apache.fineract.portfolio.savings.SavingsInterestCalculationType;
import org.apache.fineract.portfolio.savings.SavingsPeriodFrequencyType;
import org.apache.fineract.portfolio.savings.SavingsPostingInterestPeriodType;
import org.apache.fineract.portfolio.savings.data.DepositAccountDataValidator;
import org.apache.fineract.portfolio.savings.data.SavingsAccountTransactionDataValidator;
import org.apache.fineract.portfolio.savings.domain.DepositAccountAssembler;
import org.apache.fineract.portfolio.savings.domain.DepositAccountTermAndPreClosure;
import org.apache.fineract.portfolio.savings.domain.DepositPreClosureDetail;
import org.apache.fineract.portfolio.savings.domain.DepositTermDetail;
import org.apache.fineract.portfolio.savings.domain.SavingsAccount;
import org.apache.fineract.portfolio.savings.domain.SavingsAccountTransactionSummaryWrapper;
import org.apache.fineract.portfolio.savings.domain.SavingsHelper;
import org.apache.fineract.portfolio.savings.service.SavingsAccountApplicationTransitionApiJsonValidator;
import org.apache.fineract.portfolio.savings.service.SavingsAccountWritePlatformService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Covers the Phase 5 Task 4 linked-account-association wiring added to {@code submitApplication(...)}: mirrors the
 * FD/RD precedent in {@code DepositApplicationProcessWritePlatformServiceJpaRepositoryImpl#submitFDApplication}, which
 * creates an {@link AccountAssociations} row (type {@code LINKED_ACCOUNT_ASSOCIATION}) whenever the create request
 * carries a {@code linkAccountId}, and does nothing otherwise.
 */
@ExtendWith(MockitoExtension.class)
class DynamicDepositAccountWritePlatformServiceJpaRepositoryImplTest {

    @Mock
    private PlatformSecurityContext context;
    @Mock
    private DynamicDepositAccountRepository dynamicDepositAccountRepository;
    @Mock
    private DynamicDepositAccountDataValidator dynamicDepositAccountDataValidator;
    @Mock
    private DynamicDepositAccountAssembler dynamicDepositAccountAssembler;
    @Mock
    private AccountNumberGenerator accountNumberGenerator;
    @Mock
    private AccountNumberFormatRepositoryWrapper accountNumberFormatRepository;
    @Mock
    private NoteRepository noteRepository;
    @Mock
    private SavingsAccountApplicationTransitionApiJsonValidator savingsAccountApplicationTransitionApiJsonValidator;
    @Mock
    private SavingsAccountTransactionDataValidator savingsAccountTransactionDataValidator;
    @Mock
    private SavingsAccountWritePlatformService savingsAccountWritePlatformService;
    @Mock
    private DepositAccountAssembler depositAccountAssembler;
    @Mock
    private DepositAccountDataValidator depositAccountDataValidator;
    @Mock
    private AccountAssociationsRepository accountAssociationsRepository;
    @Mock
    private SavingsAccountTransactionSummaryWrapper savingsAccountTransactionSummaryWrapper;
    @Mock
    private SavingsHelper savingsHelper;
    @Mock
    private AdvanclyChargeInterestRuleValidator chargeInterestRuleValidator;

    private DynamicDepositAccountWritePlatformServiceJpaRepositoryImpl service;

    @BeforeEach
    void setUp() {
        service = new DynamicDepositAccountWritePlatformServiceJpaRepositoryImpl(context, dynamicDepositAccountRepository,
                dynamicDepositAccountDataValidator, dynamicDepositAccountAssembler, accountNumberGenerator, accountNumberFormatRepository,
                noteRepository, savingsAccountApplicationTransitionApiJsonValidator, savingsAccountTransactionDataValidator,
                savingsAccountWritePlatformService, depositAccountAssembler, depositAccountDataValidator, accountAssociationsRepository,
                savingsAccountTransactionSummaryWrapper, savingsHelper, chargeInterestRuleValidator);

        // The modifyApplication tests exercise the real DynamicDepositAccountDataValidator end-to-end, which reaches
        // account.validateNewApplicationState(...) -> DateUtils.isDateInTheFuture(submittedOnDate) ->
        // ThreadLocalContextUtil.getBusinessDates(). That thread-local is otherwise only populated by whichever test
        // happens to run first in this JVM, so seed it explicitly here rather than relying on execution order.
        final HashMap<BusinessDateType, LocalDate> businessDates = new HashMap<>();
        businessDates.put(BusinessDateType.BUSINESS_DATE, LocalDate.of(2026, 9, 15));
        ThreadLocalContextUtil.setBusinessDates(businessDates);
    }

    @Test
    void submitApplicationWithLinkAccountIdCreatesLinkedAccountAssociation() {
        Long savingsId = 5L;
        Long linkedSavingsAccountId = 9L;
        DynamicDepositAccount account = dynamicDepositAccount(savingsId);
        SavingsAccount linkedSavingsAccount = Mockito.mock(SavingsAccount.class);

        JsonCommand command = Mockito.mock(JsonCommand.class);
        when(command.json()).thenReturn("{}");
        when(command.longValueOfParameterNamed(DepositsApiConstants.linkedAccountParamName)).thenReturn(linkedSavingsAccountId);

        when(dynamicDepositAccountAssembler.assembleFrom(command)).thenReturn(account);
        when(depositAccountAssembler.assembleFrom(linkedSavingsAccountId, DepositAccountType.SAVINGS_DEPOSIT))
                .thenReturn(linkedSavingsAccount);

        CommandProcessingResult result = service.submitApplication(command);

        assertThat(result.getResourceId()).isEqualTo(savingsId);
        verify(depositAccountDataValidator).validatelinkedSavingsAccount(linkedSavingsAccount, account);

        ArgumentCaptor<AccountAssociations> captor = ArgumentCaptor.forClass(AccountAssociations.class);
        verify(accountAssociationsRepository).save(captor.capture());
        AccountAssociations saved = captor.getValue();
        assertThat(saved.linkedSavingsAccount()).isSameAs(linkedSavingsAccount);
        assertThat((SavingsAccount) ReflectionTestUtils.getField(saved, "savingsAccount")).isSameAs(account);
        assertThat((Integer) ReflectionTestUtils.getField(saved, "associationType"))
                .isEqualTo(AccountAssociationType.LINKED_ACCOUNT_ASSOCIATION.getValue());
        assertThat((boolean) ReflectionTestUtils.getField(saved, "active")).isTrue();
    }

    @Test
    void submitApplicationWithoutLinkAccountIdCreatesNoAssociation() {
        Long savingsId = 5L;
        DynamicDepositAccount account = dynamicDepositAccount(savingsId);

        JsonCommand command = Mockito.mock(JsonCommand.class);
        when(command.json()).thenReturn("{}");
        when(command.longValueOfParameterNamed(DepositsApiConstants.linkedAccountParamName)).thenReturn(null);

        when(dynamicDepositAccountAssembler.assembleFrom(command)).thenReturn(account);

        CommandProcessingResult result = service.submitApplication(command);

        assertThat(result.getResourceId()).isEqualTo(savingsId);
        verify(depositAccountAssembler, never()).assembleFrom(any(Long.class), any(DepositAccountType.class));
        verify(depositAccountDataValidator, never()).validatelinkedSavingsAccount(any(), any());
        verify(accountAssociationsRepository, never()).save(any());
    }

    /**
     * Phase 5 Task 5 regression: the now-deleted allow-withdrawal / transfer-interest coupling rule used to reject
     * {@code allowWithdrawal = false} combined with {@code transferInterestToSavings = true}. This proves that
     * combination now succeeds end-to-end through {@code modifyApplication(...)} - using the REAL
     * {@link DynamicDepositAccountDataValidator} (not a mock) so the new
     * {@code validateLinkedAccountRequiredWhenTransferInterestEnabled} rule is genuinely exercised - as long as a
     * linked account is present, and that the update path creates the {@link AccountAssociations} row exactly like
     * FD/RD's {@code modifyFDApplication}.
     */
    @Test
    void modifyApplicationWithAllowWithdrawalFalseAndTransferInterestToSavingsTrueAndValidLinkAccountSucceeds() {
        Long accountId = 5L;
        Long linkedSavingsAccountId = 9L;
        DynamicDepositAccount account = dynamicDepositAccountWithTransferInterestEnabled(accountId, false, true);
        SavingsAccount linkedSavingsAccount = Mockito.mock(SavingsAccount.class);

        JsonCommand command = Mockito.mock(JsonCommand.class);
        when(command.json()).thenReturn("{}");
        when(command.longValueOfParameterNamed(linkedAccountParamName)).thenReturn(linkedSavingsAccountId);

        when(dynamicDepositAccountRepository.findById(accountId)).thenReturn(Optional.of(account));
        when(accountAssociationsRepository.findBySavingsIdAndType(accountId, AccountAssociationType.LINKED_ACCOUNT_ASSOCIATION.getValue()))
                .thenReturn(null);
        when(depositAccountAssembler.assembleFrom(linkedSavingsAccountId, DepositAccountType.SAVINGS_DEPOSIT))
                .thenReturn(linkedSavingsAccount);

        DynamicDepositAccountDataValidator realValidator = new DynamicDepositAccountDataValidator(new FromJsonHelper());
        DynamicDepositAccountWritePlatformServiceJpaRepositoryImpl serviceWithRealValidator = new DynamicDepositAccountWritePlatformServiceJpaRepositoryImpl(
                context, dynamicDepositAccountRepository, realValidator, dynamicDepositAccountAssembler, accountNumberGenerator,
                accountNumberFormatRepository, noteRepository, savingsAccountApplicationTransitionApiJsonValidator,
                savingsAccountTransactionDataValidator, savingsAccountWritePlatformService, depositAccountAssembler,
                depositAccountDataValidator, accountAssociationsRepository, savingsAccountTransactionSummaryWrapper, savingsHelper,
                chargeInterestRuleValidator);

        assertThatCode(() -> serviceWithRealValidator.modifyApplication(accountId, command)).doesNotThrowAnyException();

        verify(depositAccountDataValidator).validatelinkedSavingsAccount(linkedSavingsAccount, account);
        ArgumentCaptor<AccountAssociations> captor = ArgumentCaptor.forClass(AccountAssociations.class);
        verify(accountAssociationsRepository).save(captor.capture());
        assertThat(captor.getValue().linkedSavingsAccount()).isSameAs(linkedSavingsAccount);
    }

    /**
     * Task review regression: {@code modifyApplication(...)} must NOT reject an ordinary request that simply omits
     * {@code linkAccountId} on an account that already has a satisfying linked-account association, even though
     * {@code transferInterestToSavings=true} on that account (so {@code isLinkedAccRequired} is true). Mirrors FD's own
     * {@code modifyFDApplication}, which only ever throws its "linked account required" error inside the two branches
     * where {@code linkAccountId} is null AND (the request explicitly cleared it, or no association exists at all) -
     * never when the field is simply untouched and an association already satisfies the requirement.
     */
    @Test
    void modifyApplicationOmittingLinkAccountIdWithExistingAssociationIsANoOp() {
        Long accountId = 5L;
        DynamicDepositAccount account = dynamicDepositAccountWithTransferInterestEnabled(accountId, true, true);
        SavingsAccount existingLinkedSavingsAccount = Mockito.mock(SavingsAccount.class);
        AccountAssociations existingAssociation = AccountAssociations.associateSavingsAccount(account, existingLinkedSavingsAccount,
                AccountAssociationType.LINKED_ACCOUNT_ASSOCIATION.getValue(), true);

        JsonCommand command = Mockito.mock(JsonCommand.class);
        when(command.json()).thenReturn("{}");
        // linkAccountId is entirely absent from the request: neither its value nor its presence was sent.
        when(command.longValueOfParameterNamed(linkedAccountParamName)).thenReturn(null);
        when(command.parameterExists(linkedAccountParamName)).thenReturn(false);

        when(dynamicDepositAccountRepository.findById(accountId)).thenReturn(Optional.of(account));
        when(accountAssociationsRepository.findBySavingsIdAndType(accountId, AccountAssociationType.LINKED_ACCOUNT_ASSOCIATION.getValue()))
                .thenReturn(existingAssociation);

        DynamicDepositAccountDataValidator realValidator = new DynamicDepositAccountDataValidator(new FromJsonHelper());
        DynamicDepositAccountWritePlatformServiceJpaRepositoryImpl serviceWithRealValidator = new DynamicDepositAccountWritePlatformServiceJpaRepositoryImpl(
                context, dynamicDepositAccountRepository, realValidator, dynamicDepositAccountAssembler, accountNumberGenerator,
                accountNumberFormatRepository, noteRepository, savingsAccountApplicationTransitionApiJsonValidator,
                savingsAccountTransactionDataValidator, savingsAccountWritePlatformService, depositAccountAssembler,
                depositAccountDataValidator, accountAssociationsRepository, savingsAccountTransactionSummaryWrapper, savingsHelper,
                chargeInterestRuleValidator);

        assertThatCode(() -> serviceWithRealValidator.modifyApplication(accountId, command)).doesNotThrowAnyException();

        verify(accountAssociationsRepository, never()).delete(any(AccountAssociations.class));
        verify(accountAssociationsRepository, never()).save(any());
        verify(depositAccountAssembler, never()).assembleFrom(any(Long.class), any(DepositAccountType.class));
        assertThat(existingAssociation.linkedSavingsAccount()).isSameAs(existingLinkedSavingsAccount);
    }

    private DynamicDepositAccount dynamicDepositAccountWithTransferInterestEnabled(Long accountId, boolean allowWithdrawal,
            boolean transferInterestToSavings) {
        MonetaryCurrency currency = new MonetaryCurrency("USD", 2, null);
        DynamicDepositProduct product = DynamicDepositProduct.createNew("Dynamic Deposit", "DD", "desc", currency, BigDecimal.TEN,
                SavingsCompoundingInterestPeriodType.DAILY, SavingsPostingInterestPeriodType.MONTHLY,
                SavingsInterestCalculationType.DAILY_BALANCE, SavingsInterestCalculationDaysInYearType.DAYS_365, null, null,
                AccountingRuleType.NONE, new HashSet<>(), null, new HashSet<>(), null, false, null, allowWithdrawal, false, false);

        DepositAccountTermAndPreClosure term = DepositAccountTermAndPreClosure
                .createNew(DepositPreClosureDetail.createFrom(false, null, null),
                        DepositTermDetail.createFrom(6, 6, SavingsPeriodFrequencyType.MONTHS, SavingsPeriodFrequencyType.MONTHS, null,
                                null),
                        null, BigDecimal.valueOf(100000), null, null, 6, SavingsPeriodFrequencyType.MONTHS, null, null,
                        transferInterestToSavings, null, null);

        DynamicDepositAccount account = DynamicDepositAccount.createNewApplicationForSubmittal(null, null, product, null, "ACC001",
                ExternalId.empty(), AccountType.INDIVIDUAL, LocalDate.of(2026, 1, 15), null, BigDecimal.TEN,
                SavingsCompoundingInterestPeriodType.DAILY, SavingsPostingInterestPeriodType.MONTHLY,
                SavingsInterestCalculationType.DAILY_BALANCE, SavingsInterestCalculationDaysInYearType.DAYS_365, null, null, null, false,
                new HashSet<>(), term, null, false);
        account.setDynamicDetail(DepositAccountDynamicDetail.createNew(account, allowWithdrawal, false));
        ReflectionTestUtils.setField(account, "id", accountId);
        return account;
    }

    private DynamicDepositAccount dynamicDepositAccount(Long savingsId) {
        DynamicDepositAccount account = createInstance(DynamicDepositAccount.class);
        ReflectionTestUtils.setField(account, "id", savingsId);
        return account;
    }

    private static <T> T createInstance(Class<T> clazz) {
        try {
            Constructor<T> constructor = clazz.getDeclaredConstructor();
            constructor.setAccessible(true);
            return constructor.newInstance();
        } catch (Exception e) {
            throw new RuntimeException("Failed to create instance of " + clazz.getName(), e);
        }
    }
}
