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
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.advancly.fineract.portfolio.savings.testutil.MoneyHelperInitializer;
import com.advancly.fineract.portfolio.savings.testutil.SavingsAccountTestBuilder;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.Set;
import org.apache.fineract.infrastructure.core.exception.PlatformApiDataValidationException;
import org.apache.fineract.portfolio.client.domain.Client;
import org.apache.fineract.portfolio.client.exception.ClientNotActiveException;
import org.apache.fineract.portfolio.group.domain.Group;
import org.apache.fineract.portfolio.group.exception.GroupNotActiveException;
import org.apache.fineract.portfolio.savings.domain.SavingsAccount;
import org.apache.fineract.portfolio.savings.domain.SavingsAccountCharge;
import org.apache.fineract.portfolio.savings.domain.SavingsAccountStatusType;
import org.apache.fineract.portfolio.savings.exception.SavingsActivityPriorToClientTransferException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * The checks core makes before a deposit or a withdrawal that the append path has to make too. Each failure carries
 * core's own error code, so a client sees the same response whichever path served the request.
 */
class SavingsTransactionRulesTest {

    // Within an account activated on 2025-01-01 and no later than today.
    private static final LocalDate DAY = LocalDate.now().minusDays(1);

    private SavingsAccount account;

    @BeforeEach
    void setUp() {
        MoneyHelperInitializer.initialize();
        this.account = new SavingsAccountTestBuilder().withId(1L).build();
    }

    // ---- deposit ----

    @Test
    void anActiveAccountTakesADepositOnAnOrdinaryDay() {
        assertThatCode(() -> SavingsTransactionRules.validateDepositAllowed(this.account, DAY)).doesNotThrowAnyException();
    }

    @Test
    void aDepositIntoAnAccountThatIsNotActiveIsRefused() {
        for (final SavingsAccountStatusType status : new SavingsAccountStatusType[] { SavingsAccountStatusType.CLOSED,
                SavingsAccountStatusType.SUBMITTED_AND_PENDING_APPROVAL, SavingsAccountStatusType.REJECTED }) {
            ReflectionTestUtils.setField(this.account, "status", status.getValue());

            assertThatThrownBy(() -> SavingsTransactionRules.validateDepositAllowed(this.account, DAY))
                    .isInstanceOf(PlatformApiDataValidationException.class)
                    .satisfies(e -> assertThat(codeOf(e)).isEqualTo("error.msg.savingsaccount.transaction.account.is.not.active"));
        }
    }

    @Test
    void aDepositDatedInTheFutureIsRefused() {
        assertThatThrownBy(() -> SavingsTransactionRules.validateDepositAllowed(this.account, LocalDate.now().plusDays(3)))
                .isInstanceOf(PlatformApiDataValidationException.class)
                .satisfies(e -> assertThat(codeOf(e)).isEqualTo("error.msg.savingsaccount.transaction.in.the.future"));
    }

    @Test
    void aDepositDatedBeforeTheAccountWasActivatedIsRefused() {
        assertThatThrownBy(() -> SavingsTransactionRules.validateDepositAllowed(this.account, LocalDate.of(2024, 12, 31)))
                .isInstanceOf(PlatformApiDataValidationException.class)
                .satisfies(e -> assertThat(codeOf(e)).isEqualTo("error.msg.savingsaccount.transaction.before.activation.date"));
    }

    @Test
    void aDepositDatedBeforeTheClientJoinedTheirOfficeIsRefused() {
        final Client client = this.account.getClient();
        when(client.getOfficeJoiningDate()).thenReturn(LocalDate.now().minusDays(5));

        assertThatThrownBy(() -> SavingsTransactionRules.validateDepositAllowed(this.account, LocalDate.now().minusDays(9)))
                .isInstanceOf(SavingsActivityPriorToClientTransferException.class);
    }

    // ---- withdrawal ----

    @Test
    void anActiveAccountAllowsAWithdrawalOnAnOrdinaryDay() {
        assertThatCode(() -> SavingsTransactionRules.validateWithdrawalAllowed(this.account, DAY)).doesNotThrowAnyException();
    }

    @Test
    void aWithdrawalFromAnAccountThatIsNotActiveIsRefused() {
        ReflectionTestUtils.setField(this.account, "status", SavingsAccountStatusType.CLOSED.getValue());

        assertThatThrownBy(() -> SavingsTransactionRules.validateWithdrawalAllowed(this.account, DAY))
                .isInstanceOf(PlatformApiDataValidationException.class)
                .satisfies(e -> assertThat(codeOf(e)).isEqualTo("error.msg.savingsaccount.transaction.account.is.not.active"));
    }

    @Test
    void aWithdrawalDatedInTheFutureIsRefused() {
        assertThatThrownBy(() -> SavingsTransactionRules.validateWithdrawalAllowed(this.account, LocalDate.now().plusDays(3)))
                .isInstanceOf(PlatformApiDataValidationException.class)
                .satisfies(e -> assertThat(codeOf(e)).isEqualTo("error.msg.savingsaccount.transaction.in.the.future"));
    }

    @Test
    void aWithdrawalDatedBeforeTheAccountWasActivatedIsRefused() {
        assertThatThrownBy(() -> SavingsTransactionRules.validateWithdrawalAllowed(this.account, LocalDate.of(2024, 12, 31)))
                .isInstanceOf(PlatformApiDataValidationException.class)
                .satisfies(e -> assertThat(codeOf(e)).isEqualTo("error.msg.savingsaccount.transaction.before.activation.date"));
    }

