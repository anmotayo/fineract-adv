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

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface DynamicDepositAccountRepository
        extends JpaRepository<DynamicDepositAccount, Long>, JpaSpecificationExecutor<DynamicDepositAccount> {

    /**
     * Backs {@link com.advancly.fineract.portfolio.savings.job.DynamicDepositPostInterestTasklet} (Task 7) - the
     * scheduled counterpart to the shared, DTO-based bulk posting job, which excludes Dynamic Deposit accounts
     * (deposit_type_enum = 500) so they are posted here instead, through the JPA-entity path.
     */
    @Query("select a.id from DynamicDepositAccount a where a.status = :statusEnum")
    List<Long> findIdsByStatus(@Param("statusEnum") Integer statusEnum);
}
