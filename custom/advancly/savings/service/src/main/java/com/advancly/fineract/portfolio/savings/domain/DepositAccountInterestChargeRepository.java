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

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface DepositAccountInterestChargeRepository extends JpaRepository<DepositAccountInterestCharge, Long> {

    /**
     * Pending (not yet consumed by an interest posting) early-withdrawal charge rows whose interest period ended on or
     * before {@code upToDate} - i.e. the rows a posting boundary ending on that date must sum (implementation plan
     * Section 10 step 2). Rows whose withdrawal transaction has been reversed are excluded here rather than filtered by
     * the caller, since this table carries no reversal state of its own. Ordered by id so the first row is the oldest,
     * which is the one whose charge definition the single charge transaction is attributed to.
     */
    @Query("select c from DepositAccountInterestCharge c where c.account.id = :savingsAccountId "
            + "and c.interestChargeTransaction is null and c.interestPeriodEndDate <= :upToDate "
            + "and c.withdrawalTransaction.reversed = false order by c.id asc")
    List<DepositAccountInterestCharge> findPendingByAccountIdUpTo(@Param("savingsAccountId") Long savingsAccountId,
            @Param("upToDate") LocalDate upToDate);

    /**
     * Backs {@code m_savings_account.interest_based_charge_derived} (implementation plan Section 5): the current
     * calculated-but-not-yet-posted interest-based charge amount.
     */
    @Query("select coalesce(sum(c.chargeAmount), 0) from DepositAccountInterestCharge c where c.account.id = :savingsAccountId "
            + "and c.interestChargeTransaction is null and c.withdrawalTransaction.reversed = false")
    BigDecimal sumPendingChargeAmount(@Param("savingsAccountId") Long savingsAccountId);

    /**
     * Backs {@code m_savings_account.interest_based_charge_posted_derived} (implementation plan Section 5): the total
     * already applied through interest posting. Excludes rows whose charge transaction or originating withdrawal has
     * since been reversed.
     */
    @Query("select coalesce(sum(c.chargeAmount), 0) from DepositAccountInterestCharge c where c.account.id = :savingsAccountId "
            + "and c.interestChargeTransaction is not null and c.interestChargeTransaction.reversed = false "
            + "and c.withdrawalTransaction.reversed = false")
    BigDecimal sumPostedChargeAmount(@Param("savingsAccountId") Long savingsAccountId);
}
