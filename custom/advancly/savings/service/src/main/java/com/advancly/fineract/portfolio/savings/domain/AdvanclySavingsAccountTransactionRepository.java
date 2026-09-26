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

    // O(1) append path, single round trip: returns, in one query, both (a) the last non-reversed transaction
    // (excludes accrual type 10) - the correct seed for the running-balance value, since a posting does move the
    // balance - and (b) the last transaction that carries its own balance window - the row to close (via
    // SavingsAccountTransactionHelper#updatePreviousTransactionBalanceEndDate) when appending a new transaction.
    // Mirrors SavingsAccountTransaction#isBalanceBearing() / the predicate
    // SavingsAccount#resetAccountTransactionsEndOfDayBalances uses: excludes interest posting (3), accrual (10),
    // and overdraft interest (17), which core never windows. Keep these type codes in sync with isBalanceBearing().
    //
    // Each branch is its own indexed "order by ... limit 1" lookup - correct and cheap regardless of how many
    // non-balance-bearing rows (e.g. a long run of interest postings on a dormant account) sit between two real
    // transactions, unlike fetching a fixed-size window and filtering client-side. Plain UNION (not UNION ALL)
    // dedupes for free in the common case where both branches land on the same row (i.e. the latest transaction is
    // itself balance-bearing): since both branches select every column of the same physical row, they are then
    // byte-for-byte identical, and a set union always collapses identical rows - so the result is 1 row in the
    // common case, 2 only when the latest row is itself a posting/accrual/overdraft-interest row. The caller must
    // not rely on the union's row order to tell the two apart - determine roles by comparing the returned rows'
    // own attributes instead (see AdvanclySavingsAccountAssembler).
    @Query(value = "select * from ( " + "  select sat.* from m_savings_account_transaction sat "
            + "  where sat.savings_account_id = :savingsId and sat.is_reversed = false and sat.is_reversal = false "
            + "    and sat.transaction_type_enum <> 10 "
            + "  order by sat.transaction_date desc, sat.created_date desc, sat.id desc limit 1" + ") seed " + "union " + "select * from ( "
            + "  select sat.* from m_savings_account_transaction sat "
            + "  where sat.savings_account_id = :savingsId and sat.is_reversed = false and sat.is_reversal = false "
            + "    and sat.transaction_type_enum not in (3, 10, 17) "
            + "  order by sat.transaction_date desc, sat.created_date desc, sat.id desc limit 1" + ") balanceBearing", nativeQuery = true)
    List<SavingsAccountTransaction> findLastNonReversedAndBalanceBearingTransactions(@Param("savingsId") Long savingsId);

    // O(1) append path: get just the last transaction date for path decision (excludes accrual type 10)
    @Query("select max(sat.dateOf) from SavingsAccountTransaction sat " + "where sat.savingsAccount.id = :savingsId "
            + "and sat.reversed = false and sat.reversalTransaction = false " + "and sat.typeOf <> 10")
    Optional<LocalDate> findLastTransactionDate(@Param("savingsId") Long savingsId);

}
