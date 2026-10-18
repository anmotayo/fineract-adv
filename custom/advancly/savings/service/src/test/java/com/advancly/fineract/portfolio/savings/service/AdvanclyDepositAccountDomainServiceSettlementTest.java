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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.advancly.fineract.portfolio.savings.domain.DepositAccountDynamicDetail;
import com.advancly.fineract.portfolio.savings.domain.DepositAccountDynamicRateHistory;
import com.advancly.fineract.portfolio.savings.domain.DepositAccountDynamicRateHistoryRepository;
import com.advancly.fineract.portfolio.savings.domain.DynamicDepositAccount;
import com.advancly.fineract.portfolio.savings.domain.DynamicDepositRateHistoryEventType;
import com.advancly.fineract.portfolio.savings.domain.DynamicDepositRateSource;
import com.advancly.fineract.portfolio.savings.testutil.MoneyHelperInitializer;
import com.advancly.fineract.portfolio.savings.testutil.SavingsAccountSummaryTestBuilder;
import com.advancly.fineract.portfolio.savings.testutil.SavingsAccountTransactionTestBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.lang.reflect.Constructor;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.fineract.accounting.journalentry.service.JournalEntryWritePlatformService;
import org.apache.fineract.infrastructure.businessdate.domain.BusinessDateType;
import org.apache.fineract.infrastructure.configuration.domain.ConfigurationDomainService;
import org.apache.fineract.infrastructure.core.api.JsonCommand;
import org.apache.fineract.infrastructure.core.serialization.FromJsonHelper;
import org.apache.fineract.infrastructure.core.service.ThreadLocalContextUtil;
import org.apache.fineract.organisation.monetary.domain.MonetaryCurrency;
import org.apache.fineract.organisation.monetary.domain.Money;
import org.apache.fineract.organisation.office.domain.Office;
import org.apache.fineract.portfolio.account.data.AccountTransferDTO;
import org.apache.fineract.portfolio.account.data.PortfolioAccountData;
import org.apache.fineract.portfolio.account.service.AccountAssociationsReadPlatformService;
import org.apache.fineract.portfolio.account.service.AccountTransfersReadPlatformService;
import org.apache.fineract.portfolio.account.service.AccountTransfersWritePlatformService;
import org.apache.fineract.portfolio.client.domain.Client;
import org.apache.fineract.portfolio.savings.DepositAccountType;
import org.apache.fineract.portfolio.savings.SavingsAccountTransactionType;
import org.apache.fineract.portfolio.savings.WithHoldTaxPostingType;
import org.apache.fineract.portfolio.savings.data.SavingsAccountTransactionEnumData;
import org.apache.fineract.portfolio.savings.domain.DepositAccountAssembler;
import org.apache.fineract.portfolio.savings.domain.DepositAccountDomainServiceJpa;
import org.apache.fineract.portfolio.savings.domain.DepositAccountTermAndPreClosure;
import org.apache.fineract.portfolio.savings.domain.SavingsAccount;
import org.apache.fineract.portfolio.savings.domain.SavingsAccountRepositoryWrapper;
import org.apache.fineract.portfolio.savings.domain.SavingsAccountStatusType;
import org.apache.fineract.portfolio.savings.domain.SavingsAccountTransaction;
import org.apache.fineract.portfolio.savings.domain.SavingsAccountTransactionRepository;
import org.apache.fineract.portfolio.savings.domain.SavingsAccountTransactionSummaryWrapper;
import org.apache.fineract.portfolio.savings.domain.SavingsHelper;
import org.apache.fineract.portfolio.savings.domain.SavingsProduct;
import org.apache.fineract.portfolio.savings.service.SavingsInterestReadPlatformService;
import org.apache.fineract.portfolio.tax.domain.TaxComponent;
import org.apache.fineract.portfolio.tax.domain.TaxGroup;
import org.apache.fineract.portfolio.tax.domain.TaxGroupMappings;
import org.apache.fineract.useradministration.domain.AppUser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationContext;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Closing a Dynamic Deposit against a REAL account: what interest and withholding-tax transactions the closure writes
 * and journals, and where the money goes for a principal-only reinvestment. Collaborators outside the account (the
 * payout, the transfer, the reinvestment) are mocked and record what they are asked to do.
 */
