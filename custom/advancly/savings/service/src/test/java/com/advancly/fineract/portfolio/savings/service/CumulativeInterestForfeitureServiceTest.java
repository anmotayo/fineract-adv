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
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.advancly.fineract.portfolio.savings.domain.AdvanclyChargeInterestRule;
import com.advancly.fineract.portfolio.savings.domain.AdvanclyChargeInterestRuleRepository;
import com.advancly.fineract.portfolio.savings.domain.DepositInterestChargeApplication;
import com.advancly.fineract.portfolio.savings.domain.DepositInterestChargeApplicationRepository;
import com.advancly.fineract.portfolio.savings.domain.InterestBasisMode;
import com.advancly.fineract.portfolio.savings.testutil.MoneyHelperInitializer;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.apache.fineract.accounting.journalentry.service.JournalEntryWritePlatformService;
import org.apache.fineract.organisation.monetary.domain.MonetaryCurrency;
import org.apache.fineract.organisation.office.domain.Office;
import org.apache.fineract.portfolio.charge.domain.Charge;
import org.apache.fineract.portfolio.charge.domain.ChargeCalculationType;
import org.apache.fineract.portfolio.note.domain.NoteRepository;
import org.apache.fineract.portfolio.savings.domain.SavingsAccount;
import org.apache.fineract.portfolio.savings.domain.SavingsAccountCharge;
import org.apache.fineract.portfolio.savings.domain.SavingsAccountRepositoryWrapper;
import org.apache.fineract.portfolio.savings.domain.SavingsAccountSummary;
import org.apache.fineract.portfolio.savings.domain.SavingsAccountTransaction;
import org.apache.fineract.portfolio.savings.service.SavingsAccountWritePlatformService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.jdbc.core.JdbcTemplate;

class CumulativeInterestForfeitureServiceTest {

    private static final MonetaryCurrency CURRENCY = new MonetaryCurrency("USD", 2, null);
    private static final Long PRODUCT_ID = 42L;
    private static final Long CHARGE_ID = 7L;
    private static final LocalDate WITHDRAWAL_DATE = LocalDate.of(2026, 2, 15);

    private final List<DepositInterestChargeApplication> savedApplications = new ArrayList<>();

    private AdvanclyChargeInterestRuleRepository chargeInterestRuleRepository;
    private DepositInterestChargeApplicationRepository interestChargeApplicationRepository;
    private SavingsAccountWritePlatformService writePlatformService;
    private NoteRepository noteRepository;
    private SavingsAccountRepositoryWrapper savingsAccountRepository;
    private JournalEntryWritePlatformService journalEntryWritePlatformService;
    private JdbcTemplate jdbcTemplate;
    private CumulativeInterestForfeitureService service;

    @BeforeEach
    void setUp() {
        MoneyHelperInitializer.initialize();
        this.savedApplications.clear();

        this.chargeInterestRuleRepository = mock(AdvanclyChargeInterestRuleRepository.class);
        lenient().when(this.chargeInterestRuleRepository.findBySavingsProductId(anyLong())).thenReturn(List.of());

        this.interestChargeApplicationRepository = mock(DepositInterestChargeApplicationRepository.class);
        lenient().when(this.interestChargeApplicationRepository.saveAndFlush(any())).thenAnswer(invocation -> {
            final DepositInterestChargeApplication application = invocation.getArgument(0);
            this.savedApplications.add(application);
            return application;
        });
        lenient().when(this.interestChargeApplicationRepository.sumActiveAppliedAmountForAccount(anyLong())).thenReturn(BigDecimal.ZERO);

        this.writePlatformService = mock(SavingsAccountWritePlatformService.class);
        this.noteRepository = mock(NoteRepository.class);
        this.savingsAccountRepository = mock(SavingsAccountRepositoryWrapper.class);
        this.journalEntryWritePlatformService = mock(JournalEntryWritePlatformService.class);
        this.jdbcTemplate = mock(JdbcTemplate.class);

        this.service = new CumulativeInterestForfeitureService(this.chargeInterestRuleRepository, this.interestChargeApplicationRepository,
                this.writePlatformService, this.noteRepository, this.savingsAccountRepository, this.journalEntryWritePlatformService,
                this.jdbcTemplate);
    }

