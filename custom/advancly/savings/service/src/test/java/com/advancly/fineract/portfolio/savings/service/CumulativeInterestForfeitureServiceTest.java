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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.advancly.fineract.portfolio.savings.domain.SavingsAccountInterestCharge;
import com.advancly.fineract.portfolio.savings.domain.SavingsAccountInterestChargeRepository;
import com.advancly.fineract.portfolio.savings.testutil.MoneyHelperInitializer;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.apache.fineract.accounting.journalentry.service.JournalEntryWritePlatformService;
import org.apache.fineract.organisation.monetary.domain.MonetaryCurrency;
import org.apache.fineract.organisation.office.domain.Office;
import org.apache.fineract.portfolio.charge.domain.Charge;
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

class CumulativeInterestForfeitureServiceTest {

    private static final MonetaryCurrency CURRENCY = new MonetaryCurrency("USD", 2, null);
    private static final LocalDate WITHDRAWAL_DATE = LocalDate.of(2026, 2, 15);

    private final List<SavingsAccountInterestCharge> savedRows = new ArrayList<>();

    private SavingsAccountInterestChargeRepository interestChargeRepository;
    private DynamicDepositEarlyWithdrawalChargeService chargeService;
    private SavingsAccountWritePlatformService writePlatformService;
    private NoteRepository noteRepository;
    private SavingsAccountRepositoryWrapper savingsAccountRepository;
    private JournalEntryWritePlatformService journalEntryWritePlatformService;
    private CumulativeInterestForfeitureService service;

    @BeforeEach
    void setUp() {
        MoneyHelperInitializer.initialize();
        this.savedRows.clear();

        this.interestChargeRepository = mock(SavingsAccountInterestChargeRepository.class);
        lenient().when(this.interestChargeRepository.saveAndFlush(any())).thenAnswer(invocation -> {
            final SavingsAccountInterestCharge row = invocation.getArgument(0);
            this.savedRows.add(row);
            return row;
        });
        lenient().when(this.interestChargeRepository.sumPostedChargeAmount(anyLong())).thenReturn(BigDecimal.ZERO);

        this.chargeService = mock(DynamicDepositEarlyWithdrawalChargeService.class);
        lenient().when(this.chargeService.currentPeriodStartDate(any())).thenReturn(LocalDate.of(2026, 2, 1));

        this.writePlatformService = mock(SavingsAccountWritePlatformService.class);
        this.noteRepository = mock(NoteRepository.class);
        this.savingsAccountRepository = mock(SavingsAccountRepositoryWrapper.class);
        this.journalEntryWritePlatformService = mock(JournalEntryWritePlatformService.class);

        this.service = new CumulativeInterestForfeitureService(this.interestChargeRepository, this.chargeService, this.writePlatformService,
                this.noteRepository, this.savingsAccountRepository, this.journalEntryWritePlatformService);
    }

    @Test
    void interestIsForcePostedThroughTheDayBeforeTheWithdrawalBeforeAnythingIsForfeited() {
        final SavingsAccount account = account(new BigDecimal("70"), BigDecimal.ZERO);
        stubQualifyingCharge(new BigDecimal("100"));

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
        stubQualifyingCharge(new BigDecimal("100"));

        this.service.forfeitIfApplicable(account, WITHDRAWAL_DATE, false, true);

        verify(this.writePlatformService).postInterest(account, true, WITHDRAWAL_DATE, false);
    }

    @Test
    void theWholePostedInterestIsForfeitedAsOneBalanceAffectingTransaction() {
        final SavingsAccount account = account(new BigDecimal("70"), BigDecimal.ZERO);
        stubQualifyingCharge(new BigDecimal("100"));

        final SavingsAccountTransaction forfeiture = this.service.forfeitIfApplicable(account, WITHDRAWAL_DATE, false, false);

        assertThat(forfeiture).isNotNull();
        assertThat(forfeiture.getAmount(CURRENCY).getAmount()).isEqualByComparingTo("70");
        assertThat(forfeiture.isInterestForfeiture()).isTrue();
        assertThat(forfeiture.getTransactionDate()).isEqualTo(WITHDRAWAL_DATE);
    }

    @Test
    void anAlreadyAppliedRowIsWrittenSoTheNextWithdrawalNetsCorrectly() {
        final SavingsAccount account = account(new BigDecimal("70"), BigDecimal.ZERO);
        stubQualifyingCharge(new BigDecimal("100"));

        this.service.forfeitIfApplicable(account, WITHDRAWAL_DATE, false, false);

        assertThat(this.savedRows).hasSize(1);
        final SavingsAccountInterestCharge row = this.savedRows.get(0);
        assertThat(row.chargeAmount()).isEqualByComparingTo("70");
        assertThat(row.isPending()).isFalse();
    }

