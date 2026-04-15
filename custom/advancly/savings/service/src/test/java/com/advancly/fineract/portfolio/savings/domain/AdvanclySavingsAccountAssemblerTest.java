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
import java.util.ArrayList;
import java.util.List;
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
    private SavingsAccountTransactionSummaryWrapper summaryWrapper;
    @Mock
    private SavingsHelper savingsHelper;

    private AdvanclySavingsAccountAssembler assembler;

    @BeforeEach
    void setUp() {
        MoneyHelperInitializer.initialize();
        assembler = new AdvanclySavingsAccountAssembler(savingsAccountRepository, advanclyTransactionRepository, summaryWrapper,
                savingsHelper);
    }

    @Test
    void testAssembleForAppendPath_loadsNoTransactions_setsLastRunningBalance() {
        SavingsAccount account = new SavingsAccountTestBuilder().withId(1L).build();
        when(savingsAccountRepository.findSavingsWithNotFoundDetection(1L, true)).thenReturn(account);

        SavingsAccountTransaction lastTxn = new SavingsAccountTransactionTestBuilder().withRunningBalance(BigDecimal.valueOf(5000)).build();
        when(advanclyTransactionRepository.findLastNonReversedTransaction(eq(1L), any(Pageable.class))).thenReturn(List.of(lastTxn));

        AssembledSavingsAccount result = assembler.assembleForAppendPath(1L);

        assertThat(result.getAccount()).isSameAs(account);
        assertThat(result.getAccount().getSummary().getRunningBalanceOnPivotDate()).isEqualByComparingTo(BigDecimal.valueOf(5000));
        assertThat(result.getLastNonReversedTransaction()).isSameAs(lastTxn);
    }

    @Test
    void testAssembleForAppendPath_noExistingTransactions_zeroBalance() {
        SavingsAccount account = new SavingsAccountTestBuilder().withId(1L).build();
        when(savingsAccountRepository.findSavingsWithNotFoundDetection(1L, true)).thenReturn(account);

        when(advanclyTransactionRepository.findLastNonReversedTransaction(eq(1L), any(Pageable.class))).thenReturn(new ArrayList<>());

        AssembledSavingsAccount result = assembler.assembleForAppendPath(1L);

        assertThat(result.getAccount().getSummary().getRunningBalanceOnPivotDate()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(result.getLastNonReversedTransaction()).isNull();
    }
}
