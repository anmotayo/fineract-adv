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

import java.math.BigDecimal;
import java.time.LocalDate;
import org.apache.fineract.infrastructure.core.data.EnumOptionData;
import org.apache.fineract.organisation.monetary.data.CurrencyData;
import org.apache.fineract.portfolio.savings.SavingsAccountTransactionType;
import org.apache.fineract.portfolio.savings.data.SavingsAccountChargeData;
import org.apache.fineract.portfolio.savings.data.SavingsAccountTransactionData;
import org.apache.fineract.portfolio.savings.data.SavingsAccountTransactionEnumData;
import org.apache.fineract.portfolio.savings.domain.SavingsAccountChargesPaidByData;
import org.apache.fineract.portfolio.savings.service.SavingsEnumerations;
import org.junit.jupiter.api.Test;

class InterestChargeTransactionTypeTest {

    @Test
    void interestChargeRoundTripsAndIsADebit() {
        assertThat(SavingsAccountTransactionType.INTEREST_CHARGE.getValue()).isEqualTo(100);
        assertThat(SavingsAccountTransactionType.fromInt(100)).isEqualTo(SavingsAccountTransactionType.INTEREST_CHARGE);
        assertThat(SavingsAccountTransactionType.INTEREST_CHARGE.isDebit()).isTrue();
        assertThat(SavingsAccountTransactionType.INTEREST_CHARGE.isInterestCharge()).isTrue();
    }

    @Test
    void interestChargeDataKeepsLinkedPenaltyChargeClassification() {
        final SavingsAccountTransactionEnumData transactionType = SavingsEnumerations
                .transactionType(SavingsAccountTransactionType.INTEREST_CHARGE.getValue());
        final LocalDate transactionDate = LocalDate.of(2026, 8, 2);
        final SavingsAccountTransactionData transaction = SavingsAccountTransactionData.create(128041L, transactionType, null, 896L,
                "000000896", transactionDate, new CurrencyData("NGN", 6, null), new BigDecimal("10829.589043"), null,
                new BigDecimal("1988597.123293"), false, transactionDate, false, BigDecimal.ZERO, transactionDate, null);
        final SavingsAccountChargeData charge = new SavingsAccountChargeData(1L, new BigDecimal("10829.589043"),
                new EnumOptionData(1L, null, null), true);
        final SavingsAccountChargesPaidByData paidBy = new SavingsAccountChargesPaidByData(1L, new BigDecimal("10829.589043"));
        paidBy.setSavingsAccountChargeData(charge);
        transaction.setChargesPaidByData(paidBy);

        assertThat(transactionType.isChargeTransaction()).isTrue();
        assertThat(transaction.isChargeTransaction()).isTrue();
        assertThat(transaction.isChargeTransactionAndNotReversed()).isTrue();
        assertThat(transaction.isPenaltyCharge()).isTrue();
        assertThat(transaction.isPenaltyChargeAndNotReversed()).isTrue();
        assertThat(transaction.isFeeCharge()).isFalse();
        assertThat(transaction.getChargesPaidByData()).containsExactly(paidBy);
    }

    @Test
    void interestChargeCountsAsAChargeTransactionSoPayAndUndoStaySymmetric() {
        assertThat(SavingsAccountTransactionType.INTEREST_CHARGE.isChargeTransaction()).isTrue();
    }
}
