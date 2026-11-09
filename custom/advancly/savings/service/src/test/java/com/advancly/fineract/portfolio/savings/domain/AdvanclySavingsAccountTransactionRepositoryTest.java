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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

import java.util.Optional;
import org.apache.fineract.portfolio.savings.domain.SavingsAccountTransaction;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Answers;

class AdvanclySavingsAccountTransactionRepositoryTest {

    private AdvanclySavingsAccountTransactionRepository repository;

    @BeforeEach
    void setUp() {
        // Real default method, mocked queries.
        repository = mock(AdvanclySavingsAccountTransactionRepository.class, withSettings().defaultAnswer(Answers.CALLS_REAL_METHODS));
    }

    @Test
    void noTransactions_returnsEmptyWithoutTheSecondLookup() {
        when(repository.findLastNonReversedTransactionForUpdate(1L)).thenReturn(Optional.empty());

        assertThat(repository.findLastNonReversedAndBalanceBearingTransactions(1L)).isEmpty();
        verify(repository, never()).findLastBalanceBearingTransactionForUpdate(1L);
    }

    @Test
    void latestIsBalanceBearing_returnsItAloneWithoutTheSecondLookup() {
        SavingsAccountTransaction latest = txn(true);
        when(repository.findLastNonReversedTransactionForUpdate(1L)).thenReturn(Optional.of(latest));

        assertThat(repository.findLastNonReversedAndBalanceBearingTransactions(1L)).containsExactly(latest);
        verify(repository, never()).findLastBalanceBearingTransactionForUpdate(1L);
    }

    @Test
    void latestIsAPosting_returnsItAndTheLastBalanceBearingRow() {
        SavingsAccountTransaction posting = txn(false);
        SavingsAccountTransaction bearing = txn(true);
        when(repository.findLastNonReversedTransactionForUpdate(1L)).thenReturn(Optional.of(posting));
        when(repository.findLastBalanceBearingTransactionForUpdate(1L)).thenReturn(Optional.of(bearing));

        assertThat(repository.findLastNonReversedAndBalanceBearingTransactions(1L)).containsExactly(posting, bearing);
    }

    @Test
    void onlyPostings_returnsTheLatestPosting() {
        SavingsAccountTransaction posting = txn(false);
        when(repository.findLastNonReversedTransactionForUpdate(1L)).thenReturn(Optional.of(posting));
        when(repository.findLastBalanceBearingTransactionForUpdate(1L)).thenReturn(Optional.empty());

        assertThat(repository.findLastNonReversedAndBalanceBearingTransactions(1L)).containsExactly(posting);
    }

    private static SavingsAccountTransaction txn(final boolean balanceBearing) {
        SavingsAccountTransaction transaction = mock(SavingsAccountTransaction.class);
        when(transaction.isBalanceBearing()).thenReturn(balanceBearing);
        return transaction;
    }
}
