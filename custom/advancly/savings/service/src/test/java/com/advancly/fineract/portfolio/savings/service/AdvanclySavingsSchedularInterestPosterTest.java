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
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.advancly.fineract.portfolio.savings.testutil.MoneyHelperInitializer;
import java.math.BigDecimal;
import java.util.Collections;
import java.util.List;
import org.apache.fineract.infrastructure.jobs.exception.JobExecutionException;
import org.apache.fineract.infrastructure.security.service.PlatformSecurityContext;
import org.apache.fineract.organisation.monetary.data.CurrencyData;
import org.apache.fineract.portfolio.savings.DepositAccountType;
import org.apache.fineract.portfolio.savings.data.SavingsAccountData;
import org.apache.fineract.portfolio.savings.data.SavingsAccountSummaryData;
import org.apache.fineract.portfolio.savings.data.SavingsAccountTransactionData;
import org.apache.fineract.portfolio.savings.data.SavingsAccountTransactionEnumData;
import org.apache.fineract.portfolio.savings.service.SavingsAccountReadPlatformService;
import org.apache.fineract.portfolio.savings.service.SavingsAccountWritePlatformService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;

class AdvanclySavingsSchedularInterestPosterTest {

    @BeforeEach
    void setUp() {
        MoneyHelperInitializer.initialize();
    }

    @Test
    void doesNothingWhenThereAreNoSavingsAccounts() throws Exception {
        final SavingsAccountWritePlatformService writePlatformService = mock(SavingsAccountWritePlatformService.class);
        final JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        final SavingsAccountReadPlatformService readPlatformService = mock(SavingsAccountReadPlatformService.class);
        final PlatformSecurityContext securityContext = mock(PlatformSecurityContext.class);
        final DynamicDepositScheduledRateHistoryReadPlatformService dynamicRateHistoryReadPlatformService = mock(
                DynamicDepositScheduledRateHistoryReadPlatformService.class);

        final AdvanclySavingsSchedularInterestPoster poster = new AdvanclySavingsSchedularInterestPoster(writePlatformService, jdbcTemplate,
                readPlatformService, securityContext, dynamicRateHistoryReadPlatformService);
        poster.setSavingAccounts(Collections.<SavingsAccountData>emptyList());
        poster.setBackdatedTxnsAllowedTill(false);

        poster.postInterest();

        verifyNoInteractions(writePlatformService, jdbcTemplate, securityContext, dynamicRateHistoryReadPlatformService);
    }

    private static final String SAVINGS_UPDATE = "update m_savings_account set";
    private static final String TRANSACTION_INSERT = "INSERT INTO m_savings_account_transaction";

    @Test
    void theAccountVersionReadWithTheAccountGuardsItsUpdate() throws Exception {
        final Fixture fixture = new Fixture(new int[] { 1 }, account(5L, 7));

        fixture.poster.postInterest();

        final ArgumentCaptor<List<Object[]>> params = ArgumentCaptor.forClass(List.class);
        verify(fixture.jdbcTemplate).batchUpdate(
                argThat((String sql) -> sql.startsWith(SAVINGS_UPDATE) && sql.endsWith("version = version + 1 WHERE id=? AND version=?")),
                params.capture());
        final Object[] row = params.getValue().get(0);
        assertThat(row[row.length - 2]).isEqualTo(5L);
        assertThat(row[row.length - 1]).isEqualTo(7);
        verify(fixture.jdbcTemplate).batchUpdate(startsWith(TRANSACTION_INSERT), anyList());
    }

    @Test
    void anAccountChangedByAConcurrentWriterIsSkippedAndTheOthersStillPost() throws Exception {
        final Fixture fixture = new Fixture(new int[] { 1, 0 }, account(1L, 3), account(2L, 4));

        fixture.poster.postInterest();

        final ArgumentCaptor<List<Object[]>> inserted = ArgumentCaptor.forClass(List.class);
        verify(fixture.jdbcTemplate).batchUpdate(startsWith(TRANSACTION_INSERT), inserted.capture());
        assertThat(inserted.getValue()).hasSize(1);
        assertThat(inserted.getValue().get(0)[0]).isEqualTo(1L);
    }

