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
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.apache.fineract.organisation.monetary.domain.MonetaryCurrency;
import org.apache.fineract.portfolio.savings.SavingsAccountTransactionType;
import org.apache.fineract.portfolio.savings.domain.SavingsAccountTransaction;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * The amount invested is the principal that is still in the account: deposits less the PRINCIPAL part of withdrawals.
 * An interest transfer to the linked account takes interest only; the closing payout (the withdrawal that leaves the
 * account empty, counting the early-withdrawal charge posted right after it) sweeps what interest is left and then
 * principal; every other withdrawal is principal withdrawn in full, whatever interest the account holds at the time.
 */
class DynamicDepositInvestedAmountCalculatorTest {

    private static final MonetaryCurrency CURRENCY = new MonetaryCurrency("USD", 2, null);
    private static final LocalDate JAN_1 = LocalDate.of(2026, 1, 1);
    private static final LocalDate MAR_1 = LocalDate.of(2026, 3, 1);
    private static final LocalDate APR_1 = LocalDate.of(2026, 4, 1);

    private final List<SavingsAccountTransaction> transactions = new ArrayList<>();
    private final Set<Long> interestTransfers = new HashSet<>();
    private long nextId = 1;

    @BeforeEach
    void setUp() {
        MoneyHelperInitializer.initialize();
        this.transactions.clear();
        this.interestTransfers.clear();
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
        final SavingsAccountTransaction closing = addEmptyingWithdrawal(MAR_1, "1024.93");

        assertThat(invested(closing)).isEqualByComparingTo("0");
    }

    @Test
    void interestPaidOutLessTaxDoesNotReduceThePrincipal() {
        add(SavingsAccountTransactionType.DEPOSIT, JAN_1, "1000");
        add(SavingsAccountTransactionType.INTEREST_POSTING, MAR_1, "24.93");
        add(SavingsAccountTransactionType.WITHHOLD_TAX, MAR_1, "2.49");
        // transferInterestToSavings: the interest less tax, 22.44, is paid out to the linked account.
        final SavingsAccountTransaction interestTransfer = addInterestTransfer(MAR_1, "22.44");

        assertThat(invested(interestTransfer)).isEqualByComparingTo("1000");
    }

    @Test
    void afterInterestTransfersTheWholePrincipalWithdrawnLeavesNothing() {
        add(SavingsAccountTransactionType.DEPOSIT, JAN_1, "1000");
        add(SavingsAccountTransactionType.INTEREST_POSTING, MAR_1, "24.93");
        add(SavingsAccountTransactionType.WITHHOLD_TAX, MAR_1, "2.49");
        addInterestTransfer(MAR_1, "22.44");
        final SavingsAccountTransaction principal = add(SavingsAccountTransactionType.WITHDRAWAL, APR_1, "1000");

        // No phantom interest is left to be mislabelled: the tax already took its share of the posting out.
        assertThat(invested(principal)).isEqualByComparingTo("0");
    }

    // The bug: 300 of interest is sitting in the account and a customer takes out 5000 of their own money.
    @Test
    void aPrincipalWithdrawalIsPrincipalEvenWhileTheAccountHoldsInterest() {
        add(SavingsAccountTransactionType.DEPOSIT, JAN_1, "100000");
        add(SavingsAccountTransactionType.INTEREST_POSTING, MAR_1, "300");
        final SavingsAccountTransaction withdrawal = add(SavingsAccountTransactionType.WITHDRAWAL, MAR_1, "5000");

        assertThat(invested(withdrawal)).isEqualByComparingTo("95000");
    }

    @Test
    void interestForfeitedIsNotAvailableToBeSweptAsInterestByTheClosingPayout() {
        add(SavingsAccountTransactionType.DEPOSIT, JAN_1, "1000");
        add(SavingsAccountTransactionType.INTEREST_POSTING, MAR_1, "20");
        add(SavingsAccountTransactionType.INTEREST_CHARGE, MAR_1, "10");
        // 1010 left in the account: 1000 principal and 10 of surviving interest.
        final SavingsAccountTransaction closing = addEmptyingWithdrawal(MAR_1, "1010");

        assertThat(invested(closing)).isEqualByComparingTo("0");
    }

