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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import com.advancly.fineract.portfolio.savings.testutil.MoneyHelperInitializer;
import com.advancly.fineract.portfolio.savings.testutil.SavingsAccountTestBuilder;
import com.advancly.fineract.portfolio.savings.testutil.SavingsAccountTransactionTestBuilder;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.apache.fineract.portfolio.savings.SavingsAccountTransactionType;
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
    void testAssembleForAppendPath_lastRowIsBalanceBearing_singleRowFromUnionServesBothRoles() {
        SavingsAccount account = new SavingsAccountTestBuilder().withId(1L).build();
        when(savingsAccountRepository.findSavingsWithNotFoundDetection(1L, true)).thenReturn(account);

        // Mirrors the common case the repository's plain UNION dedupes to 1 row: the latest transaction is itself
        // balance-bearing, so it serves as both the running-balance seed and the window-closing target.
        SavingsAccountTransaction lastTxn = new SavingsAccountTransactionTestBuilder().withId(1L)
                .withRunningBalance(BigDecimal.valueOf(5000)).build();
        when(advanclyTransactionRepository.findLastNonReversedAndBalanceBearingTransactions(eq(1L))).thenReturn(List.of(lastTxn));

        AssembledSavingsAccount result = assembler.assembleForAppendPath(1L);

        assertThat(result.getAccount()).isSameAs(account);
        assertThat(result.getAccount().getSummary().getRunningBalanceOnPivotDate()).isEqualByComparingTo(BigDecimal.valueOf(5000));
        assertThat(result.getLastBalanceBearingTransaction()).isSameAs(lastTxn);
    }

    @Test
    void testAssembleForAppendPath_noExistingTransactions_zeroBalance() {
        SavingsAccount account = new SavingsAccountTestBuilder().withId(1L).build();
        when(savingsAccountRepository.findSavingsWithNotFoundDetection(1L, true)).thenReturn(account);

        when(advanclyTransactionRepository.findLastNonReversedAndBalanceBearingTransactions(eq(1L))).thenReturn(new ArrayList<>());

        AssembledSavingsAccount result = assembler.assembleForAppendPath(1L);

        assertThat(result.getAccount().getSummary().getRunningBalanceOnPivotDate()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(result.getLastBalanceBearingTransaction()).isNull();
    }

    @Test
    void testAssembleForAppendPath_lastRowIsPosting_seedsBalanceFromPostingButClosesEarlierBalanceBearingRow() {
        SavingsAccount account = new SavingsAccountTestBuilder().withId(1L).build();
        when(savingsAccountRepository.findSavingsWithNotFoundDetection(1L, true)).thenReturn(account);

        // Mirrors the divergent case the repository's UNION returns 2 distinct rows for: the latest transaction
        // (the posting) is NOT balance-bearing, so an earlier deposit is the window-closing target instead.
        SavingsAccountTransaction depositTxn = new SavingsAccountTransactionTestBuilder().withId(1L).withDate(LocalDate.of(2026, 1, 5))
                .withRunningBalance(BigDecimal.valueOf(5000)).build();
        SavingsAccountTransaction postingTxn = new SavingsAccountTransactionTestBuilder().withId(2L).withDate(LocalDate.of(2026, 1, 10))
                .withType(SavingsAccountTransactionType.INTEREST_POSTING).withRunningBalance(BigDecimal.valueOf(5100)).build();
        when(advanclyTransactionRepository.findLastNonReversedAndBalanceBearingTransactions(eq(1L)))
                .thenReturn(List.of(depositTxn, postingTxn));

        AssembledSavingsAccount result = assembler.assembleForAppendPath(1L);

        assertThat(result.getAccount().getSummary().getRunningBalanceOnPivotDate()).isEqualByComparingTo(BigDecimal.valueOf(5100));
        assertThat(result.getLastBalanceBearingTransaction()).isSameAs(depositTxn);
    }
}
