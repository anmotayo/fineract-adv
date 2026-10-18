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

import com.advancly.fineract.portfolio.savings.testutil.MoneyHelperInitializer;
import com.advancly.fineract.portfolio.savings.testutil.SavingsAccountTransactionTestBuilder;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.apache.fineract.organisation.monetary.domain.MonetaryCurrency;
import org.apache.fineract.portfolio.savings.SavingsAccountTransactionType;
import org.apache.fineract.portfolio.savings.domain.SavingsAccountTransaction;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * The amount invested is the principal that is still in the account: deposits less the PRINCIPAL part of withdrawals. A
 * withdrawal takes interest first (what has been posted, less the tax withheld on it and any interest forfeited), and
 * only the rest of it is principal. Subtracting every withdrawal in full - the old rule - drove a closed account to a
 * negative amount and understated the principal whenever interest had been paid out.
 */
class DynamicDepositInvestedAmountCalculatorTest {

    private static final MonetaryCurrency CURRENCY = new MonetaryCurrency("USD", 2, null);
    private static final LocalDate JAN_1 = LocalDate.of(2026, 1, 1);
    private static final LocalDate MAR_1 = LocalDate.of(2026, 3, 1);
    private static final LocalDate APR_1 = LocalDate.of(2026, 4, 1);

    private final List<SavingsAccountTransaction> transactions = new ArrayList<>();
    private long nextId = 1;

    @BeforeEach
    void setUp() {
        MoneyHelperInitializer.initialize();
        this.transactions.clear();
        this.nextId = 1;
    }

    @Test
    void depositsAreTheInvestedAmount() {
        add(SavingsAccountTransactionType.DEPOSIT, JAN_1, "1000");
        final SavingsAccountTransaction topUp = add(SavingsAccountTransactionType.DEPOSIT, MAR_1, "500");

        assertThat(invested(topUp)).isEqualByComparingTo("1500");
    }

    @Test
    void aWithdrawalOfPrincipalReducesIt() {
        add(SavingsAccountTransactionType.DEPOSIT, JAN_1, "1000");
        final SavingsAccountTransaction withdrawal = add(SavingsAccountTransactionType.WITHDRAWAL, MAR_1, "400");

        assertThat(invested(withdrawal)).isEqualByComparingTo("600");
    }

    // The closed account that read -24.93: 1000 invested, 24.93 interest, and a closing withdrawal of 1024.93.
    @Test
    void closingWithdrawalOfPrincipalAndInterestLeavesNothingInvestedNotANegativeAmount() {
        add(SavingsAccountTransactionType.DEPOSIT, JAN_1, "1000");
        add(SavingsAccountTransactionType.INTEREST_POSTING, MAR_1, "24.93");
        final SavingsAccountTransaction closing = add(SavingsAccountTransactionType.WITHDRAWAL, MAR_1, "1024.93");

        assertThat(invested(closing)).isEqualByComparingTo("0");
    }

    @Test
    void interestPaidOutLessTaxDoesNotReduceThePrincipal() {
        add(SavingsAccountTransactionType.DEPOSIT, JAN_1, "1000");
        add(SavingsAccountTransactionType.INTEREST_POSTING, MAR_1, "24.93");
        add(SavingsAccountTransactionType.WITHHOLD_TAX, MAR_1, "2.49");
        // transferInterestToSavings: the interest less tax, 22.44, is paid out to the linked account.
        final SavingsAccountTransaction interestTransfer = add(SavingsAccountTransactionType.WITHDRAWAL, MAR_1, "22.44");

        assertThat(invested(interestTransfer)).isEqualByComparingTo("1000");
    }

    @Test
    void afterInterestTransfersTheWholePrincipalWithdrawnLeavesNothing() {
        add(SavingsAccountTransactionType.DEPOSIT, JAN_1, "1000");
        add(SavingsAccountTransactionType.INTEREST_POSTING, MAR_1, "24.93");
        add(SavingsAccountTransactionType.WITHHOLD_TAX, MAR_1, "2.49");
        add(SavingsAccountTransactionType.WITHDRAWAL, MAR_1, "22.44");
        final SavingsAccountTransaction principal = add(SavingsAccountTransactionType.WITHDRAWAL, APR_1, "1000");

        // No phantom interest is left to be mislabelled: the tax already took its share of the posting out.
        assertThat(invested(principal)).isEqualByComparingTo("0");
    }

    @Test
    void aWithdrawalLargerThanTheInterestTakesTheRestFromPrincipal() {
        add(SavingsAccountTransactionType.DEPOSIT, JAN_1, "1000");
        add(SavingsAccountTransactionType.INTEREST_POSTING, MAR_1, "20");
        final SavingsAccountTransaction withdrawal = add(SavingsAccountTransactionType.WITHDRAWAL, MAR_1, "120");

        assertThat(invested(withdrawal)).isEqualByComparingTo("900");
    }