class AdvanclyDepositAccountDomainServiceSettlementTest {

    private static final MonetaryCurrency CURRENCY = new MonetaryCurrency("USD", 2, null);
    private static final LocalDate ACTIVATION_DATE = LocalDate.of(2026, 1, 1);
    private static final LocalDate MATURITY_DATE = LocalDate.of(2026, 1, 10);
    private static final LocalDate CLOSED_DATE = LocalDate.of(2026, 1, 20);
    private static final BigDecimal PRINCIPAL = BigDecimal.valueOf(1000);
    private static final Long ACCOUNT_ID = 1L;
    private static final Long LINKED_ACCOUNT_ID = 77L;
    private static final int WITHDRAW = 100;
    private static final int REINVEST_PRINCIPAL_AND_INTEREST = 300;
    private static final int REINVEST_PRINCIPAL_ONLY = 400;

    private DepositAccountDomainServiceJpa coreDomainService;
    private AccountTransfersWritePlatformService accountTransfersWritePlatformService;
    private DynamicDepositReinvestmentService reinvestmentService;
    private AccountAssociationsReadPlatformService accountAssociationsReadPlatformService;
    private DepositAccountAssembler depositAccountAssembler;
    private AdvanclyDepositAccountDomainService service;

    private DynamicDepositAccount account;
    private Office office;
    private SavingsAccount linkedAccount;

    /** Every payout leg, in the order it happened: "withdrawal" or "transfer", with its amount. */
    private final List<String> payoutLegs = new ArrayList<>();
    private final List<BigDecimal> payoutAmounts = new ArrayList<>();
    private final List<AccountTransferDTO> transfers = new ArrayList<>();
    private final List<Map<String, Object>> journalledTransactions = new ArrayList<>();
    private long nextId = 1000L;

