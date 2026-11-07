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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.advancly.fineract.portfolio.savings.testutil.MoneyHelperInitializer;
import com.advancly.fineract.portfolio.savings.testutil.SavingsAccountSummaryTestBuilder;
import com.advancly.fineract.portfolio.savings.testutil.SavingsAccountTestBuilder;
import com.advancly.fineract.portfolio.savings.testutil.SavingsAccountTransactionTestBuilder;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.apache.fineract.portfolio.savings.SavingsAccountTransactionType;
import org.apache.fineract.portfolio.savings.domain.SavingsAccount;
import org.apache.fineract.portfolio.savings.domain.SavingsAccountTransaction;
import org.apache.fineract.portfolio.savings.domain.SavingsAccountTransactionSummaryWrapper;
import org.apache.fineract.portfolio.savings.domain.SavingsHelper;
import org.apache.fineract.portfolio.savings.exception.SavingsAccountNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AdvanclySavingsAccountAssemblerTest {

    @Mock
    private AdvanclySavingsAccountLockRepository savingsAccountLockRepository;
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
        assembler = new AdvanclySavingsAccountAssembler(savingsAccountLockRepository, advanclyTransactionRepository, summaryWrapper,
                savingsHelper);
    }

    @Test
    void testAssembleForAppendPath_lastRowIsBalanceBearing_singleRowServesBothRoles() {
        SavingsAccount account = accountWithDeposits();
        when(savingsAccountLockRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(account));

        // Latest transaction is balance-bearing, so one row serves as both seed and window-closing target.
        SavingsAccountTransaction lastTxn = new SavingsAccountTransactionTestBuilder().withId(1L)
                .withRunningBalance(BigDecimal.valueOf(5000)).build();
        when(advanclyTransactionRepository.findLastNonReversedAndBalanceBearingTransactions(eq(1L))).thenReturn(List.of(lastTxn));

        AssembledSavingsAccount result = assembler.assembleForAppendPath(1L);

        assertThat(result.getAccount()).isSameAs(account);
        assertThat(result.getAccount().getSummary().getRunningBalanceOnPivotDate()).isEqualByComparingTo(BigDecimal.valueOf(5000));
        assertThat(result.getLastBalanceBearingTransaction()).isSameAs(lastTxn);
    }

    @Test
    void testAssembleForAppendPath_noExistingTransactions_skipsTheLockingLookups() {
        SavingsAccount account = emptyAccount();
        when(savingsAccountLockRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(account));
        when(advanclyTransactionRepository.existsBySavingsAccountId(1L)).thenReturn(false);

        AssembledSavingsAccount result = assembler.assembleForAppendPath(1L);

        // A locking read on an empty account takes a gap lock that deadlocks with neighbouring accounts' inserts.
        verify(advanclyTransactionRepository, never()).findLastNonReversedAndBalanceBearingTransactions(1L);
        assertThat(result.getAccount().getSummary().getRunningBalanceOnPivotDate()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(result.getLastBalanceBearingTransaction()).isNull();
        assertThat(result.getLastTransactionDate()).isNull();
        assertThat(result.isBackdated(LocalDate.of(2000, 1, 1))).isFalse();
    }

    @Test
    void testAssembleForAppendPath_zeroTotalsButRowsExist_usesTheLockingLookups() {
        // e.g. a hold then a release: rows exist but every summary total is back to zero.
        SavingsAccount account = emptyAccount();
        when(savingsAccountLockRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(account));
        when(advanclyTransactionRepository.existsBySavingsAccountId(1L)).thenReturn(true);
        SavingsAccountTransaction lastTxn = new SavingsAccountTransactionTestBuilder().withId(1L).withRunningBalance(BigDecimal.ZERO)
                .build();
        when(advanclyTransactionRepository.findLastNonReversedAndBalanceBearingTransactions(1L)).thenReturn(List.of(lastTxn));

        AssembledSavingsAccount result = assembler.assembleForAppendPath(1L);

        assertThat(result.getLastBalanceBearingTransaction()).isSameAs(lastTxn);
    }

    @Test
    void testAssembleForAppendPath_lockedRowShowsActivity_skipsTheExistenceCheckEvenIfTheSnapshotLooksEmpty() {
        // A first deposit committed after our snapshot is visible in the locked account row's totals.
        SavingsAccount account = accountWithDeposits();
        when(savingsAccountLockRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(account));
        when(advanclyTransactionRepository.findLastNonReversedAndBalanceBearingTransactions(1L)).thenReturn(new ArrayList<>());

        assembler.assembleForAppendPath(1L);

        verify(advanclyTransactionRepository, never()).existsBySavingsAccountId(1L);
        verify(advanclyTransactionRepository).findLastNonReversedAndBalanceBearingTransactions(1L);
    }

    @Test
    void testAssembleForAppendPath_lastRowIsPosting_seedsBalanceFromPostingButClosesEarlierBalanceBearingRow() {
        SavingsAccount account = accountWithDeposits();
        when(savingsAccountLockRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(account));

        // Latest transaction (a posting) is not balance-bearing, so an earlier deposit closes the window.
        SavingsAccountTransaction depositTxn = new SavingsAccountTransactionTestBuilder().withId(1L).withDate(LocalDate.of(2026, 1, 5))
                .withRunningBalance(BigDecimal.valueOf(5000)).build();
        SavingsAccountTransaction postingTxn = new SavingsAccountTransactionTestBuilder().withId(2L).withDate(LocalDate.of(2026, 1, 10))
                .withType(SavingsAccountTransactionType.INTEREST_POSTING).withRunningBalance(BigDecimal.valueOf(5100)).build();
        when(advanclyTransactionRepository.findLastNonReversedAndBalanceBearingTransactions(eq(1L)))
                .thenReturn(List.of(depositTxn, postingTxn));

        AssembledSavingsAccount result = assembler.assembleForAppendPath(1L);

        assertThat(result.getAccount().getSummary().getRunningBalanceOnPivotDate()).isEqualByComparingTo(BigDecimal.valueOf(5100));
        assertThat(result.getLastBalanceBearingTransaction()).isSameAs(depositTxn);
        // The backdated re-check uses the latest row (the posting), not the window-closing row.
        assertThat(result.getLastTransactionDate()).isEqualTo(LocalDate.of(2026, 1, 10));
        assertThat(result.isBackdated(LocalDate.of(2026, 1, 9))).isTrue();
        assertThat(result.isBackdated(LocalDate.of(2026, 1, 10))).isFalse();
    }

    @Test
    void testAssembleForAppendPath_loadsTheAccountThroughTheLockedNativeReadOnly() {
        SavingsAccount account = accountWithDeposits();
        when(savingsAccountLockRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(account));
        when(advanclyTransactionRepository.findLastNonReversedAndBalanceBearingTransactions(eq(1L))).thenReturn(new ArrayList<>());

        AssembledSavingsAccount result = assembler.assembleForAppendPath(1L);

        // Core's PESSIMISTIC_WRITE query hydrates the full history; the append path must use the native FOR UPDATE.
        assertThat(result.getAccount()).isSameAs(account);
        verify(savingsAccountLockRepository).findByIdForUpdate(1L);
    }

    @Test
    void testAssembleForAppendPath_unknownAccount_throwsNotFoundBeforeReadingAnyTransaction() {
        when(savingsAccountLockRepository.findByIdForUpdate(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> assembler.assembleForAppendPath(99L)).isInstanceOf(SavingsAccountNotFoundException.class);
        verify(advanclyTransactionRepository, never()).findLastNonReversedAndBalanceBearingTransactions(99L);
    }

    private static SavingsAccount emptyAccount() {
        return new SavingsAccountTestBuilder().withId(1L).withSummary(new SavingsAccountSummaryTestBuilder().build()).build();
    }

    private static SavingsAccount accountWithDeposits() {
        return new SavingsAccountTestBuilder().withId(1L)
                .withSummary(new SavingsAccountSummaryTestBuilder().withTotalDeposits(BigDecimal.valueOf(5000)).build()).build();
    }
}
