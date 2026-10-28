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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.advancly.fineract.portfolio.savings.domain.DynamicDepositAccount;
import com.advancly.fineract.portfolio.savings.testutil.MoneyHelperInitializer;
import java.math.BigDecimal;
import java.math.MathContext;
import java.time.LocalDate;
import org.apache.fineract.infrastructure.configuration.domain.ConfigurationDomainService;
import org.apache.fineract.portfolio.savings.domain.DepositAccountTermAndPreClosure;
import org.apache.fineract.portfolio.savings.domain.SavingsAccountStatusType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

class DynamicDepositMaturityAmountServiceTest {

    private static final LocalDate MATURITY_DATE = LocalDate.of(2030, 1, 1);

    private DynamicDepositMaturityAmountService service;
    private DynamicDepositAccount account;
    private DepositAccountTermAndPreClosure term;

    @BeforeEach
    void setUp() {
        MoneyHelperInitializer.initialize();
        final ConfigurationDomainService configurationDomainService = mock(ConfigurationDomainService.class);
        when(configurationDomainService.retrieveFinancialYearBeginningMonth()).thenReturn(1);
        this.service = new DynamicDepositMaturityAmountService(configurationDomainService);

        this.term = mock(DepositAccountTermAndPreClosure.class);
        this.account = mock(DynamicDepositAccount.class);
        when(this.account.accountTermAndPreClosure()).thenReturn(this.term);
        when(this.account.maturityDate()).thenReturn(MATURITY_DATE);
        when(this.account.getStatus()).thenReturn(SavingsAccountStatusType.ACTIVE);
        when(this.account.grossMaturityAmountAfterProjection()).thenReturn(new BigDecimal("1234.56"));
    }

    @Test
    void storesTheGrossProjectionToMaturityThenRerunsTheEngineUpToToday() {
        assertThat(this.service.storeProjectedMaturityAmount(this.account)).isTrue();

        verify(this.term).updateMaturityDetails(new BigDecimal("1234.56"), MATURITY_DATE);
        final InOrder inOrder = inOrder(this.account);
        inOrder.verify(this.account).calculateInterestUsing(any(MathContext.class), eq(MATURITY_DATE), anyBoolean(), anyBoolean(), any(),
                any(), anyBoolean(), anyBoolean());
        inOrder.verify(this.account).grossMaturityAmountAfterProjection();
        inOrder.verify(this.account).calculateInterestUsing(any(MathContext.class),
                eq(org.apache.fineract.infrastructure.core.service.DateUtils.getBusinessLocalDate()), anyBoolean(), anyBoolean(), any(),
                any(), anyBoolean(), anyBoolean());
    }

    @Test
    void leavesAnAccountThatIsNotActiveAlone() {
        when(this.account.getStatus()).thenReturn(SavingsAccountStatusType.MATURED);

        assertThat(this.service.storeProjectedMaturityAmount(this.account)).isFalse();
        verify(this.term, never()).updateMaturityDetails(any(), any());
    }

    @Test
    void leavesAnAccountWithoutAMaturityDateAlone() {
        when(this.account.maturityDate()).thenReturn(null);

        assertThat(this.service.storeProjectedMaturityAmount(this.account)).isFalse();
        verify(this.term, never()).updateMaturityDetails(any(), any());
    }
}