    // 1000 principal + 24.93 interest, 5.00 early-withdrawal charge: the payout is 1019.93 and leaves the 5.00 for the
    // charge, which is posted right after it and is what brings the account to zero.
    @Test
    void theClosingPayoutIsRecognisedWhenTheEarlyWithdrawalChargeTakesTheLastOfTheBalance() {
        add(SavingsAccountTransactionType.DEPOSIT, JAN_1, "1000");
        add(SavingsAccountTransactionType.INTEREST_POSTING, MAR_1, "24.93");
        final SavingsAccountTransaction payout = add(SavingsAccountTransactionType.WITHDRAWAL, MAR_1, "1019.93", "5.00");
        final SavingsAccountTransaction charge = add(SavingsAccountTransactionType.INTEREST_CHARGE, MAR_1, "5.00", "0");

        assertThat(invested(payout)).isEqualByComparingTo("0");
        assertThat(invested(charge)).isEqualByComparingTo("0");
    }

    @Test
    void aWithdrawalThatLeavesBalanceBehindIsNotTakenForAClosingPayout() {
        add(SavingsAccountTransactionType.DEPOSIT, JAN_1, "1000");
        add(SavingsAccountTransactionType.INTEREST_POSTING, MAR_1, "20");
        final SavingsAccountTransaction withdrawal = add(SavingsAccountTransactionType.WITHDRAWAL, MAR_1, "500", "520");

        assertThat(invested(withdrawal)).isEqualByComparingTo("500");
    }

    @Test
    void aWithdrawalWithoutARunningBalanceIsPrincipal() {
        add(SavingsAccountTransactionType.DEPOSIT, JAN_1, "1000");
        add(SavingsAccountTransactionType.INTEREST_POSTING, MAR_1, "20");
        final SavingsAccountTransaction simulated = add(SavingsAccountTransactionType.WITHDRAWAL, MAR_1, "120");

        assertThat(invested(simulated)).isEqualByComparingTo("880");
    }

    @Test
    void interestIsConsumedAcrossInterestTransfersInTheOrderTheyHappened() {
        add(SavingsAccountTransactionType.DEPOSIT, JAN_1, "1000");
        add(SavingsAccountTransactionType.INTEREST_POSTING, JAN_1.plusDays(30), "10");
        addInterestTransfer(JAN_1.plusDays(31), "6");
        add(SavingsAccountTransactionType.INTEREST_POSTING, JAN_1.plusDays(60), "10");
        final SavingsAccountTransaction closing = addEmptyingWithdrawal(JAN_1.plusDays(61), "1014");

        // 14 of interest was still there for the closing payout to sweep, so all 1000 of principal leaves.
        assertThat(invested(closing)).isEqualByComparingTo("0");
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
                .withRunningBalance(BigDecimal.ZERO).build();
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
        addInterestTransfer(MAR_1, "22.44");
        add(SavingsAccountTransactionType.DEPOSIT, APR_1, "300");
        add(SavingsAccountTransactionType.WITHDRAWAL, APR_1.plusDays(5), "1100");

        final java.util.Map<SavingsAccountTransaction, BigDecimal> investedAfter = DynamicDepositInvestedAmountCalculator
                .investedAmountAfterEach(this.transactions, CURRENCY, this.interestTransfers);

        assertThat(investedAfter).hasSize(this.transactions.size());
        for (final SavingsAccountTransaction transaction : this.transactions) {
            assertThat(investedAfter.get(transaction)).isEqualByComparingTo(invested(transaction));
        }
    }

    private BigDecimal invested(final SavingsAccountTransaction upTo) {
        return DynamicDepositInvestedAmountCalculator.investedAmountAsOf(this.transactions, CURRENCY, this.interestTransfers, upTo);
    }

    private SavingsAccountTransaction addInterestTransfer(final LocalDate date, final String amount) {
        final SavingsAccountTransaction transfer = add(SavingsAccountTransactionType.WITHDRAWAL, date, amount);
        this.interestTransfers.add(transfer.getId());
        return transfer;
    }

    private SavingsAccountTransaction addEmptyingWithdrawal(final LocalDate date, final String amount) {
        return add(SavingsAccountTransactionType.WITHDRAWAL, date, amount, "0");
    }

    private SavingsAccountTransaction add(final SavingsAccountTransactionType type, final LocalDate date, final String amount,
            final String runningBalanceAfter) {
        final SavingsAccountTransaction transaction = new SavingsAccountTransactionTestBuilder().withId(this.nextId++).withType(type)
                .withDate(date).withAmount(new BigDecimal(amount)).withRunningBalance(new BigDecimal(runningBalanceAfter)).build();
        this.transactions.add(transaction);
        return transaction;
    }

    private SavingsAccountTransaction add(final SavingsAccountTransactionType type, final LocalDate date, final String amount) {
        final SavingsAccountTransaction transaction = new SavingsAccountTransactionTestBuilder().withId(this.nextId++).withType(type)
                .withDate(date).withAmount(new BigDecimal(amount)).build();
        this.transactions.add(transaction);
        return transaction;
    }
}
