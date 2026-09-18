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
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.advancly.fineract.portfolio.savings.domain.SavingsAccountInterestCharge;
import com.advancly.fineract.portfolio.savings.domain.SavingsAccountInterestChargeRepository;
import com.advancly.fineract.portfolio.savings.testutil.MoneyHelperInitializer;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.apache.fineract.infrastructure.security.service.PlatformSecurityContext;
import org.apache.fineract.organisation.monetary.data.CurrencyData;
import org.apache.fineract.portfolio.savings.SavingsAccountTransactionType;
import org.apache.fineract.portfolio.savings.data.SavingsAccountData;
import org.apache.fineract.portfolio.savings.data.SavingsAccountSummaryData;
import org.apache.fineract.portfolio.savings.data.SavingsAccountTransactionData;
import org.apache.fineract.portfolio.savings.data.SavingsAccountTransactionEnumData;
import org.apache.fineract.portfolio.savings.domain.SavingsAccountTransaction;
import org.apache.fineract.portfolio.savings.domain.SavingsAccountTransactionRepository;
import org.apache.fineract.portfolio.savings.service.SavingsAccountReadPlatformService;
import org.apache.fineract.portfolio.savings.service.SavingsAccountWritePlatformService;
import org.apache.fineract.portfolio.savings.service.SavingsEnumerations;
import org.apache.fineract.useradministration.domain.AppUser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Covers {@link AdvanclySavingsSchedularInterestPoster#postInterest()}'s new per-period-charge application logic (Task
 * 8).
 *
 * <p>
 * {@code SavingsAccountData} is mocked directly (plain {@code mock(...)}, not {@code RETURNS_DEEP_STUBS}) with only the
 * getters this code path actually calls stubbed - the established pattern in this module (see
 * {@code AdvanclySavingsAccountWritePlatformServiceSelectAccountIdTest}), since the class has only private
 * constructors. An earlier draft of this test used {@code RETURNS_DEEP_STUBS} with an empty transaction list; that
 * silently made {@code findLatestInterestPostingTransaction} return null (no interest-posting transaction to find), so
 * {@code applyPendingChargeIfAny} bailed out before ever reaching the charge-creation logic this test exists to cover,
 * and the {@code applyAtPosting}/{@code saveAll} assertions would have passed for the wrong reason (or, once the real
 * interest-posting transaction was added to make them true positives, exposed a real bug - see the note in
 * {@code AdvanclySavingsSchedularInterestPoster#applyPendingChargeIfAny} about the {@code create(...)} overload that
 * must be used so {@code transactionDate} is populated). This version builds a real interest-posting
 * {@code SavingsAccountTransactionData} fixture so the happy path is genuinely exercised end to end.
 */
class AdvanclySavingsSchedularInterestPosterChargeApplicationTest {

    private static final Long ACCOUNT_ID = 42L;
    private static final CurrencyData CURRENCY = new CurrencyData("USD", "US Dollar", 2, null, "$", "USD");
    private static final LocalDate POSTING_DATE = LocalDate.of(2026, 1, 31);

    @BeforeEach
    void setUp() {
        MoneyHelperInitializer.initialize();
    }

    @Test
    void appliesAPendingChargeAgainstTheNewlyPostedInterest() throws Exception {
        final SavingsAccountWritePlatformService writePlatformService = mock(SavingsAccountWritePlatformService.class);
        final JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        final SavingsAccountReadPlatformService readPlatformService = mock(SavingsAccountReadPlatformService.class);
        final PlatformSecurityContext securityContext = mock(PlatformSecurityContext.class);
        final SavingsAccountInterestChargeRepository interestChargeRepository = mock(SavingsAccountInterestChargeRepository.class);
        final SavingsAccountTransactionRepository savingsAccountTransactionRepository = mock(SavingsAccountTransactionRepository.class);

        stubAuthenticatedUser(securityContext);

        // Set up: account 42, plain Savings, posted 100 of interest this run, has one pending charge row at 25%.
        final SavingsAccountTransactionData interestPostingTransaction = buildInterestPostingTransaction(new BigDecimal("100.00"),
                new BigDecimal("1100.00"));
        final List<SavingsAccountTransactionData> transactions = new ArrayList<>();
        transactions.add(interestPostingTransaction);

        final SavingsAccountSummaryData summary = mock(SavingsAccountSummaryData.class);
        final SavingsAccountData accountData = mock(SavingsAccountData.class);
        when(accountData.getId()).thenReturn(ACCOUNT_ID);
        when(accountData.getAccountNo()).thenReturn("000042");
        when(accountData.getCurrency()).thenReturn(CURRENCY);
        when(accountData.getSavingsAccountTransactionData()).thenReturn(transactions);
        when(accountData.getSummary()).thenReturn(summary);
        when(writePlatformService.postInterest(accountData, false, null, false)).thenReturn(accountData);

        final SavingsAccountInterestCharge pendingRow = mock(SavingsAccountInterestCharge.class);
        when(pendingRow.chargePercentage()).thenReturn(new BigDecimal("25"));
        when(interestChargeRepository.findPendingByAccountIdUpTo(eq(ACCOUNT_ID), any(LocalDate.class))).thenReturn(List.of(pendingRow));
        when(interestChargeRepository.sumPendingChargeAmount(ACCOUNT_ID)).thenReturn(BigDecimal.ZERO);
        when(interestChargeRepository.sumPostedChargeAmount(ACCOUNT_ID)).thenReturn(new BigDecimal("25.00"));

        final SavingsAccountTransaction postingTransactionEntity = mock(SavingsAccountTransaction.class);
        final SavingsAccountTransaction chargeTransactionEntity = mock(SavingsAccountTransaction.class);
        when(savingsAccountTransactionRepository.getReferenceById(any())).thenReturn(postingTransactionEntity, chargeTransactionEntity);

        final AdvanclySavingsSchedularInterestPoster poster = new AdvanclySavingsSchedularInterestPoster(writePlatformService, jdbcTemplate,
                readPlatformService, securityContext, interestChargeRepository, savingsAccountTransactionRepository);
        poster.setSavingAccounts(List.of(accountData));
        poster.setBackdatedTxnsAllowedTill(false);

        poster.postInterest();

        // The charge (25% of 100 gross interest, uncapped by withholding tax since there is none) got applied and
        // linked to both the interest-posting and the new interest-based-charge transactions. BigDecimal.equals(...)
        // is scale-sensitive, so the basis/amount are captured and compared with isEqualByComparingTo rather than
        // matched with eq(...).
        final ArgumentCaptor<BigDecimal> basisCaptor = ArgumentCaptor.forClass(BigDecimal.class);
        final ArgumentCaptor<BigDecimal> amountCaptor = ArgumentCaptor.forClass(BigDecimal.class);
        verify(pendingRow).applyAtPosting(basisCaptor.capture(), amountCaptor.capture(), eq(postingTransactionEntity),
                eq(chargeTransactionEntity));
        assertThat(basisCaptor.getValue()).isEqualByComparingTo("100.00");
        assertThat(amountCaptor.getValue()).isEqualByComparingTo("25.00");
        verify(interestChargeRepository).saveAll(anyList());

        // A new INTEREST_BASED_CHARGE transaction was added to the account's transaction list, reducing the balance.
        assertThat(transactions).hasSize(2);
        final SavingsAccountTransactionData chargeTransaction = transactions.get(1);
        assertThat(chargeTransaction.isInterestBasedChargeAndNotReversed()).isTrue();
        assertThat(chargeTransaction.getAmount()).isEqualByComparingTo("25.00");
        assertThat(chargeTransaction.getRunningBalance()).isEqualByComparingTo("1075.00");

        // Final Self-Review Notes: the supplementary derived-column update on m_savings_account also ran.
        verify(jdbcTemplate).update(
                "update m_savings_account set interest_based_charge_derived = ?, interest_based_charge_posted_derived = ? where id = ?",
                BigDecimal.ZERO, new BigDecimal("25.00"), ACCOUNT_ID);
    }

    @Test
    void leavesAnAccountWithNoPendingChargeUnaffected() throws Exception {
        final SavingsAccountWritePlatformService writePlatformService = mock(SavingsAccountWritePlatformService.class);
        final JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        final SavingsAccountReadPlatformService readPlatformService = mock(SavingsAccountReadPlatformService.class);
        final PlatformSecurityContext securityContext = mock(PlatformSecurityContext.class);
        final SavingsAccountInterestChargeRepository interestChargeRepository = mock(SavingsAccountInterestChargeRepository.class);
        final SavingsAccountTransactionRepository savingsAccountTransactionRepository = mock(SavingsAccountTransactionRepository.class);

        stubAuthenticatedUser(securityContext);

        final SavingsAccountSummaryData summary = mock(SavingsAccountSummaryData.class);
        final SavingsAccountData accountData = mock(SavingsAccountData.class);
        when(accountData.getId()).thenReturn(7L);
        when(accountData.getCurrency()).thenReturn(CURRENCY);
        when(accountData.getSavingsAccountTransactionData()).thenReturn(new ArrayList<>());
        when(accountData.getSummary()).thenReturn(summary);
        when(writePlatformService.postInterest(accountData, false, null, false)).thenReturn(accountData);
        when(interestChargeRepository.findPendingByAccountIdUpTo(eq(7L), any(LocalDate.class))).thenReturn(Collections.emptyList());

        final AdvanclySavingsSchedularInterestPoster poster = new AdvanclySavingsSchedularInterestPoster(writePlatformService, jdbcTemplate,
                readPlatformService, securityContext, interestChargeRepository, savingsAccountTransactionRepository);
        poster.setSavingAccounts(List.of(accountData));
        poster.setBackdatedTxnsAllowedTill(false);

        poster.postInterest();

        verify(interestChargeRepository, never()).saveAll(anyList());
        // No transactions on the account at all (nothing to insert), so batchUpdate's own guard
        // (`if (transRefNo.size() > 0)`) never fires either - and updateDerivedChargeColumns never runs, since
        // pendingApplications stayed empty. jdbcTemplate should see no interactions whatsoever.
        verifyNoInteractions(jdbcTemplate);
    }

    private static void stubAuthenticatedUser(final PlatformSecurityContext securityContext) {
        final AppUser appUser = mock(AppUser.class);
        when(appUser.getId()).thenReturn(1L);
        when(securityContext.authenticatedUser()).thenReturn(appUser);
    }

    /**
     * A real (non-mocked) interest-posting transaction, built the same way core's own DTO/JDBC posting path builds one
     * ({@code SavingsAccountTransactionData.create(...)} with the overload that populates the transient
     * {@code transactionDate} field - see the production-code note this test's class javadoc references). {@code id} is
     * left null: like the charge transaction this task's code creates alongside it, it is a newly-computed-this-run
     * transaction that {@code batchUpdate} (copied from core) still needs to insert.
     */
    private static SavingsAccountTransactionData buildInterestPostingTransaction(final BigDecimal amount, final BigDecimal runningBalance) {
        final SavingsAccountTransactionEnumData transactionType = SavingsEnumerations
                .transactionType(SavingsAccountTransactionType.INTEREST_POSTING.getValue());
        return SavingsAccountTransactionData.create(null, transactionType, null, ACCOUNT_ID, "000042", POSTING_DATE, CURRENCY, amount, null,
                runningBalance, false, POSTING_DATE, false, null, POSTING_DATE, OffsetDateTime.now());
    }
}