    @Test
    void interestIsForcePostedThroughTheDayBeforeTheWithdrawalBeforeAnythingIsForfeited() {
        final SavingsAccount account = account(new BigDecimal("70"), BigDecimal.ZERO);
        stubQualifyingCharge(account, new BigDecimal("100"));

        this.service.forfeitIfApplicable(account, WITHDRAWAL_DATE, false, false);

        // postInterestAs=true is what persists is_manual=true, which stops the next scheduled run double-posting.
        verify(this.writePlatformService).postInterest(account, true, WITHDRAWAL_DATE.minusDays(1), false);
    }

    @Test
    void aPrematureClosureForcePostsOnTheSameDateCoresPerPeriodClosurePathUses() {
        // closedDate - 1 would silently drop a day of accrual relative to the per-period closure path, which posts on
        // SavingsAccount#interestPostingTransactionDateForClosure(closedDate) - invisible at 100% forfeiture but a
        // real shortfall in what the customer keeps at any partial percentage.
        final SavingsAccount account = account(new BigDecimal("70"), BigDecimal.ZERO);
        lenient().when(account.interestPostingTransactionDateForClosure(WITHDRAWAL_DATE)).thenReturn(WITHDRAWAL_DATE);
        stubQualifyingCharge(account, new BigDecimal("100"));

        this.service.forfeitIfApplicable(account, WITHDRAWAL_DATE, false, true);

        verify(this.writePlatformService).postInterest(account, true, WITHDRAWAL_DATE, false);
    }

    @Test
    void theWholePostedInterestIsForfeitedAsOneBalanceAffectingTransaction() {
        final SavingsAccount account = account(new BigDecimal("70"), BigDecimal.ZERO);
        stubQualifyingCharge(account, new BigDecimal("100"));

        final SavingsAccountTransaction forfeiture = this.service.forfeitIfApplicable(account, WITHDRAWAL_DATE, false, false);

        assertThat(forfeiture).isNotNull();
        assertThat(forfeiture.getAmount(CURRENCY).getAmount()).isEqualByComparingTo("70");
        assertThat(forfeiture.isInterestForfeiture()).isTrue();
        assertThat(forfeiture.getTransactionDate()).isEqualTo(WITHDRAWAL_DATE);
    }

    @Test
    void anApplicationLedgerRowIsWrittenSoTheNextWithdrawalNetsCorrectly() {
        final SavingsAccount account = account(new BigDecimal("70"), BigDecimal.ZERO);
        stubQualifyingCharge(account, new BigDecimal("100"));

        this.service.forfeitIfApplicable(account, WITHDRAWAL_DATE, false, false);

        assertThat(this.savedApplications).hasSize(1);
        final DepositInterestChargeApplication application = this.savedApplications.get(0);
        assertThat(application.appliedAmount()).isEqualByComparingTo("70");
        assertThat(application.interestBasisMode()).isEqualTo(InterestBasisMode.CUMULATIVE);
    }

    @Test
    void theSummaryIsRefreshedAndTheJournalEntriesArePostedBeforeTheCallerEverSeesTheBalance() {
        // The whole point of doing both here: the caller (a withdrawal, or a premature closure reading the balance to
        // decide its payout) runs entirely AFTER this returns, and its own "already existing transaction ids" snapshot
        // is taken after the flush below - so nothing it does can refresh the summary for, or journal, what this wrote.
        final SavingsAccount account = account(new BigDecimal("70"), BigDecimal.ZERO);
        stubQualifyingCharge(account, new BigDecimal("100"));

        this.service.forfeitIfApplicable(account, WITHDRAWAL_DATE, false, false);

        final InOrder inOrder = inOrder(account, this.savingsAccountRepository, this.journalEntryWritePlatformService);
        inOrder.verify(account).refreshSummary(false);
        inOrder.verify(this.savingsAccountRepository).saveAndFlush(account);
        inOrder.verify(this.journalEntryWritePlatformService).createJournalEntriesForSavings(any());
    }