    @Test
    void noWithdrawalIsAllowedWhileTheAccountIsLockedIn() {
        ReflectionTestUtils.setField(this.account, "lockedInUntilDate", LocalDate.now().plusMonths(3));

        assertThatThrownBy(() -> SavingsTransactionRules.validateWithdrawalAllowed(this.account, DAY))
                .isInstanceOf(PlatformApiDataValidationException.class).satisfies(e -> assertThat(codeOf(e))
                        .isEqualTo("error.msg.savingsaccount.transaction.withdrawals.blocked.during.lockin.period"));
    }

    @Test
    void aWithdrawalAfterTheLockInHasEndedIsAllowed() {
        ReflectionTestUtils.setField(this.account, "lockedInUntilDate", DAY.minusDays(1));

        assertThatCode(() -> SavingsTransactionRules.validateWithdrawalAllowed(this.account, DAY)).doesNotThrowAnyException();
    }

    // The lock-in stops withdrawals, not deposits.
    @Test
    void theLockInDoesNotStopADeposit() {
        ReflectionTestUtils.setField(this.account, "lockedInUntilDate", LocalDate.now().plusMonths(3));

        assertThatCode(() -> SavingsTransactionRules.validateDepositAllowed(this.account, DAY)).doesNotThrowAnyException();
    }

    // ---- client and group ----

    @Test
    void aClientThatIsNotActiveCannotTransact() {
        when(this.account.getClient().isNotActive()).thenReturn(true);

        assertThatThrownBy(() -> SavingsTransactionRules.validateClientAndGroupActive(this.account))
                .isInstanceOf(ClientNotActiveException.class);
    }

    @Test
    void aGroupThatIsNotActiveCannotTransact() {
        final Group group = mock(Group.class);
        when(group.isNotActive()).thenReturn(true);
        ReflectionTestUtils.setField(this.account, "group", group);

        assertThatThrownBy(() -> SavingsTransactionRules.validateClientAndGroupActive(this.account))
                .isInstanceOf(GroupNotActiveException.class);
    }

    @Test
    void anActiveClientAndGroupCanTransact() {
        final Group group = mock(Group.class);
        ReflectionTestUtils.setField(this.account, "group", group);

        assertThatCode(() -> SavingsTransactionRules.validateClientAndGroupActive(this.account)).doesNotThrowAnyException();
    }

    // ---- withdrawal fee ----

    @Test
    void anOrdinaryWithdrawalFeeIsOneCorePays() {
        withCharges(charge(true, true, false));

        assertThat(SavingsTransactionRules.hasAutoPayableWithdrawalFee(this.account)).isTrue();
    }

    // The early-withdrawal interest charge is a withdrawal-related, percent-of-interest charge: core does not pay it in
    // the withdrawal, and AdvanclyInterestChargeApplicationService applies it after, on both paths.
    @Test
    void aPercentOfInterestWithdrawalChargeIsNotOneCorePays() {
        withCharges(charge(true, true, true));

        assertThat(SavingsTransactionRules.hasAutoPayableWithdrawalFee(this.account)).isFalse();
    }

    @Test
    void anInactiveWithdrawalFeeIsNotOneCorePays() {
        withCharges(charge(true, false, false));

        assertThat(SavingsTransactionRules.hasAutoPayableWithdrawalFee(this.account)).isFalse();
    }

    @Test
    void aChargeThatIsNotAWithdrawalFeeIsNotOneCorePays() {
        withCharges(charge(false, true, false));

        assertThat(SavingsTransactionRules.hasAutoPayableWithdrawalFee(this.account)).isFalse();
    }

    @Test
    void oneOrdinaryFeeAmongOtherChargesIsEnough() {
        withCharges(charge(true, true, true), charge(false, true, false), charge(true, true, false));

        assertThat(SavingsTransactionRules.hasAutoPayableWithdrawalFee(this.account)).isTrue();
    }

    @Test
    void anAccountWithNoChargesPaysNoWithdrawalFee() {
        assertThat(SavingsTransactionRules.hasAutoPayableWithdrawalFee(this.account)).isFalse();
    }

    private void withCharges(final SavingsAccountCharge... charges) {
        final Set<SavingsAccountCharge> set = new HashSet<>(java.util.List.of(charges));
        ReflectionTestUtils.setField(this.account, "charges", set);
    }

    private static SavingsAccountCharge charge(final boolean withdrawalFee, final boolean active, final boolean percentageOfInterest) {
        final SavingsAccountCharge charge = mock(SavingsAccountCharge.class);
        when(charge.isWithdrawalFee()).thenReturn(withdrawalFee);
        // Short-circuiting: a charge that is not a withdrawal fee is never asked the rest.
        org.mockito.Mockito.lenient().when(charge.isActive()).thenReturn(active);
        org.mockito.Mockito.lenient().when(charge.isPercentageOfInterest()).thenReturn(percentageOfInterest);
        return charge;
    }

    private static String codeOf(final Throwable e) {
        return ((PlatformApiDataValidationException) e).getErrors().get(0).getUserMessageGlobalisationCode();
    }
}
