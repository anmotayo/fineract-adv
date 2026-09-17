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

import org.apache.fineract.portfolio.savings.SavingsAccountTransactionType;
import org.junit.jupiter.api.Test;

class InterestForfeitureTransactionTypeTest {

    @Test
    void interestForfeitureRoundTripsAndIsADebit() {
        assertThat(SavingsAccountTransactionType.INTEREST_FORFEITURE.getValue()).isEqualTo(101);
        assertThat(SavingsAccountTransactionType.fromInt(101)).isEqualTo(SavingsAccountTransactionType.INTEREST_FORFEITURE);
        assertThat(SavingsAccountTransactionType.INTEREST_FORFEITURE.isDebit()).isTrue();
        assertThat(SavingsAccountTransactionType.INTEREST_FORFEITURE.isInterestForfeiture()).isTrue();
    }

    @Test
    void interestForfeitureCountsAsAChargeTransactionSoPayAndUndoStaySymmetric() {
        assertThat(SavingsAccountTransactionType.INTEREST_FORFEITURE.isChargeTransaction()).isTrue();
    }

    @Test
    void interestForfeitureIsADistinctTypeFromInterestBasedCharge() {
        // Distinct types for auditability and because their triggers/formulas differ (lifetime vs per-period), even
        // though - for the no-compounding products Phase 1 supports - both are excluded from the interest-bearing
        // balance identically (see PostingPeriod's shouldNotAffectInterestPosting).
        assertThat(SavingsAccountTransactionType.INTEREST_FORFEITURE.isInterestBasedCharge()).isFalse();
        assertThat(SavingsAccountTransactionType.INTEREST_BASED_CHARGE.isInterestForfeiture()).isFalse();
    }
}