    @Test
    void theSummaryIsRefreshedAndTheJournalEntriesArePostedBeforeTheCallerEverSeesTheBalance() {
        // The whole point of doing both here: the caller (a withdrawal, or a premature closure reading the balance to
        // decide its payout) runs entirely AFTER this returns, and its own "already existing transaction ids" snapshot
        // is taken after the flush below - so nothing it does can refresh the summary for, or journal, what this wrote.
        final SavingsAccount account = account(new BigDecimal("70"), BigDecimal.ZERO);
        stubQualifyingCharge(new BigDecimal("100"));

        this.service.forfeitIfApplicable(account, WITHDRAWAL_DATE, false, false);

        final InOrder inOrder = inOrder(account, this.savingsAccountRepository, this.journalEntryWritePlatformService);
        inOrder.verify(account).refreshSummary(false);
        inOrder.verify(this.savingsAccountRepository).saveAndFlush(account);
        inOrder.verify(this.journalEntryWritePlatformService).createJournalEntriesForSavings(any());
    }

    @Test
    void nothingIsFlushedOrJournalledWhenTheForfeitureWritesNoTransactionAtAll() {
        final SavingsAccount account = account(BigDecimal.ZERO, BigDecimal.ZERO);
        stubQualifyingCharge(new BigDecimal("100"));

        this.service.forfeitIfApplicable(account, WITHDRAWAL_DATE, false, false);

        verify(account, never()).refreshSummary(anyBoolean());
        verifyNoInteractions(this.savingsAccountRepository);
        verifyNoInteractions(this.journalEntryWritePlatformService);
    }

    @Test
    void nothingIsWrittenWhenNoInterestHasPostedYet() {
        // The 5,000,000-deposited-then-withdrawn-after-three-weeks case.
        final SavingsAccount account = account(BigDecimal.ZERO, BigDecimal.ZERO);
        stubQualifyingCharge(new BigDecimal("100"));

        final SavingsAccountTransaction forfeiture = this.service.forfeitIfApplicable(account, WITHDRAWAL_DATE, false, false);

        assertThat(forfeiture).isNull();
        assertThat(this.savedRows).isEmpty();
    }

    @Test
    void nothingHappensWhenTheProductHasNoQualifyingCharge() {
        final SavingsAccount account = account(new BigDecimal("70"), BigDecimal.ZERO);
        lenient().when(this.chargeService.resolveQualifyingChargeWithPercentage(any())).thenReturn(null);

        final SavingsAccountTransaction forfeiture = this.service.forfeitIfApplicable(account, WITHDRAWAL_DATE, false, false);

        assertThat(forfeiture).isNull();
        verify(this.writePlatformService, never()).postInterest(any(SavingsAccount.class), anyBoolean(), any(), anyBoolean());
    }

    @Test
    void theAutomaticWithholdingTaxIsSuppressedDuringTheForcedPostingThenRestoredImmediately() {
        final SavingsAccount account = account(new BigDecimal("70"), BigDecimal.ZERO);
        lenient().when(account.withHoldTax()).thenReturn(true);
        stubQualifyingCharge(new BigDecimal("100"));

        this.service.forfeitIfApplicable(account, WITHDRAWAL_DATE, false, false);

        // Suppressed before the forced posting (so postInterest's own automatic tax step never fires on the gross
        // amount) and restored immediately after - the account's real tax configuration is unchanged either way.
        final InOrder inOrder = inOrder(account, this.writePlatformService);
        inOrder.verify(account).setWithHoldTax(false);
        inOrder.verify(this.writePlatformService).postInterest(account, true, WITHDRAWAL_DATE.minusDays(1), false);
        inOrder.verify(account).setWithHoldTax(true);
    }

    @Test
    void noTaxAppliesWhenForfeitureConsumesTheEntireNewlyPostedStub() {
        // 80 lifetime interest from prior periods, then a 20 stub is force-posted (100 total). 50% mode -> forfeit =
        // 50, which swallows the whole 20 stub (and reaches back into 30 of the older 80) - nothing of the stub
        // survives, so no tax applies to it.
        final SavingsAccount account = account(new BigDecimal("100"), BigDecimal.ZERO);
        lenient().when(account.withHoldTax()).thenReturn(true);
        withNewlyPostedStub(account, WITHDRAWAL_DATE.minusDays(1), new BigDecimal("20"));
        stubQualifyingCharge(new BigDecimal("50"));

        this.service.forfeitIfApplicable(account, WITHDRAWAL_DATE, false, false);

        verify(account, never()).withholdTaxIfApplicable(any(), any(), anyBoolean());
    }

