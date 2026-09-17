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
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

import com.advancly.fineract.portfolio.savings.domain.SavingsAccountInterestCharge;
import com.advancly.fineract.portfolio.savings.domain.SavingsAccountInterestChargeRepository;
import com.advancly.fineract.portfolio.savings.domain.DepositProductDynamicDetail;
import com.advancly.fineract.portfolio.savings.domain.DepositProductDynamicDetailRepository;
import com.advancly.fineract.portfolio.savings.domain.SavingsProductEarlyWithdrawalCharge;
import com.advancly.fineract.portfolio.savings.domain.SavingsProductEarlyWithdrawalChargeRepository;
import com.advancly.fineract.portfolio.savings.domain.DynamicDepositAccount;
import com.advancly.fineract.portfolio.savings.testutil.MoneyHelperInitializer;
import com.advancly.fineract.portfolio.savings.testutil.SavingsAccountTransactionTestBuilder;
import java.lang.reflect.Constructor;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.apache.fineract.organisation.monetary.domain.MonetaryCurrency;
import org.apache.fineract.portfolio.charge.domain.Charge;
import org.apache.fineract.portfolio.charge.domain.ChargeCalculationType;
import org.apache.fineract.portfolio.savings.SavingsAccountTransactionType;
import org.apache.fineract.portfolio.savings.SavingsPeriodFrequencyType;
import org.apache.fineract.portfolio.savings.domain.DepositAccountTermAndPreClosure;
import org.apache.fineract.portfolio.savings.domain.DepositPreClosureDetail;
import org.apache.fineract.portfolio.savings.domain.DepositTermDetail;
import org.apache.fineract.portfolio.savings.domain.SavingsAccountCharge;
import org.apache.fineract.portfolio.savings.domain.SavingsAccountSummary;
import org.apache.fineract.portfolio.savings.domain.SavingsAccountTransaction;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationContext;
import org.springframework.test.util.ReflectionTestUtils;

class DynamicDepositEarlyWithdrawalChargeServiceTest {

    private static final MonetaryCurrency CURRENCY = new MonetaryCurrency("USD", 2, null);
    private static final Long PRODUCT_ID = 42L;
    private static final Long CHARGE_ID = 7L;
    private static final LocalDate SUBMITTED_ON = LocalDate.of(2026, 1, 1);
    private static final LocalDate BEFORE_MATURITY = LocalDate.of(2026, 4, 1);
    private static final LocalDate AFTER_MATURITY = LocalDate.of(2026, 8, 1);

    private final List<SavingsAccountInterestCharge> savedRows = new ArrayList<>();

    private SavingsAccountInterestChargeRepository interestChargeRepository;
    private SavingsProductEarlyWithdrawalChargeRepository productEarlyWithdrawalChargeRepository;
    private DepositProductDynamicDetailRepository productDynamicDetailRepository;
    private DynamicDepositEarlyWithdrawalChargeService service;

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
        lenient().when(this.interestChargeRepository.sumPendingChargeAmount(anyLong())).thenAnswer(invocation -> this.savedRows.stream()
                .map(SavingsAccountInterestCharge::chargeAmount).reduce(BigDecimal.ZERO, BigDecimal::add));

        this.productEarlyWithdrawalChargeRepository = mock(SavingsProductEarlyWithdrawalChargeRepository.class);
        lenient().when(this.productEarlyWithdrawalChargeRepository.findBySavingsProductId(PRODUCT_ID))
                .thenReturn(List.of(SavingsProductEarlyWithdrawalCharge.createNew(PRODUCT_ID, CHARGE_ID)));

        this.productDynamicDetailRepository = mock(DepositProductDynamicDetailRepository.class);
        lenient().when(this.productDynamicDetailRepository.findByProductId(PRODUCT_ID)).thenReturn(Optional.of(productDetail(true)));