    @BeforeEach
    void setUp() {
        MoneyHelperInitializer.initialize();
        this.payoutLegs.clear();
        this.payoutAmounts.clear();
        this.transfers.clear();
        this.journalledTransactions.clear();
        this.nextId = 1000L;
        final HashSet<BusinessDateType> unused = null;
        final java.util.HashMap<BusinessDateType, LocalDate> businessDates = new java.util.HashMap<>();
        businessDates.put(BusinessDateType.BUSINESS_DATE, CLOSED_DATE);
        businessDates.put(BusinessDateType.COB_DATE, CLOSED_DATE.minusDays(1));
        ThreadLocalContextUtil.setBusinessDates(businessDates);

        this.coreDomainService = mock(DepositAccountDomainServiceJpa.class);
        this.accountTransfersWritePlatformService = mock(AccountTransfersWritePlatformService.class);
        this.reinvestmentService = mock(DynamicDepositReinvestmentService.class);
        this.accountAssociationsReadPlatformService = mock(AccountAssociationsReadPlatformService.class);
        this.depositAccountAssembler = mock(DepositAccountAssembler.class);
        final SavingsAccountRepositoryWrapper savingsAccountRepository = mock(SavingsAccountRepositoryWrapper.class);
        final SavingsAccountTransactionRepository savingsAccountTransactionRepository = mock(SavingsAccountTransactionRepository.class);
        final JournalEntryWritePlatformService journalEntryWritePlatformService = mock(JournalEntryWritePlatformService.class);
        final ConfigurationDomainService configurationDomainService = mock(ConfigurationDomainService.class);
        when(configurationDomainService.isSavingsInterestPostingAtCurrentPeriodEnd()).thenReturn(false);
        when(configurationDomainService.retrieveFinancialYearBeginningMonth()).thenReturn(1);

        this.account = buildAccount();
        this.linkedAccount = mock(SavingsAccount.class);
        when(this.linkedAccount.isActive()).thenReturn(true);
        when(this.linkedAccount.clientId()).thenReturn(1L);
        when(this.depositAccountAssembler.assembleFrom(LINKED_ACCOUNT_ID, DepositAccountType.SAVINGS_DEPOSIT))
                .thenReturn(this.linkedAccount);

        // The database's id sequence: the interest posting and tax transactions get ids when the account is flushed.
        when(savingsAccountRepository.saveAndFlush(any(SavingsAccount.class))).thenAnswer(invocation -> {
            for (final SavingsAccountTransaction transaction : this.account.getTransactions()) {
                if (transaction.getId() == null) {
                    ReflectionTestUtils.setField(transaction, "id", this.nextId++);
                }
            }
            return invocation.getArgument(0);
        });
        // What reached the accounting bridge, from every journal-posting call.
        org.mockito.Mockito.doAnswer(invocation -> {
            final Map<String, Object> bridgeData = invocation.getArgument(0);
            @SuppressWarnings("unchecked")
            final List<Map<String, Object>> newTransactions = (List<Map<String, Object>>) bridgeData.get("newSavingsTransactions");
            this.journalledTransactions.addAll(newTransactions);
            return null;
        }).when(journalEntryWritePlatformService).createJournalEntriesForSavings(any());

        // A withdrawal and a transfer both take the amount out of the real account, as the real ones do.
        when(this.coreDomainService.handleWithdrawal(any(), any(), any(), any(), any(), anyBoolean(), anyBoolean()))
                .thenAnswer(invocation -> {
                    final BigDecimal amount = invocation.getArgument(3);
                    this.payoutLegs.add("withdrawal");
                    return takeOut(amount);
                });
        when(this.accountTransfersWritePlatformService.transferFunds(any(AccountTransferDTO.class))).thenAnswer(invocation -> {
            final AccountTransferDTO transfer = invocation.getArgument(0);
            this.payoutLegs.add("transfer");
            this.transfers.add(transfer);
            takeOut(transfer.getTransactionAmount());
            return 1L;
        });
        final DynamicDepositAccount reinvested = mock(DynamicDepositAccount.class);
        when(reinvested.getId()).thenReturn(808L);
        when(this.reinvestmentService.reinvest(any(), any(), any(), any())).thenReturn(reinvested);

        this.service = new AdvanclyDepositAccountDomainService(this.coreDomainService, savingsAccountRepository,
                savingsAccountTransactionRepository, journalEntryWritePlatformService, configurationDomainService,
                this.depositAccountAssembler, this.accountTransfersWritePlatformService, this.reinvestmentService,
                this.accountAssociationsReadPlatformService);
    }

    // ---- interest and withholding tax are written, and journalled, by the closure ----