    @Test
    void interestForfeitedIsNotAvailableToBeWithdrawnAsInterest() {
        add(SavingsAccountTransactionType.DEPOSIT, JAN_1, "1000");
        add(SavingsAccountTransactionType.INTEREST_POSTING, MAR_1, "20");
        add(SavingsAccountTransactionType.INTEREST_CHARGE, MAR_1, "10");
        // 1010 left in the account: 1000 principal and 10 of surviving interest.
        final SavingsAccountTransaction closing = add(SavingsAccountTransactionType.WITHDRAWAL, MAR_1, "1010");

        assertThat(invested(closing)).isEqualByComparingTo("0");
    }

    @Test
    void interestIsConsumedAcrossWithdrawalsInTheOrderTheyHappened() {
        add(SavingsAccountTransactionType.DEPOSIT, JAN_1, "1000");
        add(SavingsAccountTransactionType.INTEREST_POSTING, JAN_1.plusDays(30), "10");
        add(SavingsAccountTransactionType.WITHDRAWAL, JAN_1.plusDays(31), "6");
        add(SavingsAccountTransactionType.INTEREST_POSTING, JAN_1.plusDays(60), "10");
        final SavingsAccountTransaction withdrawal = add(SavingsAccountTransactionType.WITHDRAWAL, JAN_1.plusDays(61), "100");

        // 6 + 100 withdrawn against 20 of interest: 14 of the second comes from interest... 86 from principal.
        assertThat(invested(withdrawal)).isEqualByComparingTo("914");
    }

    @Test
    void transactionsAfterTheOneAskedAboutAreIgnored() {
        final SavingsAccountTransaction deposit = add(SavingsAccountTransactionType.DEPOSIT, JAN_1, "1000");
        add(SavingsAccountTransactionType.WITHDRAWAL, MAR_1, "400");

        assertThat(invested(deposit)).isEqualByComparingTo("1000");
    }

    @Test
    void reversedTransactionsAreIgnored() {
        add(SavingsAccountTransactionType.DEPOSIT, JAN_1, "1000");
        final SavingsAccountTransaction reversed = add(SavingsAccountTransactionType.WITHDRAWAL, MAR_1, "400");
        ReflectionTestUtils.setField(reversed, "reversed", true);
        final SavingsAccountTransaction later = add(SavingsAccountTransactionType.DEPOSIT, APR_1, "100");

        assertThat(invested(later)).isEqualByComparingTo("1100");
    }

    @Test
    void theOrderTheTransactionsAreListedInDoesNotMatter() {
        final SavingsAccountTransaction closing = new SavingsAccountTransactionTestBuilder().withId(9L)
                .withType(SavingsAccountTransactionType.WITHDRAWAL).withDate(MAR_1.plusDays(1)).withAmount(new BigDecimal("1024.93"))
                .build();
        this.transactions.add(closing);
        this.transactions.add(new SavingsAccountTransactionTestBuilder().withId(8L).withType(SavingsAccountTransactionType.INTEREST_POSTING)
                .withDate(MAR_1).withAmount(new BigDecimal("24.93")).build());
        this.transactions.add(new SavingsAccountTransactionTestBuilder().withId(7L).withType(SavingsAccountTransactionType.DEPOSIT)
                .withDate(JAN_1).withAmount(new BigDecimal("1000")).build());

        assertThat(invested(closing)).isEqualByComparingTo("0");
    }

    // The one-pass form used to re-derive every rate-history row must agree with asking about each transaction in turn.
    @Test
    void theOnePassFormAgreesWithAskingAboutEachTransactionInTurn() {
        add(SavingsAccountTransactionType.DEPOSIT, JAN_1, "1000");
        add(SavingsAccountTransactionType.INTEREST_POSTING, MAR_1, "24.93");
        add(SavingsAccountTransactionType.WITHHOLD_TAX, MAR_1, "2.49");
        add(SavingsAccountTransactionType.WITHDRAWAL, MAR_1, "22.44");
        add(SavingsAccountTransactionType.DEPOSIT, APR_1, "300");
        add(SavingsAccountTransactionType.WITHDRAWAL, APR_1.plusDays(5), "1100");

        final java.util.Map<SavingsAccountTransaction, BigDecimal> investedAfter = DynamicDepositInvestedAmountCalculator
                .investedAmountAfterEach(this.transactions, CURRENCY);

        assertThat(investedAfter).hasSize(this.transactions.size());
        for (final SavingsAccountTransaction transaction : this.transactions) {
            assertThat(investedAfter.get(transaction)).isEqualByComparingTo(invested(transaction));
        }
    }

    private BigDecimal invested(final SavingsAccountTransaction upTo) {
        return DynamicDepositInvestedAmountCalculator.investedAmountAsOf(this.transactions, CURRENCY, upTo);
    }

    private SavingsAccountTransaction add(final SavingsAccountTransactionType type, final LocalDate date, final String amount) {
        final SavingsAccountTransaction transaction = new SavingsAccountTransactionTestBuilder().withId(this.nextId++).withType(type)
                .withDate(date).withAmount(new BigDecimal(amount)).build();
        this.transactions.add(transaction);
        return transaction;
    }
}