    @Test
    void postedInterestChargeColumnIsRefreshedAfterTheForfeitureRowIsSaved() {
        final SavingsAccount account = account(new BigDecimal("70"), BigDecimal.ZERO);
        stubQualifyingCharge(account, new BigDecimal("100"));
        lenient().when(this.interestChargeApplicationRepository.sumActiveAppliedAmountForAccount(1L)).thenReturn(BigDecimal.ZERO,
                new BigDecimal("70"));

        this.service.forfeitIfApplicable(account, WITHDRAWAL_DATE, false, false);

        final InOrder inOrder = inOrder(this.savingsAccountRepository, this.jdbcTemplate, this.journalEntryWritePlatformService);
        inOrder.verify(this.savingsAccountRepository).saveAndFlush(account);
        inOrder.verify(this.jdbcTemplate).update("update m_savings_account set interest_based_charge_posted_derived = ? where id = ?",
                new BigDecimal("70"), 1L);
        inOrder.verify(this.journalEntryWritePlatformService).createJournalEntriesForSavings(any());
    }

    @Test
    void nothingIsFlushedOrJournalledWhenTheForfeitureWritesNoTransactionAtAll() {
        final SavingsAccount account = account(BigDecimal.ZERO, BigDecimal.ZERO);
        stubQualifyingCharge(account, new BigDecimal("100"));

        this.service.forfeitIfApplicable(account, WITHDRAWAL_DATE, false, false);

        verify(account, never()).refreshSummary(anyBoolean());
        verifyNoInteractions(this.savingsAccountRepository);
        verifyNoInteractions(this.journalEntryWritePlatformService);
    }

    @Test
    void nothingIsWrittenWhenNoInterestHasPostedYet() {
        // The 5,000,000-deposited-then-withdrawn-after-three-weeks case.
        final SavingsAccount account = account(BigDecimal.ZERO, BigDecimal.ZERO);
        stubQualifyingCharge(account, new BigDecimal("100"));

        final SavingsAccountTransaction forfeiture = this.service.forfeitIfApplicable(account, WITHDRAWAL_DATE, false, false);

        assertThat(forfeiture).isNull();
        assertThat(this.savedApplications).isEmpty();
    }

    @Test
    void nothingHappensWhenTheProductHasNoQualifyingCharge() {
        final SavingsAccount account = account(new BigDecimal("70"), BigDecimal.ZERO);

        final SavingsAccountTransaction forfeiture = this.service.forfeitIfApplicable(account, WITHDRAWAL_DATE, false, false);

        assertThat(forfeiture).isNull();
        verify(this.writePlatformService, never()).postInterest(any(SavingsAccount.class), anyBoolean(), any(), anyBoolean());
    }

    @Test
    void alreadyPostedInterestOnTheForcedPostingDateIsReusedInsteadOfPostedAgain() {
        final SavingsAccount account = account(new BigDecimal("70"), BigDecimal.ZERO);
        withInterestPosting(account, WITHDRAWAL_DATE.minusDays(1), new BigDecimal("20"));
        stubQualifyingCharge(account, new BigDecimal("100"));

        final SavingsAccountTransaction forfeiture = this.service.forfeitIfApplicable(account, WITHDRAWAL_DATE, false, false);

        assertThat(forfeiture).isNotNull();
        verify(this.writePlatformService, never()).postInterest(any(SavingsAccount.class), anyBoolean(), any(), anyBoolean());
        verify(this.noteRepository).save(any());
    }

    @Test
    void forcedPostingKeepsWithholdingTaxEnabledForCorePostingAndDoesNotApplyManualTax() {
        final SavingsAccount account = account(new BigDecimal("70"), BigDecimal.ZERO);
        lenient().when(account.withHoldTax()).thenReturn(true);
        stubQualifyingCharge(account, new BigDecimal("100"));

        this.service.forfeitIfApplicable(account, WITHDRAWAL_DATE, false, false);

        verify(this.writePlatformService).postInterest(account, true, WITHDRAWAL_DATE.minusDays(1), false);
        verify(account, never()).setWithHoldTax(false);
        verify(account, never()).setWithHoldTax(true);
        verify(account, never()).withholdTaxIfApplicable(any(), any(), anyBoolean());
    }