        this.service = new DynamicDepositEarlyWithdrawalChargeService(this.interestChargeRepository,
                this.productEarlyWithdrawalChargeRepository, this.productDynamicDetailRepository);
    }

    @Test
    void aWithdrawalBeforeMaturityCreatesAPendingRowComputedFromTheUnpostedInterest() {
        final DynamicDepositAccount account = account(new BigDecimal("500"), new BigDecimal("200"), accountCharge(new BigDecimal("10")));

        this.service.recordIfApplicable(account, withdrawal(BEFORE_MATURITY));

        assertThat(this.savedRows).hasSize(1);
        final SavingsAccountInterestCharge row = this.savedRows.get(0);
        // basis = 500 earned - 200 posted = 300; 10% of 300 = 30
        assertThat(row.interestAmountBasis()).isEqualByComparingTo("300");
        assertThat(row.chargePercentage()).isEqualByComparingTo("10");
        assertThat(row.chargeAmount()).isEqualByComparingTo("30");
        assertThat(row.interestPeriodEndDate()).isEqualTo(BEFORE_MATURITY);
        assertThat(row.isPending()).isTrue();
        assertThat(account.interestBasedChargeDerived()).isEqualByComparingTo("30");
    }

    @Test
    void aWithdrawalOnOrAfterMaturityCreatesNothing() {
        final DynamicDepositAccount account = account(new BigDecimal("500"), new BigDecimal("200"), accountCharge(new BigDecimal("10")));

        this.service.recordIfApplicable(account, withdrawal(AFTER_MATURITY));

        assertThat(this.savedRows).isEmpty();
    }

    @Test
    void nothingIsRecordedWhenTheProductHasThePenaltyDisabled() {
        lenient().when(this.productDynamicDetailRepository.findByProductId(PRODUCT_ID)).thenReturn(Optional.of(productDetail(false)));
        final DynamicDepositAccount account = account(new BigDecimal("500"), new BigDecimal("200"), accountCharge(new BigDecimal("10")));

        this.service.recordIfApplicable(account, withdrawal(BEFORE_MATURITY));

        assertThat(this.savedRows).isEmpty();
    }

    @Test
    void nothingIsRecordedWhenTheProductSelectsNoEarlyWithdrawalCharge() {
        lenient().when(this.productEarlyWithdrawalChargeRepository.findBySavingsProductId(PRODUCT_ID)).thenReturn(List.of());
        final DynamicDepositAccount account = account(new BigDecimal("500"), new BigDecimal("200"), accountCharge(new BigDecimal("10")));

        this.service.recordIfApplicable(account, withdrawal(BEFORE_MATURITY));

        assertThat(this.savedRows).isEmpty();
    }

    @Test
    void aRowIsStillRecordedWithTheAuthoritativePercentageWhenTheBasisIsStaleOrZero() {
        // Nothing has been calculated since the last posting, so the summary reports no unposted interest. The row
        // must still be written: charge_percentage is the authoritative input, and posting recomputes the amount from
        // it against the period's real gross interest. Skipping here would silently drop the penalty.
        final DynamicDepositAccount account = account(new BigDecimal("200"), new BigDecimal("200"), accountCharge(new BigDecimal("10")));

        this.service.recordIfApplicable(account, withdrawal(BEFORE_MATURITY));

        assertThat(this.savedRows).hasSize(1);
        assertThat(this.savedRows.get(0).chargePercentage()).isEqualByComparingTo("10");
        assertThat(this.savedRows.get(0).interestAmountBasis()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(this.savedRows.get(0).chargeAmount()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(this.savedRows.get(0).isPending()).isTrue();
    }

    @Test
    void theAccountLevelPercentageOverrideWinsOverTheChargeDefinitionAmount() {
        final SavingsAccountCharge override = accountCharge(new BigDecimal("25"));
        final DynamicDepositAccount account = account(new BigDecimal("500"), new BigDecimal("200"), override);

        this.service.recordIfApplicable(account, withdrawal(BEFORE_MATURITY));

        assertThat(this.savedRows.get(0).chargePercentage()).isEqualByComparingTo("25");
        assertThat(this.savedRows.get(0).chargeAmount()).isEqualByComparingTo("75");
    }

    @Test
    void theProductChargePercentageIsUsedWhenTheAccountChargeCarriesNoOverride() {
        final SavingsAccountCharge noOverride = accountCharge(null);
        final DynamicDepositAccount account = account(new BigDecimal("500"), new BigDecimal("200"), noOverride);

        this.service.recordIfApplicable(account, withdrawal(BEFORE_MATURITY));

        // falls back to Charge#getAmount(), stubbed at 4%
        assertThat(this.savedRows.get(0).chargePercentage()).isEqualByComparingTo("4");
        assertThat(this.savedRows.get(0).chargeAmount()).isEqualByComparingTo("12");
    }

    @Test
    void theChargeIsCappedAtTheAvailableInterestBasis() {
        final DynamicDepositAccount account = account(new BigDecimal("500"), new BigDecimal("200"), accountCharge(new BigDecimal("150")));

        this.service.recordIfApplicable(account, withdrawal(BEFORE_MATURITY));

        // 150% of 300 would be 450, capped at the 300 basis so principal can never be reached
        assertThat(this.savedRows.get(0).chargeAmount()).isEqualByComparingTo("300");
    }

    @Test
    void aReversedWithdrawalTransactionIsIgnored() {
        final DynamicDepositAccount account = account(new BigDecimal("500"), new BigDecimal("200"), accountCharge(new BigDecimal("10")));
        final SavingsAccountTransaction reversed = new SavingsAccountTransactionTestBuilder().withId(11L).withSavingsAccount(account)
                .withType(SavingsAccountTransactionType.WITHDRAWAL).withDate(BEFORE_MATURITY).withAmount(BigDecimal.valueOf(100)).reversed()
                .build();

        this.service.recordIfApplicable(account, reversed);

        assertThat(this.savedRows).isEmpty();
    }

    @Test
    void theSettlementWithdrawalOfAPrematureClosureIsSkipped() {
        // Premature closure computes and charges its own penalty as part of the period's single capped interest-based
        // charge, then writes the row itself (already applied) against this very withdrawal. Recording a pending row
        // here too would duplicate a charge that was already taken - and nothing would ever apply or clear it, since a
        // closed account never posts interest again.
        final DynamicDepositAccount account = account(new BigDecimal("500"), new BigDecimal("200"), accountCharge(new BigDecimal("10")));
        final ApplicationContext applicationContext = mock(ApplicationContext.class);
        lenient().when(applicationContext.getBean(DynamicDepositEarlyWithdrawalChargeService.class)).thenReturn(this.service);
        ReflectionTestUtils.setField(DynamicDepositServiceLocator.class, "applicationContext", applicationContext);

        account.prepareClosureSettlement(BEFORE_MATURITY);
        this.service.recordIfApplicable(account, withdrawal(BEFORE_MATURITY));

        assertThat(this.savedRows).isEmpty();

        // ...and the suppression ends with the settlement: an ordinary early withdrawal afterwards records as usual.
        account.completeClosureSettlement(null);
        this.service.recordIfApplicable(account, withdrawal(BEFORE_MATURITY));

        assertThat(this.savedRows).hasSize(1);
    }

    @Test
    void aNonPercentOfInterestAccountChargeDoesNotQualify() {
        final SavingsAccountCharge flat = accountCharge(new BigDecimal("10"));
        ReflectionTestUtils.setField(flat, "chargeCalculation", ChargeCalculationType.FLAT.getValue());
        lenient().when(flat.getCharge().getChargeCalculation()).thenReturn(ChargeCalculationType.FLAT.getValue());
        final DynamicDepositAccount account = account(new BigDecimal("500"), new BigDecimal("200"), flat);

        this.service.recordIfApplicable(account, withdrawal(BEFORE_MATURITY));

        assertThat(this.savedRows).isEmpty();
    }

    private DepositProductDynamicDetail productDetail(final boolean earlyWithdrawalPenaltyEnabled) {
        return DepositProductDynamicDetail.createNew(null, true, false, earlyWithdrawalPenaltyEnabled);
    }

    private SavingsAccountCharge accountCharge(final BigDecimal accountPercentageOverride) {
        final Charge definition = mock(Charge.class);
        lenient().when(definition.getId()).thenReturn(CHARGE_ID);
        lenient().when(definition.getAmount()).thenReturn(new BigDecimal("4"));
        lenient().when(definition.isPenalty()).thenReturn(true);
        lenient().when(definition.isActive()).thenReturn(true);
        lenient().when(definition.getChargeCalculation()).thenReturn(ChargeCalculationType.PERCENT_OF_INTEREST.getValue());

        final SavingsAccountCharge accountCharge = mock(SavingsAccountCharge.class);
        lenient().when(accountCharge.getCharge()).thenReturn(definition);
        lenient().when(accountCharge.getPercentage()).thenReturn(accountPercentageOverride);
        lenient().when(accountCharge.isActive()).thenReturn(true);
        lenient().when(accountCharge.isPenaltyCharge()).thenReturn(true);
        return accountCharge;
    }

    private SavingsAccountTransaction withdrawal(final LocalDate date) {
        return new SavingsAccountTransactionTestBuilder().withId(11L).withType(SavingsAccountTransactionType.WITHDRAWAL).withDate(date)
                .withAmount(BigDecimal.valueOf(100)).build();
    }

    private DynamicDepositAccount account(final BigDecimal totalInterestEarned, final BigDecimal totalInterestPosted,
            final SavingsAccountCharge... charges) {
        final DepositAccountTermAndPreClosure term = DepositAccountTermAndPreClosure.createNew(
                DepositPreClosureDetail.createFrom(false, null, null),
                DepositTermDetail.createFrom(6, 6, SavingsPeriodFrequencyType.MONTHS, SavingsPeriodFrequencyType.MONTHS, null, null), null,
                BigDecimal.valueOf(100000), null, null, 6, SavingsPeriodFrequencyType.MONTHS, null, null, false, null, null);

        final DynamicDepositAccount account = createInstance(DynamicDepositAccount.class);
        ReflectionTestUtils.setField(account, "id", 1L);
        ReflectionTestUtils.setField(account, "currency", CURRENCY);
        ReflectionTestUtils.setField(account, "submittedOnDate", SUBMITTED_ON);
        ReflectionTestUtils.setField(account, "accountTermAndPreClosure", term);
        ReflectionTestUtils.setField(account, "savingsAccountTransactions", new ArrayList<SavingsAccountTransaction>());
        ReflectionTestUtils.setField(account, "transactions", new ArrayList<SavingsAccountTransaction>());
        ReflectionTestUtils.setField(account, "charges", new HashSet<>(Set.of(charges)));

        final SavingsAccountSummary summary = createInstance(SavingsAccountSummary.class);
        ReflectionTestUtils.setField(summary, "totalInterestEarned", totalInterestEarned);
        ReflectionTestUtils.setField(summary, "totalInterestPosted", totalInterestPosted);
        ReflectionTestUtils.setField(account, "summary", summary);

        final org.apache.fineract.portfolio.savings.domain.SavingsProduct product = mock(
                org.apache.fineract.portfolio.savings.domain.SavingsProduct.class);
        lenient().when(product.getId()).thenReturn(PRODUCT_ID);
        ReflectionTestUtils.setField(account, "product", product);

        account.updateMaturityDate();
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
