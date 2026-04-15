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
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AdvanclySavingsAccountTransactionRepository extends JpaRepository<SavingsAccountTransaction, Long> {

    // O(1) append path: get last non-reversed transaction for running balance (excludes accrual type 10)
    @Query("select sat from SavingsAccountTransaction sat " + "where sat.savingsAccount.id = :savingsId "
            + "and sat.reversed = false and sat.reversalTransaction = false " + "and sat.typeOf <> 10 "
            + "order by sat.dateOf desc, sat.createdDate desc, sat.id desc")
    List<SavingsAccountTransaction> findLastNonReversedTransaction(@Param("savingsId") Long savingsId, Pageable pageable);

    // O(1) append path: get just the last transaction date for path decision (excludes accrual type 10)
    @Query("select max(sat.dateOf) from SavingsAccountTransaction sat " + "where sat.savingsAccount.id = :savingsId "
            + "and sat.reversed = false and sat.reversalTransaction = false " + "and sat.typeOf <> 10")
    Optional<LocalDate> findLastTransactionDate(@Param("savingsId") Long savingsId);

}
