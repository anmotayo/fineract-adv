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
package com.advancly.fineract.portfolio.savings.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.advancly.fineract.portfolio.savings.testutil.MoneyHelperInitializer;
import java.lang.reflect.Constructor;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import org.apache.fineract.infrastructure.core.exception.PlatformApiDataValidationException;
import org.apache.fineract.portfolio.savings.SavingsAccountTransactionType;
import org.apache.fineract.portfolio.savings.data.SavingsAccountTransactionDTO;
import org.apache.fineract.portfolio.savings.domain.DepositAccountTermAndPreClosure;
import org.apache.fineract.portfolio.savings.domain.SavingsAccountStatusType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Modelled on how Recurring Deposits guard maturity: a Dynamic Deposit that has reached its maturity date is matured
 * and takes no deposit or withdrawal, and none dated on or after the maturity date, so it can only be closed. Like core
 * and RD these are {@link PlatformApiDataValidationException}s.
 */
class DynamicDepositAccountMaturedTest {

    private static final LocalDate MATURITY_DATE = LocalDate.of(2026, 7, 1);
    private static final String MATURED_CODE = "error.msg.dynamicdepositaccount.transaction.account.is.matured";
    private static final String DATE_CODE = "error.msg.dynamicdepositaccount.transaction.date.is.after.account.maturity.date";

    private DynamicDepositAccount account;

    @BeforeEach
    void setUp() {
        MoneyHelperInitializer.initialize();
        this.account = createInstance(DynamicDepositAccount.class);
        ReflectionTestUtils.setField(this.account, "id", 1L);
        ReflectionTestUtils.setField(this.account, "status", SavingsAccountStatusType.ACTIVE.getValue());
        final DepositAccountTermAndPreClosure term = mock(DepositAccountTermAndPreClosure.class);
        when(term.getMaturityDate()).thenReturn(MATURITY_DATE);
        ReflectionTestUtils.setField(this.account, "accountTermAndPreClosure", term);
    }

    @Test
    void anActiveAccountIsNotMatured() {
        assertThat(this.account.isMatured()).isFalse();
        assertThat(this.account.isActive()).isTrue();
        assertThat(this.account.isTransactionsAllowed()).isTrue();
    }

    @Test
    void aMaturedAccountIsNoLongerActiveAndAllowsNoTransactions() {
        this.account.markMatured();

        assertThat(this.account.isMatured()).isTrue();
        assertThat(this.account.isActive()).isFalse();
        assertThat(this.account.isNotActive()).isTrue();
        assertThat(this.account.isTransactionsAllowed()).isFalse();
    }

    // Unlike FD and RD, a Dynamic Deposit takes manual withdrawals, so the hook core's withdraw gates on is not simply
    // widened to matured accounts: it is open only while the account's own maturity closure is paying it out.
    @Test
    void aMaturedAccountAllowsTransactionsOnlyWhileItsMaturityClosureIsPayingOut() {
        this.account.markMatured();

        this.account.markClosingAtMaturity(true);
        assertThat(this.account.isTransactionsAllowed()).isTrue();

        this.account.markClosingAtMaturity(false);
        assertThat(this.account.isTransactionsAllowed()).isFalse();
    }

    // The payout is a withdrawal; deposits are gated by isNotActive() in core, which the closure never loosens.
    @Test
    void theClosingFlagDoesNotMakeAMaturedAccountActive() {
        this.account.markMatured();

        this.account.markClosingAtMaturity(true);

        assertThat(this.account.isActive()).isFalse();
    }

    @Test
    void theClosingFlagDoesNotAllowTransactionsOnAnAccountThatIsNotMatured() {
        ReflectionTestUtils.setField(this.account, "status", SavingsAccountStatusType.CLOSED.getValue());

        this.account.markClosingAtMaturity(true);

        assertThat(this.account.isTransactionsAllowed()).isFalse();
    }

    @Test
    void aDepositIntoAMaturedAccountIsRejectedWhateverItsDate() {
        this.account.markMatured();

        assertThatThrownBy(
                () -> this.account.deposit(dto(MATURITY_DATE.minusDays(10)), SavingsAccountTransactionType.DEPOSIT, false, null, null))
                .isInstanceOf(PlatformApiDataValidationException.class).satisfies(e -> assertThat(codeOf(e)).isEqualTo(MATURED_CODE));
    }

