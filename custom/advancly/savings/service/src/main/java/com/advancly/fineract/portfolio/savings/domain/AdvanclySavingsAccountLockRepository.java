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

import java.util.Optional;
import org.apache.fineract.portfolio.savings.domain.SavingsAccount;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;

/**
 * Loads a savings account under a row lock without hydrating its transactions (core's PESSIMISTIC_WRITE findOneLocked
 * makes EclipseLink load the whole history). A native {@code for update} is a current read that sees the latest
 * committed row and {@code @Version} (a plain read would use the stale REPEATABLE-READ snapshot), yet stays lazy.
 */
public interface AdvanclySavingsAccountLockRepository extends Repository<SavingsAccount, Long> {

    @Query(value = "select * from m_savings_account where id = ?1 for update", nativeQuery = true)
    Optional<SavingsAccount> findByIdForUpdate(Long savingsId);
}
