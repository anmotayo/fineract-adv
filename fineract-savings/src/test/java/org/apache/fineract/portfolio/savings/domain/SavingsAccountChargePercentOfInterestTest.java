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
package org.apache.fineract.portfolio.savings.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.HashMap;
import org.apache.fineract.infrastructure.businessdate.domain.BusinessDateType;
import org.apache.fineract.infrastructure.core.domain.ActionContext;
import org.apache.fineract.infrastructure.core.domain.FineractPlatformTenant;
import org.apache.fineract.infrastructure.core.service.ThreadLocalContextUtil;
import org.apache.fineract.organisation.monetary.domain.MonetaryCurrency;
import org.apache.fineract.organisation.monetary.domain.Money;
import org.apache.fineract.organisation.monetary.domain.MoneyHelper;
import org.apache.fineract.portfolio.charge.domain.Charge;
import org.apache.fineract.portfolio.charge.domain.ChargeCalculationType;
import org.apache.fineract.portfolio.charge.domain.ChargeTimeType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Guards the PERCENT_OF_INTEREST arms of {@link SavingsAccountCharge}. Enabling that calculation type for savings
 * (commit f51316b8f) made a previously unreachable code path reachable: it nulled {@code amount}, which the constructor
 * immediately dereferences through {@code determineIfFullyPaid()} -> {@code calculateOutstanding()}.
 */
class SavingsAccountChargePercentOfInterestTest {

    private FineractPlatformTenant originalTenant;

    @BeforeEach
    void setUp() {
        this.originalTenant = ThreadLocalContextUtil.getTenant();
        ThreadLocalContextUtil
                .setTenant(FineractPlatformTenant.builder().id(1L).tenantIdentifier("default").name("Default").timezoneId("UTC").build());
        MoneyHelper.initializeTenantRoundingMode("default", RoundingMode.HALF_EVEN.ordinal());

        final HashMap<BusinessDateType, LocalDate> businessDates = new HashMap<>();
        businessDates.put(BusinessDateType.BUSINESS_DATE, LocalDate.now());
        businessDates.put(BusinessDateType.COB_DATE, LocalDate.now().minusDays(1));
        ThreadLocalContextUtil.setActionContext(ActionContext.DEFAULT);
        ThreadLocalContextUtil.setBusinessDates(businessDates);
    }

    @AfterEach
    void tearDown() {
        ThreadLocalContextUtil.setTenant(this.originalTenant);
        MoneyHelper.clearCache();
        ThreadLocalContextUtil.reset();
    }

    @Test
    void aPercentOfInterestChargeCanBeConstructedWithoutThrowing() {
        assertThatCode(() -> percentOfInterestCharge(new BigDecimal("5"))).doesNotThrowAnyException();
    }

    @Test
    void theConfiguredPercentageIsPreservedAndReadableBack() {
        final SavingsAccountCharge charge = percentOfInterestCharge(new BigDecimal("5"));

        assertThat(charge.getPercentage()).isEqualByComparingTo("5");
    }

    @Test
    void anAccountLevelOverrideWinsOverTheChargeDefinitionAmount() {
        final SavingsAccountCharge charge = percentOfInterestCharge(new BigDecimal("7.5"));

        assertThat(charge.getPercentage()).isEqualByComparingTo("7.5");
    }

    @Test
    void amountAndOutstandingAreZeroRatherThanNullSoBothNotNullColumnsStayValid() {
        final SavingsAccountCharge charge = percentOfInterestCharge(new BigDecimal("5"));

        assertThat(charge.amount()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(charge.amoutOutstanding()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void aFlatChargeStillHasNoPercentage() {
        final Charge definition = mock(Charge.class);
        lenient().when(definition.getAmount()).thenReturn(new BigDecimal("100"));
        lenient().when(definition.getChargeCalculation()).thenReturn(ChargeCalculationType.FLAT.getValue());

        final SavingsAccountCharge charge = SavingsAccountCharge.createNewWithoutSavingsAccount(definition, new BigDecimal("100"),
                ChargeTimeType.SAVINGS_ACTIVATION, ChargeCalculationType.FLAT, null, true, null, null);

        assertThat(charge.getPercentage()).isNull();
        assertThat(charge.amount()).isEqualByComparingTo("100");
    }

    @Test
    void payingAnExternallyComputedAmountWithoutRefreshingOutstandingFirstDrivesItNegative() {
        final SavingsAccountCharge charge = percentOfInterestCharge(new BigDecimal("50"));
        final MonetaryCurrency currency = new MonetaryCurrency("USD", 2, null);

        // amountOutstanding is pinned at 0 between applications (see amountAndOutstandingAreZeroRatherThanNull...
        // above) - paying an externally-computed amount straight against that, without refreshing it first, is
        // exactly the bug: it goes negative instead of landing on zero.
        charge.pay(currency, Money.of(currency, new BigDecimal("37.50")));

        assertThat(charge.amoutOutstanding()).isEqualByComparingTo("-37.50");
    }

    @Test
    void payExternallyComputedChargeLandsExactlyOnZeroInsteadOfGoingNegative() {
        final SavingsAccountCharge charge = percentOfInterestCharge(new BigDecimal("50"));
        final MonetaryCurrency currency = new MonetaryCurrency("USD", 2, null);

        charge.payExternallyComputedCharge(currency, new BigDecimal("37.50"));

        assertThat(charge.amoutOutstanding()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(charge.isPaid()).isTrue();
    }

    @Test
    void payExternallyComputedChargeStillAccumulatesAmountPaidAsALifetimeTotalAcrossApplications() {
        final SavingsAccountCharge charge = percentOfInterestCharge(new BigDecimal("50"));
        final MonetaryCurrency currency = new MonetaryCurrency("USD", 2, null);

        charge.payExternallyComputedCharge(currency, new BigDecimal("37.50"));
        charge.payExternallyComputedCharge(currency, new BigDecimal("42.00"));
        charge.payExternallyComputedCharge(currency, new BigDecimal("10.00"));

        // amount/amountOutstanding only ever reflect the latest application, but amountPaid keeps the running
        // lifetime total across all three - the tracking amountPaid alone would lose if it were reset per
        // application.
        assertThat(charge.amount()).isEqualByComparingTo("10.00");
        assertThat(charge.amoutOutstanding()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(charge.amountPaid()).isEqualByComparingTo("89.50");
        assertThat(charge.isPaid()).isTrue();
    }

    private SavingsAccountCharge percentOfInterestCharge(final BigDecimal percentage) {
        final Charge definition = mock(Charge.class);
        lenient().when(definition.getAmount()).thenReturn(new BigDecimal("2"));
        lenient().when(definition.getChargeCalculation()).thenReturn(ChargeCalculationType.PERCENT_OF_INTEREST.getValue());

        return SavingsAccountCharge.createNewWithoutSavingsAccount(definition, percentage, ChargeTimeType.SAVINGS_ACTIVATION,
                ChargeCalculationType.PERCENT_OF_INTEREST, null, true, null, null);
    }
}