    @Test
    void aMaturityClosureWritesAndJournalsTheInterestPostingAndTheWithholdingTaxWhenTaxIsWithheldAtMaturity() {
        withholdingTax(WithHoldTaxPostingType.MATURITY, true);

        this.service.handleDynamicDepositClosure(this.account, null, mock(AppUser.class), closeCommand(WITHDRAW),
                new java.util.HashMap<>());

        final SavingsAccountTransaction posting = single(SavingsAccountTransaction::isInterestPostingAndNotReversed);
        final SavingsAccountTransaction tax = single(SavingsAccountTransaction::isWithHoldTaxAndNotReversed);
        assertThat(tax.getAmount())
                .isEqualByComparingTo(posting.getAmount().multiply(new BigDecimal("0.1")).setScale(2, java.math.RoundingMode.HALF_UP));
        assertThat(journalled(SavingsAccountTransactionType.INTEREST_POSTING)).hasSize(1);
        assertThat(journalled(SavingsAccountTransactionType.WITHHOLD_TAX)).hasSize(1);
        assertThat(this.payoutAmounts).usingElementComparator(BigDecimal::compareTo)
                .containsExactly(PRINCIPAL.add(posting.getAmount()).subtract(tax.getAmount()));
        assertThat(this.account.getSummary().getAccountBalance()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    // Closing the account - early or not - is when tax configured to be withheld at maturity is written, as it is for
    // Fixed and Recurring Deposits.
    @Test
    void aPrematureClosureAlsoWritesAndJournalsTheTaxConfiguredToBeWithheldAtMaturity() {
        withholdingTax(WithHoldTaxPostingType.MATURITY, true);
        ReflectionTestUtils.setField(this.account.accountTermAndPreClosure(), "maturityDate", LocalDate.of(2026, 7, 1));

        this.service.handleDynamicDepositPreMatureClosure(this.account, null, mock(AppUser.class), closeCommand(WITHDRAW),
                new java.util.HashMap<>());

        final SavingsAccountTransaction posting = single(SavingsAccountTransaction::isInterestPostingAndNotReversed);
        final SavingsAccountTransaction tax = single(SavingsAccountTransaction::isWithHoldTaxAndNotReversed);
        assertThat(journalled(SavingsAccountTransactionType.WITHHOLD_TAX)).hasSize(1);
        assertThat(this.payoutAmounts).usingElementComparator(BigDecimal::compareTo)
                .containsExactly(PRINCIPAL.add(posting.getAmount()).subtract(tax.getAmount()));
        assertThat(this.account.getSummary().getAccountBalance()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void whenTaxIsWithheldAtEachInterestPostingTheClosureWritesExactlyOneTaxTransaction() {
        withholdingTax(WithHoldTaxPostingType.INTEREST_POSTING, true);

        this.service.handleDynamicDepositClosure(this.account, null, mock(AppUser.class), closeCommand(WITHDRAW),
                new java.util.HashMap<>());

        single(SavingsAccountTransaction::isWithHoldTaxAndNotReversed);
        assertThat(journalled(SavingsAccountTransactionType.WITHHOLD_TAX)).hasSize(1);
    }

    @Test
    void noTaxIsWrittenWhenWithholdingTaxIsOff() {
        withholdingTax(WithHoldTaxPostingType.MATURITY, false);

        this.service.handleDynamicDepositClosure(this.account, null, mock(AppUser.class), closeCommand(WITHDRAW),
                new java.util.HashMap<>());

        assertThat(this.account.getTransactions().stream().filter(SavingsAccountTransaction::isWithHoldTaxAndNotReversed)).isEmpty();
        assertThat(journalled(SavingsAccountTransactionType.WITHHOLD_TAX)).isEmpty();
    }

    // ---- reinvest principal only: the principal moves on, the interest less tax goes to the linked account or is paid
    // out ----

    @Test
    void reinvestingThePrincipalOnlySendsTheInterestLessTaxToTheLinkedAccountFirstThenMovesThePrincipalAndClosesTheAccount() {
        withholdingTax(WithHoldTaxPostingType.MATURITY, true);
        linkTo(LINKED_ACCOUNT_ID);
        final java.util.Map<String, Object> changes = new java.util.HashMap<>();

        this.service.handleDynamicDepositClosure(this.account, null, mock(AppUser.class), closeCommand(REINVEST_PRINCIPAL_ONLY), changes);

        final SavingsAccountTransaction posting = single(SavingsAccountTransaction::isInterestPostingAndNotReversed);
        final SavingsAccountTransaction tax = single(SavingsAccountTransaction::isWithHoldTaxAndNotReversed);
        final BigDecimal netInterest = posting.getAmount().subtract(tax.getAmount());
        // The interest leaves FIRST, so the engine's interest-first labelling books it as interest and the principal
        // withdrawal that follows as principal.
        assertThat(this.payoutLegs).containsExactly("transfer", "withdrawal");
        assertThat(this.payoutAmounts).usingElementComparator(BigDecimal::compareTo).containsExactly(netInterest, PRINCIPAL);
        assertThat(this.transfers.get(0).getToSavingsAccount()).isSameAs(this.linkedAccount);
        final ArgumentCaptor<BigDecimal> reinvested = ArgumentCaptor.forClass(BigDecimal.class);
        verify(this.reinvestmentService).reinvest(any(), reinvested.capture(), any(), any());
        assertThat(reinvested.getValue()).isEqualByComparingTo(PRINCIPAL);
        assertThat(changes).containsEntry("reinvestedDepositId", 808L);
        assertThat(this.account.getSummary().getAccountBalance()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(this.account.isClosed()).isTrue();
    }

    @Test
    void withoutALinkedAccountTheInterestLessTaxIsWithdrawnBeforeThePrincipalMovesOn() {
        withholdingTax(WithHoldTaxPostingType.MATURITY, true);

        this.service.handleDynamicDepositClosure(this.account, null, mock(AppUser.class), closeCommand(REINVEST_PRINCIPAL_ONLY),
                new java.util.HashMap<>());

        final SavingsAccountTransaction posting = single(SavingsAccountTransaction::isInterestPostingAndNotReversed);
        final SavingsAccountTransaction tax = single(SavingsAccountTransaction::isWithHoldTaxAndNotReversed);
        assertThat(this.payoutLegs).containsExactly("withdrawal", "withdrawal");
        assertThat(this.payoutAmounts).usingElementComparator(BigDecimal::compareTo)
                .containsExactly(posting.getAmount().subtract(tax.getAmount()), PRINCIPAL);
        verify(this.accountTransfersWritePlatformService, never()).transferFunds(any());
        assertThat(this.account.isClosed()).isTrue();
    }

    @Test
    void reinvestingPrincipalAndInterestStillMovesTheWholePayoutInOneWithdrawal() {
        withholdingTax(WithHoldTaxPostingType.MATURITY, true);
        linkTo(LINKED_ACCOUNT_ID);

        this.service.handleDynamicDepositClosure(this.account, null, mock(AppUser.class), closeCommand(REINVEST_PRINCIPAL_AND_INTEREST),
                new java.util.HashMap<>());

        final SavingsAccountTransaction posting = single(SavingsAccountTransaction::isInterestPostingAndNotReversed);
        final SavingsAccountTransaction tax = single(SavingsAccountTransaction::isWithHoldTaxAndNotReversed);
        assertThat(this.payoutLegs).containsExactly("withdrawal");
        assertThat(this.payoutAmounts).usingElementComparator(BigDecimal::compareTo)
                .containsExactly(PRINCIPAL.add(posting.getAmount()).subtract(tax.getAmount()));
        verify(this.accountTransfersWritePlatformService, never()).transferFunds(any());
    }

    @Test
    void whenThereIsNoInterestToSendOnlyThePrincipalMovesOn() {
        ReflectionTestUtils.setField(this.account, "nominalAnnualInterestRate", BigDecimal.ZERO);
        linkTo(LINKED_ACCOUNT_ID);

        this.service.handleDynamicDepositClosure(this.account, null, mock(AppUser.class), closeCommand(REINVEST_PRINCIPAL_ONLY),
                new java.util.HashMap<>());

        assertThat(this.payoutLegs).containsExactly("withdrawal");
        assertThat(this.payoutAmounts).usingElementComparator(BigDecimal::compareTo).containsExactly(PRINCIPAL);
        verify(this.accountTransfersWritePlatformService, never()).transferFunds(any());
        assertThat(this.account.isClosed()).isTrue();
    }

    // ---- helpers ----

    private void linkTo(final Long linkedAccountId) {
        final PortfolioAccountData linked = mock(PortfolioAccountData.class);
        when(linked.getId()).thenReturn(linkedAccountId);
        when(this.accountAssociationsReadPlatformService.retriveSavingsLinkedAssociation(ACCOUNT_ID)).thenReturn(linked);
    }

    private SavingsAccountTransaction takeOut(final BigDecimal amount) {
        final SavingsAccountTransaction withdrawal = SavingsAccountTransaction.withdrawal(this.account, this.office, null, CLOSED_DATE,
                Money.of(CURRENCY, amount), "test-ref");
        ReflectionTestUtils.setField(withdrawal, "id", this.nextId++);
        this.account.addTransaction(withdrawal);
        ReflectionTestUtils.setField(this.account.getSummary(), "accountBalance",
                this.account.getSummary().getAccountBalance().subtract(amount));
        this.payoutAmounts.add(amount);
        return withdrawal;
    }

    private SavingsAccountTransaction single(final java.util.function.Predicate<SavingsAccountTransaction> predicate) {
        final List<SavingsAccountTransaction> found = this.account.getTransactions().stream().filter(predicate).toList();
        assertThat(found).hasSize(1);
        return found.get(0);
    }

    private List<Map<String, Object>> journalled(final SavingsAccountTransactionType type) {
        return this.journalledTransactions.stream()
                .filter(transaction -> ((SavingsAccountTransactionEnumData) transaction.get("type")).getTransactionTypeEnum() == type)
                .toList();
    }

    private void withholdingTax(final WithHoldTaxPostingType postingType, final boolean withHoldTax) {
        final TaxComponent component = mock(TaxComponent.class);
        when(component.getApplicablePercentage(any())).thenReturn(new BigDecimal("10"));
        final TaxGroupMappings mappings = mock(TaxGroupMappings.class);
        when(mappings.occursOnDayFromAndUpToAndIncluding(any())).thenReturn(true);
        when(mappings.getTaxComponent()).thenReturn(component);
        final TaxGroup taxGroup = mock(TaxGroup.class);
        when(taxGroup.getTaxGroupMappings()).thenReturn(Set.of(mappings));
        final DepositAccountTermAndPreClosure term = this.account.accountTermAndPreClosure();
        ReflectionTestUtils.setField(term, "withHoldTaxPostingType", postingType.getValue());
        ReflectionTestUtils.setField(this.account, "taxGroup", taxGroup);
        this.account.setWithHoldTax(withHoldTax);
    }

    private static JsonCommand closeCommand(final int onAccountClosureId) {
        final JsonObject payload = new JsonObject();
        payload.addProperty("dateFormat", "dd MMMM yyyy");
        payload.addProperty("locale", "en");
        payload.addProperty("closedOnDate", "20 January 2026");
        payload.addProperty("onAccountClosureId", onAccountClosureId);
        final String json = payload.toString();
        return JsonCommand.fromExistingCommand(null, json, JsonParser.parseString(json), new FromJsonHelper(), null, null, null, null, null,
                null, ACCOUNT_ID, null, null, null, null, null, null, null);
    }

    private DynamicDepositAccount buildAccount() {
        final DynamicDepositAccount newAccount = createInstance(DynamicDepositAccount.class);
        ReflectionTestUtils.setField(newAccount, "id", ACCOUNT_ID);
        ReflectionTestUtils.setField(newAccount, "status", SavingsAccountStatusType.ACTIVE.getValue());
        ReflectionTestUtils.setField(newAccount, "currency", CURRENCY);
        ReflectionTestUtils.setField(newAccount, "activatedOnDate", ACTIVATION_DATE);
        ReflectionTestUtils.setField(newAccount, "nominalAnnualInterestRate", BigDecimal.valueOf(2));
        ReflectionTestUtils.setField(newAccount, "interestCompoundingPeriodType", 8);
        ReflectionTestUtils.setField(newAccount, "interestPostingPeriodType", 4);
        ReflectionTestUtils.setField(newAccount, "interestCalculationType", 1);
        ReflectionTestUtils.setField(newAccount, "interestCalculationDaysInYearType", 365);
        ReflectionTestUtils.setField(newAccount, "savingsAccountTransactions", new ArrayList<SavingsAccountTransaction>());
        ReflectionTestUtils.setField(newAccount, "charges", new HashSet<>());

        final DepositAccountTermAndPreClosure term = DepositAccountTermAndPreClosure.createNew(null, null, null, null, null, null, null,
                null, null, null, false, null, null);
        term.updateMaturityDetails(null, MATURITY_DATE);
        term.updateDepositAmount(PRINCIPAL);
        ReflectionTestUtils.setField(newAccount, "accountTermAndPreClosure", term);
        ReflectionTestUtils.setField(newAccount, "summary",
                new SavingsAccountSummaryTestBuilder().withAccountBalance(PRINCIPAL).withTotalDeposits(PRINCIPAL).build());
        ReflectionTestUtils.setField(newAccount, "savingsAccountTransactionSummaryWrapper", new SavingsAccountTransactionSummaryWrapper());
        final AccountTransfersReadPlatformService transfersRead = mock(AccountTransfersReadPlatformService.class);
        when(transfersRead.fetchPostInterestTransactionIds(anyLong())).thenReturn(List.of());
        ReflectionTestUtils.setField(newAccount, "savingsHelper",
                new SavingsHelper(transfersRead, mock(SavingsInterestReadPlatformService.class)));

        this.office = mock(Office.class);
        when(this.office.getId()).thenReturn(1L);
        final Client client = mock(Client.class);
        when(client.getOffice()).thenReturn(this.office);
        when(client.officeId()).thenReturn(1L);
        when(client.getId()).thenReturn(1L);
        ReflectionTestUtils.setField(newAccount, "client", client);
        final SavingsProduct product = mock(SavingsProduct.class);
        when(product.getId()).thenReturn(10L);
        ReflectionTestUtils.setField(newAccount, "product", product);
        newAccount.setDynamicDetail(DepositAccountDynamicDetail.createNew(newAccount, true, false));

        final SavingsAccountTransaction openingDeposit = new SavingsAccountTransactionTestBuilder().withId(1L)
                .withSavingsAccount(newAccount).withType(SavingsAccountTransactionType.DEPOSIT).withDate(ACTIVATION_DATE)
                .withAmount(PRINCIPAL).build();
        newAccount.getTransactions().add(openingDeposit);
        final DepositAccountDynamicRateHistoryRepository rateHistoryRepository = mock(DepositAccountDynamicRateHistoryRepository.class);
        when(rateHistoryRepository.findByAccountIdOrderByTransactionDateAscIdAsc(ACCOUNT_ID))
                .thenReturn(List.of(DepositAccountDynamicRateHistory.createNew(newAccount, openingDeposit, ACTIVATION_DATE,
                        DynamicDepositRateHistoryEventType.ACCOUNT_ACTIVATION, PRINCIPAL, 12, 2, null, null, BigDecimal.valueOf(2),
                        BigDecimal.valueOf(2), DynamicDepositRateSource.INTEREST_RATE_CHART)));
        final ApplicationContext applicationContext = mock(ApplicationContext.class);
        when(applicationContext.getBean(DepositAccountDynamicRateHistoryRepository.class)).thenReturn(rateHistoryRepository);
        // An early closure asks whether a cumulative forfeiture rule applies; none does here.
        final CumulativeInterestForfeitureService forfeitureService = mock(CumulativeInterestForfeitureService.class);
        when(applicationContext.getBean(CumulativeInterestForfeitureService.class)).thenReturn(forfeitureService);
        ReflectionTestUtils.setField(com.advancly.fineract.portfolio.savings.service.DynamicDepositServiceLocator.class,
                "applicationContext", applicationContext);
        return newAccount;
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
