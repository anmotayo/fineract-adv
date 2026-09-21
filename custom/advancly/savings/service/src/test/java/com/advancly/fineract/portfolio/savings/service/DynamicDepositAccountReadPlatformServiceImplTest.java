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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

import com.advancly.fineract.portfolio.savings.data.DynamicDepositInterestSummaryData;
import com.advancly.fineract.portfolio.savings.domain.DepositAccountDynamicRateHistoryRepository;
import com.advancly.fineract.portfolio.savings.domain.DepositAccountInterestWithdrawal;
import com.advancly.fineract.portfolio.savings.domain.DepositAccountInterestWithdrawalRepository;
import com.advancly.fineract.portfolio.savings.domain.DepositInterestChargeApplicationRepository;
import com.advancly.fineract.portfolio.savings.testutil.MoneyHelperInitializer;
import com.advancly.fineract.portfolio.savings.testutil.SavingsAccountTransactionTestBuilder;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.apache.fineract.infrastructure.security.service.PlatformSecurityContext;
import org.apache.fineract.portfolio.savings.DepositAccountType;
import org.apache.fineract.portfolio.savings.domain.SavingsAccount;
import org.apache.fineract.portfolio.savings.domain.SavingsAccountRepositoryWrapper;
import org.apache.fineract.portfolio.savings.domain.SavingsAccountSummary;
import org.apache.fineract.portfolio.savings.domain.SavingsAccountTransaction;
import org.apache.fineract.portfolio.savings.service.DepositAccountInterestRateChartReadPlatformService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class DynamicDepositAccountReadPlatformServiceImplTest {

    private static final Long ACCOUNT_ID = 1L;

    @Mock
    private PlatformSecurityContext context;
    @Mock
    private JdbcTemplate jdbcTemplate;
    @Mock
    private DynamicDepositProductReadPlatformService dynamicDepositProductReadPlatformService;
    @Mock
    private SavingsAccountRepositoryWrapper savingsAccountRepository;
    @Mock
    private DepositAccountInterestWithdrawalRepository interestWithdrawalRepository;
    @Mock
    private DepositAccountDynamicRateHistoryRepository rateHistoryRepository;
    @Mock
    private DepositInterestChargeApplicationRepository interestChargeApplicationRepository;
    @Mock
    private DepositAccountInterestRateChartReadPlatformService accountChartReadPlatformService;
    @Mock
    private SavingsAccount account;
    @Mock
    private SavingsAccountSummary summary;

    private DynamicDepositAccountReadPlatformServiceImpl service;

    @BeforeEach
    void setUp() {
        MoneyHelperInitializer.initialize();
        this.service = new DynamicDepositAccountReadPlatformServiceImpl(this.context, this.jdbcTemplate,
                this.dynamicDepositProductReadPlatformService, this.savingsAccountRepository, this.interestWithdrawalRepository,
                this.rateHistoryRepository, this.interestChargeApplicationRepository, this.accountChartReadPlatformService);
        lenient().when(this.savingsAccountRepository.findOneWithNotFoundDetection(eq(ACCOUNT_ID), eq(DepositAccountType.DYNAMIC_DEPOSIT)))
                .thenReturn(this.account);
        lenient().when(this.account.getSummary()).thenReturn(this.summary);
        lenient().when(this.rateHistoryRepository.findByAccountIdOrderByTransactionDateAscIdAsc(ACCOUNT_ID)).thenReturn(List.of());
    }

    @Test
    void interestWithdrawnExcludesMarkerRowsWhoseLinkedTransactionWasReversed() {
        when(this.summary.getTotalInterestEarned()).thenReturn(BigDecimal.valueOf(100));
        when(this.summary.getTotalInterestPosted()).thenReturn(BigDecimal.valueOf(80));
        when(this.summary.getTotalWithholdTax()).thenReturn(BigDecimal.valueOf(5));

        final SavingsAccountTransaction activePosting = interestPosting(10L, LocalDate.of(2026, 1, 31), false);
        final SavingsAccountTransaction activeWithdrawal = withdrawal(11L, LocalDate.of(2026, 2, 1), false);
        final DepositAccountInterestWithdrawal stillCountedRow = DepositAccountInterestWithdrawal.createNew(this.account, activePosting,
                activeWithdrawal, BigDecimal.valueOf(20), LocalDate.of(2026, 2, 1));

        // Simulates Task 3's correction branch: the original posting transaction that this old marker row points at
        // was later reversed (and replaced by a new posting transaction with a different id), so this row must no
        // longer be counted.
        final SavingsAccountTransaction reversedPosting = interestPosting(12L, LocalDate.of(2026, 3, 31), true);
        final SavingsAccountTransaction reversedWithdrawal = withdrawal(13L, LocalDate.of(2026, 4, 1), false);
        final DepositAccountInterestWithdrawal staleRow = DepositAccountInterestWithdrawal.createNew(this.account, reversedPosting,
                reversedWithdrawal, BigDecimal.valueOf(15), LocalDate.of(2026, 4, 1));

        when(this.interestWithdrawalRepository.findByAccountIdOrderByTransactionDateAscIdAsc(ACCOUNT_ID))
                .thenReturn(List.of(stillCountedRow, staleRow));

        final DynamicDepositInterestSummaryData summaryData = this.service.retrieveInterestSummary(ACCOUNT_ID);

        final BigDecimal interestWithdrawn = (BigDecimal) ReflectionTestUtils.getField(summaryData, "interestWithdrawn");
        assertThat(interestWithdrawn).isEqualByComparingTo("20");
    }

    @Test
    void netInterestIsLifeToDateInterestPostedMinusWithholdingTaxAndCharges() {
        when(this.summary.getTotalInterestEarned()).thenReturn(BigDecimal.valueOf(120));
        when(this.summary.getTotalInterestPosted()).thenReturn(BigDecimal.valueOf(100));
        when(this.summary.getTotalWithholdTax()).thenReturn(BigDecimal.valueOf(10));
        when(this.interestWithdrawalRepository.findByAccountIdOrderByTransactionDateAscIdAsc(ACCOUNT_ID)).thenReturn(List.of());

        final DynamicDepositInterestSummaryData summaryData = this.service.retrieveInterestSummary(ACCOUNT_ID);

        // netInterest = interestPosted - withholdingTax - interestBasedCharges (interestBasedCharges is always zero
        // this phase), not the current unposted accrual (totalInterestForPeriod = 120 - 100 = 20, which would make
        // netInterest negative here and is the defect this test guards against).
        final BigDecimal netInterest = (BigDecimal) ReflectionTestUtils.getField(summaryData, "netInterest");
        assertThat(netInterest).isEqualByComparingTo("90");
        assertThat(netInterest).isNotNegative();
    }

    @Test
    void interestBasedChargesReportsThePostedTotalAndIsSubtractedFromNetInterest() {
        when(this.summary.getTotalInterestPosted()).thenReturn(BigDecimal.valueOf(500));
        when(this.summary.getTotalWithholdTax()).thenReturn(BigDecimal.valueOf(50));
        when(this.interestWithdrawalRepository.findByAccountIdOrderByTransactionDateAscIdAsc(ACCOUNT_ID)).thenReturn(List.of());
        lenient().when(this.interestChargeApplicationRepository.sumActiveAppliedAmountForAccount(ACCOUNT_ID))
                .thenReturn(new BigDecimal("30"));

        final DynamicDepositInterestSummaryData summary = this.service.retrieveInterestSummary(ACCOUNT_ID);

        assertThat(summary.interestBasedCharges()).isEqualByComparingTo("30");
        assertThat(summary.interestBasedChargePostedDerived()).isEqualByComparingTo("30");
        // 500 posted - 50 withholding tax - 30 interest-based charge
        assertThat(summary.netInterest()).isEqualByComparingTo("420");
    }

    @Test
    void interestBasedChargesIsZeroWhenNoChargeHasEverBeenApplied() {
        when(this.summary.getTotalInterestPosted()).thenReturn(BigDecimal.valueOf(500));
        when(this.summary.getTotalWithholdTax()).thenReturn(BigDecimal.valueOf(50));
        when(this.interestWithdrawalRepository.findByAccountIdOrderByTransactionDateAscIdAsc(ACCOUNT_ID)).thenReturn(List.of());
        lenient().when(this.interestChargeApplicationRepository.sumActiveAppliedAmountForAccount(ACCOUNT_ID)).thenReturn(BigDecimal.ZERO);

        final DynamicDepositInterestSummaryData summary = this.service.retrieveInterestSummary(ACCOUNT_ID);

        assertThat(summary.interestBasedCharges()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(summary.netInterest()).isEqualByComparingTo("450");
    }

    private SavingsAccountTransaction interestPosting(final Long id, final LocalDate date, final boolean reversed) {
        final SavingsAccountTransactionTestBuilder builder = new SavingsAccountTransactionTestBuilder().withId(id)
                .withSavingsAccount(this.account).withDate(date);
        if (reversed) {
            builder.reversed();
        }
        return builder.build();
    }

    private SavingsAccountTransaction withdrawal(final Long id, final LocalDate date, final boolean reversed) {
        final SavingsAccountTransactionTestBuilder builder = new SavingsAccountTransactionTestBuilder().withId(id)
                .withSavingsAccount(this.account).withDate(date);
        if (reversed) {
            builder.reversed();
        }
        return builder.build();
    }
}