    @Test
    void taxAppliesToWhateverOfTheNewlyPostedStubSurvivesForfeiture() {
        // No prior interest at all - the 20 stub is the entire basis. 50% mode -> forfeit = 10, survivor = 10.
        final SavingsAccount account = account(new BigDecimal("20"), BigDecimal.ZERO);
        lenient().when(account.withHoldTax()).thenReturn(true);
        withNewlyPostedStub(account, WITHDRAWAL_DATE.minusDays(1), new BigDecimal("20"));
        stubQualifyingCharge(new BigDecimal("50"));

        this.service.forfeitIfApplicable(account, WITHDRAWAL_DATE, false, false);

        verify(account).withholdTaxIfApplicable(eq(new BigDecimal("10")), eq(WITHDRAWAL_DATE.minusDays(1)), eq(false));
    }

    @Test
    void theWholeNewlyPostedStubIsTaxedWhenNothingRemainsToForfeit() {
        // Everything forfeitable (the whole 20 basis) was already forfeited by a prior withdrawal, so this
        // withdrawal's forfeit computes to exactly zero - but the 20 stub was still force-posted and must still be
        // taxed in full, exactly as an ordinary posting would be. Guards against a future "cleanup" moving the
        // tax-on-survivor block after the zero-forfeit early return, or gating it on amount > 0.
        final SavingsAccount account = account(new BigDecimal("20"), BigDecimal.ZERO);
        lenient().when(account.withHoldTax()).thenReturn(true);
        lenient().when(this.interestChargeRepository.sumPostedChargeAmount(anyLong())).thenReturn(new BigDecimal("20"));
        withNewlyPostedStub(account, WITHDRAWAL_DATE.minusDays(1), new BigDecimal("20"));
        stubQualifyingCharge(new BigDecimal("50"));

        final SavingsAccountTransaction forfeiture = this.service.forfeitIfApplicable(account, WITHDRAWAL_DATE, false, false);

        assertThat(forfeiture).isNull();
        verify(account).withholdTaxIfApplicable(eq(new BigDecimal("20")), eq(WITHDRAWAL_DATE.minusDays(1)), eq(false));
        // ...and the tax transaction it just wrote is flushed and journalled here too, since the caller's snapshot
        // would otherwise be the only thing that could journal it.
        verify(account).refreshSummary(false);
        verify(this.journalEntryWritePlatformService).createJournalEntriesForSavings(any());
    }

    private void withNewlyPostedStub(final SavingsAccount account, final LocalDate date, final BigDecimal amount) {
        final SavingsAccountTransaction stub = mock(SavingsAccountTransaction.class);
        lenient().when(stub.isInterestPostingAndNotReversed()).thenReturn(true);
        lenient().when(stub.getTransactionDate()).thenReturn(date);
        lenient().when(stub.getAmount()).thenReturn(amount);
        lenient().when(account.getTransactions()).thenReturn(List.of(stub));
    }

    private void stubQualifyingCharge(final BigDecimal percentage) {
        final Charge definition = mock(Charge.class);
        final SavingsAccountCharge accountCharge = mock(SavingsAccountCharge.class);
        lenient().when(accountCharge.getCharge()).thenReturn(definition);
        lenient().when(this.chargeService.resolveQualifyingChargeWithPercentage(any()))
                .thenReturn(new DynamicDepositEarlyWithdrawalChargeService.QualifyingCharge(accountCharge, percentage));
    }

    private SavingsAccount account(final BigDecimal totalInterestPosted, final BigDecimal totalWithholdTax) {
        final SavingsAccount account = mock(SavingsAccount.class);
        final SavingsAccountSummary summary = mock(SavingsAccountSummary.class);
        lenient().when(summary.getTotalInterestPosted()).thenReturn(totalInterestPosted);
        lenient().when(summary.getTotalWithholdTax()).thenReturn(totalWithholdTax);
        lenient().when(account.getId()).thenReturn(1L);
        lenient().when(account.getSummary()).thenReturn(summary);
        lenient().when(account.getCurrency()).thenReturn(CURRENCY);
        lenient().when(account.office()).thenReturn(mock(Office.class));
        return account;
    }
}
