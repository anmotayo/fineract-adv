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
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

import java.math.BigDecimal;
import java.time.LocalDate;
import org.apache.fineract.portfolio.charge.domain.Charge;
import org.apache.fineract.portfolio.savings.domain.SavingsAccount;
import org.apache.fineract.portfolio.savings.domain.SavingsAccountCharge;
import org.apache.fineract.portfolio.savings.domain.SavingsAccountTransaction;
import org.junit.jupiter.api.Test;

class SavingsAccountInterestChargeTest {

    @Test
    void aNewRowIsPendingAndCarriesNoTransactionLinks() {
        final SavingsAccountInterestCharge row = row(false);

        assertThat(row.isPending()).isTrue();
        assertThat(row.interestPostingTransaction()).isNull();
        assertThat(row.interestChargeTransaction()).isNull();
        assertThat(row.chargeAmount()).isEqualByComparingTo("15.00");
        assertThat(row.interestAmountBasis()).isEqualByComparingTo("300.00");
        assertThat(row.chargePercentage()).isEqualByComparingTo("5");
    }

    @Test
    void linkToPostingStoresBothTransactionsAndClearsThePendingState() {
        final SavingsAccountInterestCharge row = row(false);
        final SavingsAccountTransaction posting = mock(SavingsAccountTransaction.class);
        final SavingsAccountTransaction chargeTransaction = mock(SavingsAccountTransaction.class);

        row.linkToPosting(posting, chargeTransaction);

        assertThat(row.isPending()).isFalse();
        assertThat(row.interestPostingTransaction()).isSameAs(posting);
        assertThat(row.interestChargeTransaction()).isSameAs(chargeTransaction);
    }

    @Test
    void aRowWhoseWithdrawalTransactionWasReversedIsVoided() {
        assertThat(row(true).isVoidedByReversal()).isTrue();
        assertThat(row(false).isVoidedByReversal()).isFalse();
    }

    private SavingsAccountInterestCharge row(final boolean withdrawalReversed) {
        final SavingsAccount account = mock(SavingsAccount.class);
        final SavingsAccountTransaction withdrawal = mock(SavingsAccountTransaction.class);
        lenient().when(withdrawal.isReversed()).thenReturn(withdrawalReversed);
        final SavingsAccountCharge savingsAccountCharge = mock(SavingsAccountCharge.class);
        final Charge charge = mock(Charge.class);

        return SavingsAccountInterestCharge.createNew(account, withdrawal, savingsAccountCharge, charge, LocalDate.of(2026, 1, 1),
                LocalDate.of(2026, 1, 20), new BigDecimal("300.00"), new BigDecimal("5"), new BigDecimal("15.00"));
    }
}
