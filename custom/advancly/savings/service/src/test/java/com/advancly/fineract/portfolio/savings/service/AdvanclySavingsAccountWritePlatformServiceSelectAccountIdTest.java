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
package com.advancly.fineract.portfolio.savings.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import org.apache.fineract.accounting.glaccount.domain.GLAccount;
import org.apache.fineract.accounting.producttoaccountmapping.domain.ProductToGLAccountMapping;
import org.apache.fineract.accounting.producttoaccountmapping.domain.ProductToGLAccountMappingRepository;
import org.apache.fineract.portfolio.savings.SavingsAccountTransactionType;
import org.apache.fineract.portfolio.savings.data.SavingsAccountData;
import org.apache.fineract.portfolio.savings.data.SavingsAccountTransactionData;
import org.apache.fineract.portfolio.savings.data.SavingsAccountTransactionEnumData;
import org.apache.fineract.portfolio.savings.service.SavingsEnumerations;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * {@code SavingsAccountData} has only private constructors (all instantiation happens through its own static factory
 * methods), and no test in this module instantiates a real one - see this class's own investigation notes on
 * {@link #mockAccountDataForProduct(Long)} below. Mocking it directly with Mockito is therefore the only option, and is
 * safe here: {@code custom/advancly/savings/service} is one of the dynamically-loaded {@code fineractCustomProjects}
 * modules, which all pull in {@code mockito-inline} (see the root {@code build.gradle}), so the inline mock maker -
 * needed to mock a {@code final} class like {@code SavingsAccountData} - is already on this module's test classpath.
 */
@ExtendWith(MockitoExtension.class)
class AdvanclySavingsAccountWritePlatformServiceSelectAccountIdTest {

    @Mock
    private ProductToGLAccountMappingRepository productToGLAccountMappingRepository;
    @Mock
    private GLAccount savingsControlAccount;
    @Mock
    private GLAccount incomeFromPenaltiesAccount;

    @InjectMocks
    private AdvanclySavingsAccountWritePlatformService service;

    @Test
    void resolvesPenaltyIncomeAccountsForAnInterestBasedCharge() {
        final SavingsAccountTransactionEnumData transactionType = SavingsEnumerations
                .transactionType(SavingsAccountTransactionType.INTEREST_BASED_CHARGE.getValue());
        final SavingsAccountTransactionData transactionData = SavingsAccountTransactionData.create(null, transactionType, null, 1L,
                "000001", LocalDate.of(2026, 1, 31), null, BigDecimal.TEN, null, null, false, null, LocalDate.of(2026, 1, 31), false, null,
                null, null, null, null, null, null, OffsetDateTime.now());

        final SavingsAccountData savingsAccountData = mockAccountDataForProduct(4L);
        final ProductToGLAccountMapping controlMapping = mock(ProductToGLAccountMapping.class);
        when(controlMapping.getGlAccount()).thenReturn(savingsControlAccount);
        when(savingsControlAccount.getId()).thenReturn(101L);
        final ProductToGLAccountMapping incomeMapping = mock(ProductToGLAccountMapping.class);
        when(incomeMapping.getGlAccount()).thenReturn(incomeFromPenaltiesAccount);
        when(incomeFromPenaltiesAccount.getId()).thenReturn(202L);

        // findProductIdAndProductTypeAndFinancialAccountTypeAndChargeId(...) is a hand-written @Query with an
        // explicit "mapping.charge.id = :chargeId" comparison, not a Spring Data derived query - so a null chargeId
        // does NOT get rewritten to "IS NULL" the way a derived findByChargeIsNull(...) method would. In JPQL (as in
        // SQL) "x = NULL" is never true, so calling that method with charge=null can never match a row, no matter
        // what data exists. Every existing caller of it (AccountingProcessorHelper, for loan/savings/share charges)
        // only ever passes an actual non-null charge id for a charge-specific override; there is no null-charge-id
        // precedent anywhere in this codebase. The base, no-charge SAVINGS_CONTROL/INCOME_FROM_PENALTIES mappings
        // that a savings product carries (see SavingsProductToGLAccountMappingHelper's
        // mergeSavingsToLiabilityAccountMappingChanges/mergeSavingsToIncomeAccountMappingChanges) are created without
        // a charge attached, and are exactly what findCoreProductToFinAccountMapping(...) is documented and used
        // everywhere else in core to look up ("paymentType is NULL and charge is NULL..."). So this test stubs
        // findCoreProductToFinAccountMapping(...), matching the corrected production code.
        when(productToGLAccountMappingRepository.findCoreProductToFinAccountMapping(4L, 2, 2)).thenReturn(controlMapping);
        when(productToGLAccountMappingRepository.findCoreProductToFinAccountMapping(4L, 2, 5)).thenReturn(incomeMapping);

        service.selectAccountId(transactionData, savingsAccountData);

        assertThat(transactionData.getAccountDebit()).isEqualTo(101L);
        assertThat(transactionData.getAccountCredit()).isEqualTo(202L);
    }

    /**
     * {@code SavingsAccountData}'s constructors are all {@code private}; every instance in production code is built
     * through one of the class's own static factory methods (e.g. {@code importInstanceIndividual},
     * {@code instance(...)}), none of which offers a convenient shape for a bare product-id fixture, and no existing
     * test anywhere in this module or the wider codebase constructs or mocks one. Since the class is also
     * {@code public final}, mocking it needs Mockito's inline mock maker, which {@code mockito-inline} (already a
     * {@code fineractCustomProjects} test dependency, see the root {@code build.gradle}) provides. Mocking it directly
     * is therefore the simplest correct option, and only {@code getProductId()} needs stubbing for this test's lookups
     * to be hit with the right product id.
     */
    private static SavingsAccountData mockAccountDataForProduct(final Long productId) {
        final SavingsAccountData savingsAccountData = mock(SavingsAccountData.class);
        when(savingsAccountData.getProductId()).thenReturn(productId);
        return savingsAccountData;
    }
}
