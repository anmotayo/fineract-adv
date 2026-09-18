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
package org.apache.fineract.portfolio.savings.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Set;
import org.apache.fineract.infrastructure.businessdate.domain.BusinessDateType;
import org.apache.fineract.infrastructure.core.domain.ActionContext;
import org.apache.fineract.infrastructure.core.domain.FineractPlatformTenant;
import org.apache.fineract.infrastructure.core.service.ThreadLocalContextUtil;
import org.apache.fineract.organisation.monetary.domain.MonetaryCurrency;
import org.apache.fineract.organisation.monetary.domain.MoneyHelper;
import org.apache.fineract.portfolio.charge.domain.Charge;
import org.apache.fineract.portfolio.charge.domain.ChargeCalculationType;
import org.apache.fineract.portfolio.charge.domain.ChargeTimeType;
import org.apache.fineract.portfolio.savings.DepositAccountType;
import org.apache.fineract.portfolio.savings.data.SavingsAccountTransactionDTO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SavingsAccountWithdrawalFeeTest {

    private static final MonetaryCurrency USD = new MonetaryCurrency("USD", 2, null);
    private static final LocalDate ACTIVATION_DATE = LocalDate.of(2026, 1, 1);
    private static final LocalDate WITHDRAWAL_DATE = LocalDate.of(2026, 2, 1);

    private FineractPlatformTenant originalTenant;

    @BeforeEach
    void setUp() {
        this.originalTenant = ThreadLocalContextUtil.getTenant();
        ThreadLocalContextUtil
                .setTenant(FineractPlatformTenant.builder().id(1L).tenantIdentifier("default").name("Default").timezoneId("UTC").build());
        MoneyHelper.initializeTenantRoundingMode("default", RoundingMode.HALF_EVEN.ordinal());

        final HashMap<BusinessDateType, LocalDate> businessDates = new HashMap<>();
        businessDates.put(BusinessDateType.BUSINESS_DATE, LocalDate.of(2026, 9, 18));
        businessDates.put(BusinessDateType.COB_DATE, LocalDate.of(2026, 9, 17));
        ThreadLocalContextUtil.setActionContext(ActionContext.DEFAULT);
        ThreadLocalContextUtil.setBusinessDates(businessDates);
    }

    @AfterEach
    void tearDown() {
        ThreadLocalContextUtil.setTenant(this.originalTenant);
        MoneyHelper.clearCache();
        ThreadLocalContextUtil.reset();
    }

    @Test
    void calculatesOrdinaryFlatWithdrawalFeeForPlainSavings() {
        final SavingsAccount account = account(DepositAccountType.SAVINGS_DEPOSIT,
                withdrawalCharge(BigDecimal.valueOf(25L), ChargeCalculationType.FLAT));

        assertThat(account.calculateWithdrawalFee(BigDecimal.valueOf(1000L))).isEqualByComparingTo("25");
    }

    @Test
    void excludesDynamicDepositInterestBasedWithdrawalFeeFromAutoWithdrawalFeeCalculation() {
        final SavingsAccount account = account(DepositAccountType.DYNAMIC_DEPOSIT,
                withdrawalCharge(BigDecimal.valueOf(18L), ChargeCalculationType.PERCENT_OF_INTEREST));

        assertThat(account.calculateWithdrawalFee(BigDecimal.valueOf(1000L))).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void excludesPlainSavingsInterestBasedEarlyWithdrawalFeeFromAutoWithdrawalFeeCalculation() {
        final SavingsAccount account = account(DepositAccountType.SAVINGS_DEPOSIT,
                withdrawalCharge(BigDecimal.valueOf(18L), ChargeCalculationType.PERCENT_OF_INTEREST));

        assertThat(account.calculateWithdrawalFee(BigDecimal.valueOf(1000L))).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void withdrawDoesNotCreateGenericWithdrawalFeeForDynamicDepositInterestBasedCharge() {
        final SavingsAccountCharge interestBasedCharge = withdrawalCharge(BigDecimal.valueOf(18L),
                ChargeCalculationType.PERCENT_OF_INTEREST);
        final SavingsAccount account = account(DepositAccountType.DYNAMIC_DEPOSIT, interestBasedCharge);

        account.withdraw(new SavingsAccountTransactionDTO(DateTimeFormatter.ISO_LOCAL_DATE, WITHDRAWAL_DATE, BigDecimal.valueOf(100L), null,
                1L, DepositAccountType.DYNAMIC_DEPOSIT.getValue()), true, false, 0L, null);

        assertThat(account.getTransactions()).hasSize(1);
        assertThat(account.getTransactions()).noneMatch(SavingsAccountTransaction::isWithdrawalFee);
        assertThat(interestBasedCharge.amoutOutstanding()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void withdrawDoesNotCreateGenericWithdrawalFeeForPlainSavingsInterestBasedEarlyWithdrawalCharge() {
        final SavingsAccountCharge interestBasedCharge = withdrawalCharge(BigDecimal.valueOf(18L),
                ChargeCalculationType.PERCENT_OF_INTEREST);
        final SavingsAccount account = account(DepositAccountType.SAVINGS_DEPOSIT, interestBasedCharge);

        account.withdraw(new SavingsAccountTransactionDTO(DateTimeFormatter.ISO_LOCAL_DATE, WITHDRAWAL_DATE, BigDecimal.valueOf(100L), null,
                1L, DepositAccountType.SAVINGS_DEPOSIT.getValue()), true, false, 0L, null);

        assertThat(account.getTransactions()).hasSize(1);
        assertThat(account.getTransactions()).noneMatch(SavingsAccountTransaction::isWithdrawalFee);
        assertThat(interestBasedCharge.amoutOutstanding()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void withdrawStillAutoPaysOrdinaryFlatWithdrawalFee() {
        final SavingsAccountCharge flatCharge = withdrawalCharge(BigDecimal.valueOf(25L), ChargeCalculationType.FLAT);
        final SavingsAccount account = account(DepositAccountType.SAVINGS_DEPOSIT, flatCharge);

        account.withdraw(new SavingsAccountTransactionDTO(DateTimeFormatter.ISO_LOCAL_DATE, WITHDRAWAL_DATE, BigDecimal.valueOf(100L), null,
                1L, DepositAccountType.SAVINGS_DEPOSIT.getValue()), true, false, 0L, null);

        assertThat(account.getTransactions()).hasSize(2);
        assertThat(account.getTransactions()).anyMatch(SavingsAccountTransaction::isWithdrawalFee);
        assertThat(flatCharge.amoutOutstanding()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    private static SavingsAccount account(final DepositAccountType depositAccountType, final SavingsAccountCharge charge) {
        final TestSavingsAccount account = new TestSavingsAccount(depositAccountType);
        charge.update(account);
        account.charges = Set.of(charge);
        return account;
    }

    private static SavingsAccountCharge withdrawalCharge(final BigDecimal amount, final ChargeCalculationType calculationType) {
        final Charge charge = mock(Charge.class);
        lenient().when(charge.getId()).thenReturn(1L);
        lenient().when(charge.getName()).thenReturn("Withdrawal fee");
        lenient().when(charge.getAmount()).thenReturn(amount);
        lenient().when(charge.getChargeTimeType()).thenReturn(ChargeTimeType.WITHDRAWAL_FEE.getValue());
        lenient().when(charge.getChargeCalculation()).thenReturn(calculationType.getValue());
        lenient().when(charge.isPenalty()).thenReturn(true);
        return SavingsAccountCharge.createNewWithoutSavingsAccount(charge, amount, ChargeTimeType.WITHDRAWAL_FEE, calculationType, null,
                true, null, null);
    }

    private static final class TestSavingsAccount extends SavingsAccount {

        private final DepositAccountType depositAccountType;

        private TestSavingsAccount(final DepositAccountType depositAccountType) {
            this.depositAccountType = depositAccountType;
            this.status = SavingsAccountStatusType.ACTIVE.getValue();
            this.activatedOnDate = ACTIVATION_DATE;
            this.currency = USD;
            this.withdrawalFeeApplicableForTransfer = true;
            this.summary = new SavingsAccountSummary();
        }

        @Override
        public DepositAccountType depositAccountType() {
            return this.depositAccountType;
        }
    }
}
