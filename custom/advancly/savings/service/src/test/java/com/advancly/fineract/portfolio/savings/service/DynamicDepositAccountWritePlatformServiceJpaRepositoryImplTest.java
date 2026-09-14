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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.advancly.fineract.portfolio.savings.domain.DynamicDepositAccount;
import com.advancly.fineract.portfolio.savings.domain.DynamicDepositAccountAssembler;
import com.advancly.fineract.portfolio.savings.domain.DynamicDepositAccountRepository;
import com.advancly.fineract.portfolio.savings.validator.DynamicDepositAccountDataValidator;
import java.lang.reflect.Constructor;
import org.apache.fineract.infrastructure.accountnumberformat.domain.AccountNumberFormatRepositoryWrapper;
import org.apache.fineract.infrastructure.core.api.JsonCommand;
import org.apache.fineract.infrastructure.core.data.CommandProcessingResult;
import org.apache.fineract.infrastructure.security.service.PlatformSecurityContext;
import org.apache.fineract.portfolio.account.domain.AccountAssociationType;
import org.apache.fineract.portfolio.account.domain.AccountAssociations;
import org.apache.fineract.portfolio.account.domain.AccountAssociationsRepository;
import org.apache.fineract.portfolio.account.service.AccountNumberGenerator;
import org.apache.fineract.portfolio.note.domain.NoteRepository;
import org.apache.fineract.portfolio.savings.DepositAccountType;
import org.apache.fineract.portfolio.savings.DepositsApiConstants;
import org.apache.fineract.portfolio.savings.data.DepositAccountDataValidator;
import org.apache.fineract.portfolio.savings.data.SavingsAccountTransactionDataValidator;
import org.apache.fineract.portfolio.savings.domain.DepositAccountAssembler;
import org.apache.fineract.portfolio.savings.domain.SavingsAccount;
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

    private DynamicDepositAccountWritePlatformServiceJpaRepositoryImpl service;

    @BeforeEach
    void setUp() {
        service = new DynamicDepositAccountWritePlatformServiceJpaRepositoryImpl(context, dynamicDepositAccountRepository,
                dynamicDepositAccountDataValidator, dynamicDepositAccountAssembler, accountNumberGenerator, accountNumberFormatRepository,
                noteRepository, savingsAccountApplicationTransitionApiJsonValidator, savingsAccountTransactionDataValidator,
                savingsAccountWritePlatformService, depositAccountAssembler, depositAccountDataValidator, accountAssociationsRepository);
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