    @Test
    void noManualTaxFlushOrJournalIsWrittenWhenNothingRemainsToForfeit() {
        // Everything forfeitable was already consumed by a prior withdrawal. Normal postInterest still runs, with the
        // account's real tax configuration intact, but this service does not create a separate manual tax transaction.
        final SavingsAccount account = account(new BigDecimal("20"), BigDecimal.ZERO);
        lenient().when(this.interestChargeApplicationRepository.sumActiveAppliedAmountForAccount(anyLong()))
                .thenReturn(new BigDecimal("20"));
        stubQualifyingCharge(account, new BigDecimal("50"));

        final SavingsAccountTransaction forfeiture = this.service.forfeitIfApplicable(account, WITHDRAWAL_DATE, false, false);

        assertThat(forfeiture).isNull();
        verify(account, never()).withholdTaxIfApplicable(any(), any(), anyBoolean());
        verify(account, never()).refreshSummary(anyBoolean());
        verifyNoInteractions(this.journalEntryWritePlatformService);
    }

    private void withInterestPosting(final SavingsAccount account, final LocalDate date, final BigDecimal amount) {
        final SavingsAccountTransaction stub = mock(SavingsAccountTransaction.class);
        lenient().when(stub.isInterestPostingAndNotReversed()).thenReturn(true);
        lenient().when(stub.getTransactionDate()).thenReturn(date);
        lenient().when(stub.getAmount()).thenReturn(amount);
        lenient().when(account.getTransactions()).thenReturn(List.of(stub));
    }

    private void stubQualifyingCharge(final SavingsAccount account, final BigDecimal percentage) {
        final AdvanclyChargeInterestRule rule = mock(AdvanclyChargeInterestRule.class);
        final Charge definition = mock(Charge.class);
        final SavingsAccountCharge accountCharge = mock(SavingsAccountCharge.class);
        lenient().when(this.chargeInterestRuleRepository.findBySavingsProductId(PRODUCT_ID)).thenReturn(List.of(rule));
        lenient().when(rule.isCumulative()).thenReturn(true);
        lenient().when(rule.chargeId()).thenReturn(CHARGE_ID);
        lenient().when(account.charges()).thenReturn(Set.of(accountCharge));
        lenient().when(accountCharge.getCharge()).thenReturn(definition);
        lenient().when(accountCharge.isActive()).thenReturn(true);
        lenient().when(accountCharge.isPenaltyCharge()).thenReturn(true);
        lenient().when(accountCharge.getPercentage()).thenReturn(percentage);
        lenient().when(definition.getId()).thenReturn(CHARGE_ID);
        lenient().when(definition.isPenalty()).thenReturn(true);
        lenient().when(definition.getChargeCalculation()).thenReturn(ChargeCalculationType.PERCENT_OF_INTEREST.getValue());
        lenient().when(definition.getAmount()).thenReturn(percentage);
    }

    private SavingsAccount account(final BigDecimal totalInterestPosted, final BigDecimal totalWithholdTax) {
        final SavingsAccount account = mock(SavingsAccount.class);
        final SavingsAccountSummary summary = mock(SavingsAccountSummary.class);
        lenient().when(summary.getTotalInterestPosted()).thenReturn(totalInterestPosted);
        lenient().when(summary.getTotalWithholdTax()).thenReturn(totalWithholdTax);
        lenient().when(account.getId()).thenReturn(1L);
        lenient().when(account.productId()).thenReturn(PRODUCT_ID);
        lenient().when(account.getSummary()).thenReturn(summary);
        lenient().when(account.getCurrency()).thenReturn(CURRENCY);
        lenient().when(account.office()).thenReturn(mock(Office.class));
        return account;
    }
}
