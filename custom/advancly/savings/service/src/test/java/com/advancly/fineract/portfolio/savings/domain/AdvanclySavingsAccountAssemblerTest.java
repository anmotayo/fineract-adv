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
package com.advancly.fineract.portfolio.savings.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import com.advancly.fineract.portfolio.savings.testutil.MoneyHelperInitializer;
import com.advancly.fineract.portfolio.savings.testutil.SavingsAccountTestBuilder;
import com.advancly.fineract.portfolio.savings.testutil.SavingsAccountTransactionTestBuilder;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.apache.fineract.infrastructure.configuration.domain.ConfigurationDomainService;
import org.apache.fineract.portfolio.savings.domain.SavingsAccount;
import org.apache.fineract.portfolio.savings.domain.SavingsAccountRepositoryWrapper;
import org.apache.fineract.portfolio.savings.domain.SavingsAccountTransaction;
import org.apache.fineract.portfolio.savings.domain.SavingsAccountTransactionSummaryWrapper;
import org.apache.fineract.portfolio.savings.domain.SavingsHelper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;

@ExtendWith(MockitoExtension.class)
class AdvanclySavingsAccountAssemblerTest {

    @Mock
    private SavingsAccountRepositoryWrapper savingsAccountRepository;
    @Mock
    private AdvanclySavingsAccountTransactionRepository advanclyTransactionRepository;
    @Mock
    private ConfigurationDomainService configurationDomainService;
    @Mock
    private SavingsAccountTransactionSummaryWrapper summaryWrapper;
    @Mock
    private SavingsHelper savingsHelper;

    private AdvanclySavingsAccountAssembler assembler;

    @BeforeEach
    void setUp() {
        MoneyHelperInitializer.initialize();
        assembler = new AdvanclySavingsAccountAssembler(savingsAccountRepository, advanclyTransactionRepository, configurationDomainService,
                summaryWrapper, savingsHelper);
    }

    @Test
    void testAssembleForAppendPath_loadsNoTransactions_setsLastRunningBalance() {
        SavingsAccount account = new SavingsAccountTestBuilder().withId(1L).build();
        when(savingsAccountRepository.findSavingsWithNotFoundDetection(1L, false)).thenReturn(account);

        SavingsAccountTransaction lastTxn = new SavingsAccountTransactionTestBuilder().withRunningBalance(BigDecimal.valueOf(5000)).build();
        when(advanclyTransactionRepository.findLastNonReversedTransaction(eq(1L), any(Pageable.class))).thenReturn(List.of(lastTxn));
        when(advanclyTransactionRepository.findNonReversedInterestAndOverdraftTransactions(1L)).thenReturn(new ArrayList<>());

        AssembledSavingsAccount result = assembler.assembleForAppendPath(1L);

        assertThat(result.getAccount()).isSameAs(account);
        assertThat(result.getAccount().getSummary().getRunningBalanceOnPivotDate()).isEqualByComparingTo(BigDecimal.valueOf(5000));
        assertThat(result.getInterestAndOverdraftTransactions()).isEmpty();
    }

    @Test
    void testAssembleForAppendPath_noExistingTransactions_zeroBalance() {
        SavingsAccount account = new SavingsAccountTestBuilder().withId(1L).build();
        when(savingsAccountRepository.findSavingsWithNotFoundDetection(1L, false)).thenReturn(account);

        when(advanclyTransactionRepository.findLastNonReversedTransaction(eq(1L), any(Pageable.class))).thenReturn(new ArrayList<>());
        when(advanclyTransactionRepository.findNonReversedInterestAndOverdraftTransactions(1L)).thenReturn(new ArrayList<>());

        AssembledSavingsAccount result = assembler.assembleForAppendPath(1L);

        assertThat(result.getAccount().getSummary().getRunningBalanceOnPivotDate()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void testAssembleForInsertPath_loadsFromTransactionDate() {
        LocalDate txnDate = LocalDate.of(2025, 7, 15);
        SavingsAccount account = new SavingsAccountTestBuilder().withId(1L).build();
        when(savingsAccountRepository.findSavingsWithNotFoundDetection(1L, false)).thenReturn(account);

        List<SavingsAccountTransaction> txns = List
                .of(new SavingsAccountTransactionTestBuilder().withDate(txnDate).withAmount(BigDecimal.valueOf(100)).build());
        when(advanclyTransactionRepository.findTransactionsOnOrAfterDate(account, txnDate)).thenReturn(txns);

        SavingsAccountTransaction beforeTxn = new SavingsAccountTransactionTestBuilder().withRunningBalance(BigDecimal.valueOf(3000))
                .build();
        when(advanclyTransactionRepository.findNonInterestTransactionBeforeDate(eq(1L), eq(txnDate), any(Pageable.class)))
                .thenReturn(List.of(beforeTxn));
        when(advanclyTransactionRepository.findNonReversedInterestAndOverdraftTransactions(1L)).thenReturn(new ArrayList<>());

        AssembledSavingsAccount result = assembler.assembleForInsertPath(1L, txnDate, false);

        assertThat(result.getAccount().getSummary().getRunningBalanceOnPivotDate()).isEqualByComparingTo(BigDecimal.valueOf(3000));
    }

    @Test
    void testAssembleForInsertPath_withInterestRate_usesNonAccrualQuery() {
        LocalDate txnDate = LocalDate.of(2025, 7, 15);
        SavingsAccount account = new SavingsAccountTestBuilder().withId(1L).build();
        when(savingsAccountRepository.findSavingsWithNotFoundDetection(1L, false)).thenReturn(account);

        when(advanclyTransactionRepository.findTransactionsOnOrAfterDate(account, txnDate)).thenReturn(new ArrayList<>());

        SavingsAccountTransaction beforeTxn = new SavingsAccountTransactionTestBuilder().withRunningBalance(BigDecimal.valueOf(2000))
                .build();
        when(advanclyTransactionRepository.findNonAccrualTransactionBeforeDate(eq(1L), eq(txnDate), any(Pageable.class)))
                .thenReturn(List.of(beforeTxn));
        when(advanclyTransactionRepository.findNonReversedInterestAndOverdraftTransactions(1L)).thenReturn(new ArrayList<>());

        AssembledSavingsAccount result = assembler.assembleForInsertPath(1L, txnDate, true);

        assertThat(result.getAccount().getSummary().getRunningBalanceOnPivotDate()).isEqualByComparingTo(BigDecimal.valueOf(2000));
    }
}