    @Test
    void aWithdrawalFromAMaturedAccountIsRejectedWhateverItsDate() {
        this.account.markMatured();

        assertThatThrownBy(() -> this.account.withdraw(dto(MATURITY_DATE.minusDays(10)), false, false, null, null))
                .isInstanceOf(PlatformApiDataValidationException.class).satisfies(e -> assertThat(codeOf(e)).isEqualTo(MATURED_CODE));
    }

    @Test
    void aDepositDatedAfterTheMaturityDateIsRejected() {
        assertThatThrownBy(
                () -> this.account.deposit(dto(MATURITY_DATE.plusDays(1)), SavingsAccountTransactionType.DEPOSIT, false, null, null))
                .isInstanceOf(PlatformApiDataValidationException.class).satisfies(e -> assertThat(codeOf(e)).isEqualTo(DATE_CODE));
    }

    @Test
    void aWithdrawalDatedAfterTheMaturityDateIsRejected() {
        assertThatThrownBy(() -> this.account.withdraw(dto(MATURITY_DATE.plusDays(1)), false, false, null, null))
                .isInstanceOf(PlatformApiDataValidationException.class).satisfies(e -> assertThat(codeOf(e)).isEqualTo(DATE_CODE));
    }

    // The maturity day is the first day the account only closes: nothing is accepted on it, not even for the few hours
    // before the maturity job has run.
    @Test
    void aDepositOnTheMaturityDayIsRejected() {
        assertThatThrownBy(() -> this.account.deposit(dto(MATURITY_DATE), SavingsAccountTransactionType.DEPOSIT, false, null, null))
                .isInstanceOf(PlatformApiDataValidationException.class).satisfies(e -> assertThat(codeOf(e)).isEqualTo(DATE_CODE));
    }

    @Test
    void aWithdrawalOnTheMaturityDayIsRejected() {
        assertThatThrownBy(() -> this.account.withdraw(dto(MATURITY_DATE), false, false, null, null))
                .isInstanceOf(PlatformApiDataValidationException.class).satisfies(e -> assertThat(codeOf(e)).isEqualTo(DATE_CODE));
    }

    // The day before maturity is an ordinary day. Whatever else the withdrawal trips over on this bare account, it is
    // not a maturity rule.
    @Test
    void aTransactionTheDayBeforeMaturityIsNotRejectedByTheMaturityRules() {
        assertThatThrownBy(() -> this.account.withdraw(dto(MATURITY_DATE.minusDays(1)), false, false, null, null))
                .satisfies(e -> assertThat(isMaturityRejection(e)).isFalse());
    }

    @Test
    void theMaturityClosureIsExemptFromTheRulesForItsOwnPayout() {
        this.account.markMatured();
        this.account.markClosingAtMaturity(true);

        assertThatThrownBy(() -> this.account.withdraw(dto(MATURITY_DATE.plusDays(3)), false, false, null, null))
                .satisfies(e -> assertThat(isMaturityRejection(e)).isFalse());
    }

    @Test
    void anAccountWithNoMaturityDateIsNotLimitedByTheDateRule() {
        ReflectionTestUtils.setField(this.account, "accountTermAndPreClosure", null);

        assertThatThrownBy(() -> this.account.withdraw(dto(MATURITY_DATE.plusYears(5)), false, false, null, null))
                .satisfies(e -> assertThat(isMaturityRejection(e)).isFalse());
    }

    private static String codeOf(final Throwable e) {
        return ((PlatformApiDataValidationException) e).getErrors().get(0).getUserMessageGlobalisationCode();
    }

    private static boolean isMaturityRejection(final Throwable e) {
        return e instanceof PlatformApiDataValidationException rule
                && (MATURED_CODE.equals(codeOf(rule)) || DATE_CODE.equals(codeOf(rule)));
    }

    private static SavingsAccountTransactionDTO dto(final LocalDate transactionDate) {
        final SavingsAccountTransactionDTO dto = mock(SavingsAccountTransactionDTO.class);
        when(dto.getTransactionDate()).thenReturn(transactionDate);
        when(dto.getFormatter()).thenReturn(DateTimeFormatter.ISO_LOCAL_DATE);
        return dto;
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
