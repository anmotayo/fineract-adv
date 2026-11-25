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

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.apache.fineract.portfolio.savings.domain.SavingsAccountTransaction;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AdvanclySavingsAccountTransactionRepository extends JpaRepository<SavingsAccountTransaction, Long> {

    // Locking reads on purpose: under REPEATABLE-READ a plain read after the account lock would use the stale
    // pre-lock snapshot. Two queries, not a UNION, because Postgres rejects FOR UPDATE inside a UNION.

    // Running-balance seed; excludes accrual (10).
    @Query(value = "select sat.* from m_savings_account_transaction sat "
            + "where sat.savings_account_id = ?1 and sat.is_reversed = false and sat.is_reversal = false "
            + "and sat.transaction_type_enum <> 10 "
            + "order by sat.transaction_date desc, sat.created_on_utc desc, sat.id desc limit 1 for update", nativeQuery = true)
    Optional<SavingsAccountTransaction> findLastNonReversedTransactionForUpdate(Long savingsId);

    // Balance window to close on append. Keep types in sync with SavingsAccountTransaction#isBalanceBearing().
    @Query(value = "select sat.* from m_savings_account_transaction sat "
            + "where sat.savings_account_id = ?1 and sat.is_reversed = false and sat.is_reversal = false "
            + "and sat.transaction_type_enum not in (3, 10, 17) "
            + "order by sat.transaction_date desc, sat.created_on_utc desc, sat.id desc limit 1 for update", nativeQuery = true)
    Optional<SavingsAccountTransaction> findLastBalanceBearingTransactionForUpdate(Long savingsId);

    // At most 2 rows; the caller resolves roles by attribute, not order.
    default List<SavingsAccountTransaction> findLastNonReversedAndBalanceBearingTransactions(final Long savingsId) {
        final Optional<SavingsAccountTransaction> last = findLastNonReversedTransactionForUpdate(savingsId);
        if (last.isEmpty()) {
            return List.of();
        }
        if (last.get().isBalanceBearing()) {
            return List.of(last.get());
        }
        return findLastBalanceBearingTransactionForUpdate(savingsId).map(bearing -> List.of(last.get(), bearing))
                .orElseGet(() -> List.of(last.get()));
    }

    // Plain (snapshot) read on purpose: a locking read on an account with no rows takes a gap lock shared with
    // neighbouring accounts, and two inserts into that gap deadlock.
    boolean existsBySavingsAccountId(Long savingsId);

    @Query("select max(sat.dateOf) from SavingsAccountTransaction sat " + "where sat.savingsAccount.id = :savingsId "
            + "and sat.reversed = false and sat.reversalTransaction = false " + "and sat.typeOf <> 10")
    Optional<LocalDate> findLastTransactionDate(@Param("savingsId") Long savingsId);

}