    @Test
    void whenEveryAccountWasChangedNothingIsWrittenAndTheJobDoesNotFail() throws Exception {
        final Fixture fixture = new Fixture(new int[] { 0, 0 }, account(1L, 3), account(2L, 4));

        fixture.poster.postInterest();

        verify(fixture.jdbcTemplate).batchUpdate(startsWith(SAVINGS_UPDATE), anyList());
        verify(fixture.jdbcTemplate, never()).batchUpdate(startsWith(TRANSACTION_INSERT), anyList());
    }

    @Test
    void aTransactionMissingFromTheFetchedRowsFailsTheBatchInsteadOfSkippingItsJournalEntries() {
        final Fixture fixture = new Fixture(new int[] { 1 }, account(1L, 3)).withoutInsertedRowsFetched();

        assertThatThrownBy(fixture.poster::postInterest).isInstanceOf(JobExecutionException.class);
    }

    private static SavingsAccountData account(final Long id, final Integer version) {
        final SavingsAccountData account = mock(SavingsAccountData.class);
        when(account.getId()).thenReturn(id);
        when(account.getVersion()).thenReturn(version);
        when(account.getDepositTypeId()).thenReturn(DepositAccountType.SAVINGS_DEPOSIT.getValue());
        when(account.getCurrency()).thenReturn(new CurrencyData("USD", 2, 1));
        when(account.getSummary()).thenReturn(mock(SavingsAccountSummaryData.class));
        final SavingsAccountTransactionData interest = mock(SavingsAccountTransactionData.class);
        when(interest.getId()).thenReturn(null);
        when(interest.getRefNo()).thenReturn("ref-" + id);
        when(interest.getAmount()).thenReturn(BigDecimal.TEN);
        when(interest.getTransactionType()).thenReturn(mock(SavingsAccountTransactionEnumData.class));
        when(account.getSavingsAccountTransactionData()).thenReturn(List.of(interest));
        return account;
    }

    private static final class Fixture {

        private final JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        private final SavingsAccountReadPlatformService readPlatformService = mock(SavingsAccountReadPlatformService.class);
        private final AdvanclySavingsSchedularInterestPoster poster;

        private Fixture(final int[] accountUpdateCounts, final SavingsAccountData... accounts) {
            final SavingsAccountWritePlatformService writePlatformService = mock(SavingsAccountWritePlatformService.class);
            when(writePlatformService.postInterest(any(SavingsAccountData.class), anyBoolean(), any(), anyBoolean()))
                    .thenAnswer(invocation -> invocation.getArgument(0));
            when(jdbcTemplate.batchUpdate(anyString(), anyList())).thenReturn(new int[0]);
            when(jdbcTemplate.batchUpdate(startsWith(SAVINGS_UPDATE), anyList())).thenReturn(accountUpdateCounts);
            final PlatformSecurityContext securityContext = mock(PlatformSecurityContext.class);
            when(securityContext.authenticatedUser()).thenReturn(mock(org.apache.fineract.useradministration.domain.AppUser.class));
            poster = new AdvanclySavingsSchedularInterestPoster(writePlatformService, jdbcTemplate, readPlatformService, securityContext,
                    mock(DynamicDepositScheduledRateHistoryReadPlatformService.class));
            poster.setSavingAccounts(List.of(accounts));
            poster.setBackdatedTxnsAllowedTill(false);
            final List<SavingsAccountTransactionData> insertedRows = new java.util.ArrayList<>();
            for (final SavingsAccountData account : accounts) {
                final String refNo = account.getSavingsAccountTransactionData().get(0).getRefNo();
                final Long rowId = 1000L + account.getId();
                final SavingsAccountTransactionData row = mock(SavingsAccountTransactionData.class);
                when(row.getRefNo()).thenReturn(refNo);
                when(row.getId()).thenReturn(rowId);
                insertedRows.add(row);
            }
            when(readPlatformService.retrieveAllTransactionData(anyList())).thenReturn(insertedRows);
        }

        private Fixture withoutInsertedRowsFetched() {
            when(readPlatformService.retrieveAllTransactionData(anyList())).thenReturn(List.of());
            return this;
        }
    }
}
