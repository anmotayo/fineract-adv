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

/** {@code SavingsAccountData} is final with private constructors, so it is mocked (mockito-inline). */
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
    void resolvesPenaltyIncomeAccountsForAnInterestCharge() {
        final SavingsAccountTransactionEnumData transactionType = SavingsEnumerations
                .transactionType(SavingsAccountTransactionType.INTEREST_CHARGE.getValue());
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

        // Base (no-charge) mappings come from findCoreProductToFinAccountMapping; the charge-specific @Query never
        // matches a null chargeId.
        when(productToGLAccountMappingRepository.findCoreProductToFinAccountMapping(4L, 2, 2)).thenReturn(controlMapping);
        when(productToGLAccountMappingRepository.findCoreProductToFinAccountMapping(4L, 2, 5)).thenReturn(incomeMapping);

        service.selectAccountId(transactionData, savingsAccountData);

        assertThat(transactionData.getAccountDebit()).isEqualTo(101L);
        assertThat(transactionData.getAccountCredit()).isEqualTo(202L);
    }

    private static SavingsAccountData mockAccountDataForProduct(final Long productId) {
        final SavingsAccountData savingsAccountData = mock(SavingsAccountData.class);
        when(savingsAccountData.getProductId()).thenReturn(productId);
        return savingsAccountData;
    }
}
