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

import java.lang.reflect.Method;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.Query;

/**
 * Review finding I2: a row whose interest-based charge transaction was created and then reversed must count as PENDING
 * again, not fall into neither the pending nor the posted bucket - otherwise the account's derived
 * interest-based-charge columns silently drop that amount forever and the reversal effectively forgives the charge
 * (contradicting {@code DynamicDepositAccount.postInterest}'s own documented assumption that "a genuinely reversed
 * charge would correctly return to pending").
 *
 * This module has no JPA/persistence test infrastructure wired up for it (no {@code @DataJpaTest}, and no H2 or
 * Testcontainers dependency is on this module's test classpath - unlike, say, {@code fineract-command}, which pulls in
 * a real Testcontainers database purely to test its own repository). Building that out is a much larger change than
 * this fix warrants, so these assertions instead pin the exact JPQL predicate on the three affected query methods, read
 * straight off their {@code @Query} annotations, rather than executing them against a database.
 */
class SavingsAccountInterestChargeRepositoryTest {

    private static final String REVERSED_CHARGE_COUNTS_AS_PENDING = "(c.interestChargeTransaction is null or c.interestChargeTransaction.reversed = true)";

    @Test
    void findPendingByAccountIdUpToTreatsARowWithAReversedChargeTransactionAsPending() throws NoSuchMethodException {
        final String jpql = queryFor("findPendingByAccountIdUpTo", Long.class, LocalDate.class);

        assertThat(jpql).contains(REVERSED_CHARGE_COUNTS_AS_PENDING);
        // The other two predicates (interest period boundary, withdrawal-reversal exclusion) must survive untouched.
        assertThat(jpql).contains("c.interestPeriodEndDate <= :upToDate");
        assertThat(jpql).contains("c.withdrawalTransaction.reversed = false");
    }

    @Test
    void sumPendingChargeAmountTreatsARowWithAReversedChargeTransactionAsPending() throws NoSuchMethodException {
        final String jpql = queryFor("sumPendingChargeAmount", Long.class);

        assertThat(jpql).contains(REVERSED_CHARGE_COUNTS_AS_PENDING);
        assertThat(jpql).contains("c.withdrawalTransaction.reversed = false");
    }

    @Test
    void sumPostedChargeAmountStillExcludesARowWithAReversedChargeTransaction() throws NoSuchMethodException {
        final String jpql = queryFor("sumPostedChargeAmount", Long.class);

        // Unchanged by this fix: a row is posted only while its charge transaction stands (exists and not reversed).
        assertThat(jpql).contains("c.interestChargeTransaction is not null and c.interestChargeTransaction.reversed = false");
        // A reversed-charge row must never be counted as pending AND posted at once.
        assertThat(jpql).doesNotContain("interestChargeTransaction is null or");
    }

    private String queryFor(final String methodName, final Class<?>... parameterTypes) throws NoSuchMethodException {
        final Method method = SavingsAccountInterestChargeRepository.class.getMethod(methodName, parameterTypes);
        final Query query = method.getAnnotation(Query.class);
        assertThat(query).as("@Query annotation on %s", methodName).isNotNull();
        return query.value();
    }
}
