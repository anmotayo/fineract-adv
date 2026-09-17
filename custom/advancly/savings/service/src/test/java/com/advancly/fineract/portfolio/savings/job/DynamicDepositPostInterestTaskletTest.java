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
package com.advancly.fineract.portfolio.savings.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.isA;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.advancly.fineract.portfolio.savings.domain.DepositAccountDynamicRateHistory;
import com.advancly.fineract.portfolio.savings.domain.DepositAccountDynamicRateHistoryRepository;
import com.advancly.fineract.portfolio.savings.domain.DynamicDepositAccount;
import com.advancly.fineract.portfolio.savings.domain.DynamicDepositAccountRepository;
import com.advancly.fineract.portfolio.savings.domain.DynamicDepositRateHistoryEventType;
import com.advancly.fineract.portfolio.savings.domain.DynamicDepositRateSource;
import com.advancly.fineract.portfolio.savings.domain.SavingsAccountInterestChargeRepository;
import com.advancly.fineract.portfolio.savings.service.DynamicDepositServiceLocator;
import com.advancly.fineract.portfolio.savings.testutil.MoneyHelperInitializer;
import com.advancly.fineract.portfolio.savings.testutil.SavingsAccountSummaryTestBuilder;
import com.advancly.fineract.portfolio.savings.testutil.SavingsAccountTransactionTestBuilder;
import java.lang.reflect.Constructor;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import org.apache.fineract.infrastructure.businessdate.domain.BusinessDateType;
import org.apache.fineract.infrastructure.configuration.domain.ConfigurationDomainService;
import org.apache.fineract.infrastructure.core.serialization.FromJsonHelper;
import org.apache.fineract.infrastructure.core.service.ExternalIdFactory;
import org.apache.fineract.infrastructure.core.service.ThreadLocalContextUtil;
import org.apache.fineract.infrastructure.event.business.domain.savings.SavingsPostInterestBusinessEvent;
import org.apache.fineract.infrastructure.event.business.service.BusinessEventNotifierService;
import org.apache.fineract.organisation.monetary.domain.MonetaryCurrency;
import org.apache.fineract.organisation.monetary.domain.MoneyHelper;
import org.apache.fineract.organisation.office.domain.Office;
import org.apache.fineract.organisation.staff.domain.StaffRepositoryWrapper;
import org.apache.fineract.portfolio.account.service.AccountTransfersReadPlatformService;
import org.apache.fineract.portfolio.client.domain.Client;
import org.apache.fineract.portfolio.client.domain.ClientRepositoryWrapper;
import org.apache.fineract.portfolio.group.domain.GroupRepositoryWrapper;
import org.apache.fineract.portfolio.savings.SavingsAccountTransactionType;
import org.apache.fineract.portfolio.savings.domain.DepositAccountTermAndPreClosure;
import org.apache.fineract.portfolio.savings.domain.SavingsAccount;
import org.apache.fineract.portfolio.savings.domain.SavingsAccountAssembler;
import org.apache.fineract.portfolio.savings.domain.SavingsAccountChargeAssembler;
import org.apache.fineract.portfolio.savings.domain.SavingsAccountRepositoryWrapper;
import org.apache.fineract.portfolio.savings.domain.SavingsAccountStatusType;
import org.apache.fineract.portfolio.savings.domain.SavingsAccountSummary;
import org.apache.fineract.portfolio.savings.domain.SavingsAccountTransaction;
import org.apache.fineract.portfolio.savings.domain.SavingsAccountTransactionDataSummaryWrapper;
import org.apache.fineract.portfolio.savings.domain.SavingsAccountTransactionSummaryWrapper;
import org.apache.fineract.portfolio.savings.domain.SavingsHelper;
import org.apache.fineract.portfolio.savings.domain.SavingsProductRepository;
import org.apache.fineract.portfolio.savings.exception.SavingsAccountNotFoundException;
import org.apache.fineract.portfolio.savings.service.SavingsAccountWritePlatformService;
import org.apache.fineract.portfolio.savings.service.SavingsInterestReadPlatformService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.batch.core.StepContribution;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.repeat.RepeatStatus;
import org.springframework.batch.support.transaction.ResourcelessTransactionManager;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * Exercises {@link DynamicDepositPostInterestTasklet} against a real {@link SavingsAccountAssembler} and a real
 * {@link DynamicDepositAccount} (only the assembler's own collaborators and the write-platform-service seam are mocked)
 * - not a fully-mocked account - specifically so that a regression back to loading accounts without calling
 * {@code SavingsAccountAssembler#assembleFrom}/{@code SavingsAccount#setHelpers} would make these tests fail with a
 * real {@code NullPointerException} inside {@code DynamicDepositAccount#calculateInterestUsing}, the same way it would
 * in production, rather than silently passing against a mock that can't tell the difference.
 */
@ExtendWith(MockitoExtension.class)
class DynamicDepositPostInterestTaskletTest {

    private static final MonetaryCurrency CURRENCY = new MonetaryCurrency("USD", 2, null);

    @Mock
    private DynamicDepositAccountRepository dynamicDepositAccountRepository;
    @Mock
    private SavingsAccountRepositoryWrapper savingsAccountRepositoryWrapper;
    @Mock
    private SavingsAccountWritePlatformService savingsAccountWritePlatformService;
    @Mock
    private StepContribution stepContribution;
    @Mock
    private ChunkContext chunkContext;
    @Mock
    private DepositAccountDynamicRateHistoryRepository rateHistoryRepository;
    @Mock
    private SavingsAccountInterestChargeRepository interestChargeRepository;
    @Mock
    private BusinessEventNotifierService businessEventNotifierService;

    private final PlatformTransactionManager transactionManager = new ResourcelessTransactionManager();

    private DynamicDepositPostInterestTasklet underTest;

    @BeforeEach
    void setUp() {
        MoneyHelperInitializer.initialize();

        final HashMap<BusinessDateType, LocalDate> businessDates = new HashMap<>();
        businessDates.put(BusinessDateType.BUSINESS_DATE, LocalDate.of(2026, 3, 1));
        ThreadLocalContextUtil.setBusinessDates(businessDates);

        final ApplicationContext applicationContext = mock(ApplicationContext.class);
        lenient().when(applicationContext.getBean(DepositAccountDynamicRateHistoryRepository.class)).thenReturn(this.rateHistoryRepository);
        // Phase 4: postInterest now resolves the interest-charge repository through the locator on every run (see
        // DynamicDepositAccountInterestTest), so this end-to-end tasklet test needs the same stub or the real
        // postInterest call below NPEs, gets swallowed by the tasklet's per-account catch, and silently drops the
        // SavingsPostInterestBusinessEvent these tests verify. Defaults to "nothing pending" - these tests are not
        // exercising interest-based charges themselves.
        lenient().when(applicationContext.getBean(SavingsAccountInterestChargeRepository.class)).thenReturn(this.interestChargeRepository);
        lenient().when(this.interestChargeRepository.findPendingByAccountIdUpTo(anyLong(), any())).thenReturn(List.of());
        ReflectionTestUtils.setField(DynamicDepositServiceLocator.class, "applicationContext", applicationContext);
        lenient().when(this.rateHistoryRepository.findByAccountIdOrderByTransactionDateAscIdAsc(anyLong())).thenReturn(List.of());

        // A real SavingsAccountAssembler (only its own collaborators are mocked) - this is what actually calls
        // SavingsAccount#setHelpers(...) in production, so it must do the same here for these tests to mean anything.
        final AccountTransfersReadPlatformService accountTransfersReadPlatformService = mock(AccountTransfersReadPlatformService.class);
        lenient().when(accountTransfersReadPlatformService.fetchPostInterestTransactionIds(anyLong())).thenReturn(List.of());
        final SavingsInterestReadPlatformService savingsInterestReadPlatformService = mock(SavingsInterestReadPlatformService.class);
        final SavingsHelper savingsHelper = new SavingsHelper(accountTransfersReadPlatformService, savingsInterestReadPlatformService);
        final SavingsAccountAssembler savingsAccountAssembler = new SavingsAccountAssembler(new SavingsAccountTransactionSummaryWrapper(),
                mock(SavingsAccountTransactionDataSummaryWrapper.class), mock(ClientRepositoryWrapper.class),
                mock(GroupRepositoryWrapper.class), mock(StaffRepositoryWrapper.class), mock(SavingsProductRepository.class),
                this.savingsAccountRepositoryWrapper, mock(SavingsAccountChargeAssembler.class), mock(FromJsonHelper.class), savingsHelper,
                mock(JdbcTemplate.class), mock(ConfigurationDomainService.class), mock(ExternalIdFactory.class));

        this.underTest = new DynamicDepositPostInterestTasklet(this.dynamicDepositAccountRepository, savingsAccountAssembler,
                this.savingsAccountWritePlatformService, this.transactionManager, this.businessEventNotifierService);

        // Forwards straight to the real, Task-3-overridden entity method - exactly what
        // SavingsAccountWritePlatformServiceJpaRepositoryImpl#postInterest(SavingsAccount,...) does in production
        // (minus its own persistence/journal-entry side effects, which are irrelevant here) - so these tests exercise
        // the real calculateInterestUsing/postInterest override, not a mock that always "succeeds".
        lenient().doAnswer(invocation -> {
            final SavingsAccount account = invocation.getArgument(0);
            final LocalDate transactionDate = invocation.getArgument(2);
            final boolean backdatedTxnsAllowedTill = invocation.getArgument(3);
            account.postInterest(MoneyHelper.getMathContext(), transactionDate, false, false, 1, null, backdatedTxnsAllowedTill, true);
            return null;
        }).when(this.savingsAccountWritePlatformService).postInterest(any(SavingsAccount.class), anyBoolean(), any(LocalDate.class),
                anyBoolean());
    }

    @AfterEach
    void tearDown() {
        ThreadLocalContextUtil.reset();
    }

    @Test
    void postsInterestForEveryActiveAccountThroughTheRealAssemblerAndEntityPostInterestPath() {
        final DynamicDepositAccount account1 = buildBareAccount(1L);
        final DynamicDepositAccount account2 = buildBareAccount(2L);
        when(this.dynamicDepositAccountRepository.findIdsByStatus(SavingsAccountStatusType.ACTIVE.getValue())).thenReturn(List.of(1L, 2L));
        when(this.savingsAccountRepositoryWrapper.findSavingsWithNotFoundDetection(1L, false)).thenReturn(account1);
        when(this.savingsAccountRepositoryWrapper.findSavingsWithNotFoundDetection(2L, false)).thenReturn(account2);

        final RepeatStatus result = this.underTest.execute(this.stepContribution, this.chunkContext);

        assertThat(result).isEqualTo(RepeatStatus.FINISHED);
        // If the tasklet had loaded these accounts without going through SavingsAccountAssembler#assembleFrom (and
        // therefore without SavingsAccount#setHelpers being called), account.postInterest(...) above would have
        // NPE'd inside DynamicDepositAccount#calculateInterestUsing, been swallowed by the tasklet's per-account
        // catch, and left no interest posting transaction here at all - this assertion would then fail.
        assertThat(account1.getTransactions()).anyMatch(SavingsAccountTransaction::isInterestPostingAndNotReversed);
        assertThat(account2.getTransactions()).anyMatch(SavingsAccountTransaction::isInterestPostingAndNotReversed);
        // The scheduled path must fire SavingsPostInterestBusinessEvent per successfully-posted account, exactly as
        // SavingsAccountWritePlatformServiceJpaRepositoryImpl#postInterest(JsonCommand) does for the command-driven
        // path - otherwise external event consumers (Kafka/JMS) never see scheduled Dynamic Deposit postings.
        verify(this.businessEventNotifierService, times(2)).notifyPostBusinessEvent(isA(SavingsPostInterestBusinessEvent.class));
    }

    @Test
    void continuesWithRemainingAccountsWhenOneAccountFailsToLoad() {
        final DynamicDepositAccount account2 = buildBareAccount(2L);
        when(this.dynamicDepositAccountRepository.findIdsByStatus(SavingsAccountStatusType.ACTIVE.getValue())).thenReturn(List.of(1L, 2L));
        // Simulates any failure while loading/assembling account 1 (equally representative of a failure during
        // posting itself) - the point is that this one account's failure must not stop account 2 from being posted,
        // nor should it need to appear as an UnexpectedRollbackException from a poisoned shared transaction.
        when(this.savingsAccountRepositoryWrapper.findSavingsWithNotFoundDetection(1L, false))
                .thenThrow(new SavingsAccountNotFoundException(1L));
        when(this.savingsAccountRepositoryWrapper.findSavingsWithNotFoundDetection(2L, false)).thenReturn(account2);

        final RepeatStatus result = this.underTest.execute(this.stepContribution, this.chunkContext);

        assertThat(result).isEqualTo(RepeatStatus.FINISHED);
        // Only account 2 ever reaches postInterest - account 1's failure happened while loading it, before
        // postInterest could be called at all - and account 2's posting must still have gone through.
        verify(this.savingsAccountWritePlatformService, times(1)).postInterest(any(SavingsAccount.class), anyBoolean(), any(),
                anyBoolean());
        assertThat(account2.getTransactions()).anyMatch(SavingsAccountTransaction::isInterestPostingAndNotReversed);
        verify(this.businessEventNotifierService, times(1)).notifyPostBusinessEvent(isA(SavingsPostInterestBusinessEvent.class));
    }

    @Test
    void doesNothingWhenNoActiveDynamicDepositAccountsExist() {
        when(this.dynamicDepositAccountRepository.findIdsByStatus(SavingsAccountStatusType.ACTIVE.getValue())).thenReturn(List.of());

        final RepeatStatus result = this.underTest.execute(this.stepContribution, this.chunkContext);

        assertThat(result).isEqualTo(RepeatStatus.FINISHED);
        verify(this.savingsAccountRepositoryWrapper, never()).findSavingsWithNotFoundDetection(anyLong(), anyBoolean());
        verify(this.savingsAccountWritePlatformService, never()).postInterest(any(SavingsAccount.class), anyBoolean(), any(),
                anyBoolean());
        verify(this.businessEventNotifierService, never()).notifyPostBusinessEvent(any());
    }

    private DynamicDepositAccount buildBareAccount(final long id) {
        final DynamicDepositAccount account = createInstance(DynamicDepositAccount.class);
        ReflectionTestUtils.setField(account, "id", id);
        ReflectionTestUtils.setField(account, "currency", CURRENCY);
        ReflectionTestUtils.setField(account, "activatedOnDate", LocalDate.of(2026, 1, 1));
        ReflectionTestUtils.setField(account, "nominalAnnualInterestRate", BigDecimal.valueOf(2));
        ReflectionTestUtils.setField(account, "interestCompoundingPeriodType", 8); // NO_COMPOUNDING_SIMPLE_INTEREST
        ReflectionTestUtils.setField(account, "interestPostingPeriodType", 4); // MONTHLY
        ReflectionTestUtils.setField(account, "interestCalculationType", 1); // DAILY_BALANCE
        ReflectionTestUtils.setField(account, "interestCalculationDaysInYearType", 365);
        // DynamicDepositAccount#withHoldTaxPostingType() dereferences accountTermAndPreClosure unconditionally
        // (mirroring FixedDepositAccount/RecurringDepositAccount) - postInterest calls it regardless of whether
        // withholding tax is actually configured, so it must never be null, even when these tests aren't exercising
        // WHT themselves.
        ReflectionTestUtils.setField(account, "accountTermAndPreClosure",
                DepositAccountTermAndPreClosure.createNew(null, null, null, null, null, null, null, null, null, null, false, null, null));
        // Deliberately NOT setting savingsHelper/savingsAccountTransactionSummaryWrapper here - populating those is
        // exactly SavingsAccountAssembler#assembleFrom's job (via SavingsAccount#setHelpers), which is what these
        // tests are verifying the tasklet actually goes through.

        final SavingsAccountSummary summary = new SavingsAccountSummaryTestBuilder().build();
        ReflectionTestUtils.setField(account, "summary", summary);

        final Office office = mock(Office.class);
        lenient().when(office.getId()).thenReturn(1L);
        final Client client = mock(Client.class);
        lenient().when(client.getOffice()).thenReturn(office);
        lenient().when(client.officeId()).thenReturn(1L);
        lenient().when(client.getId()).thenReturn(1L);
        ReflectionTestUtils.setField(account, "client", client);

        final SavingsAccountTransaction openingDeposit = new SavingsAccountTransactionTestBuilder().withId(id * 100 + 1)
                .withSavingsAccount(account).withType(SavingsAccountTransactionType.DEPOSIT).withDate(LocalDate.of(2026, 1, 1))
                .withAmount(BigDecimal.valueOf(1000)).build();
        account.getTransactions().add(openingDeposit);

        // DynamicDepositInterestIntervalSplitter#resolveRateAsOf throws IllegalStateException for any period with no
        // rate history at or before it - so a rate history row is required here, or postInterest(...) above would
        // throw and be swallowed by the tasklet's per-account catch (mirrors every fixture in
        // DynamicDepositAccountInterestTest).
        final List<DepositAccountDynamicRateHistory> rateHistory = List.of(DepositAccountDynamicRateHistory.createNew(account,
                openingDeposit, LocalDate.of(2026, 1, 1), DynamicDepositRateHistoryEventType.ACCOUNT_ACTIVATION, BigDecimal.valueOf(1000),
                12, 2, null, null, BigDecimal.valueOf(2), BigDecimal.valueOf(2), DynamicDepositRateSource.INTEREST_RATE_CHART));
        lenient().when(this.rateHistoryRepository.findByAccountIdOrderByTransactionDateAscIdAsc(id)).thenReturn(rateHistory);

        return account;
    }

    private static <T> T createInstance(final Class<T> clazz) {
        try {
            final Constructor<T> constructor = clazz.getDeclaredConstructor();
            constructor.setAccessible(true);
            return constructor.newInstance();
        } catch (final Exception e) {
            throw new RuntimeException("Failed to create instance of " + clazz.getName(), e);
        }
    }
}
