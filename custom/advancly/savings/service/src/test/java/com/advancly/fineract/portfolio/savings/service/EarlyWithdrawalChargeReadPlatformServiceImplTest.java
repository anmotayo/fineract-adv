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

import static com.advancly.fineract.portfolio.savings.AdvanclyInterestChargeApiConstants.EARLY_WITHDRAWAL_CHARGE_PERCENTAGE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

import com.advancly.fineract.portfolio.savings.data.EarlyWithdrawalChargeData;
import com.advancly.fineract.portfolio.savings.domain.AdvanclyChargeInterestRule;
import com.advancly.fineract.portfolio.savings.domain.DepositInterestChargeApplicationRepository;
import com.advancly.fineract.portfolio.savings.domain.InterestBasisMode;
import com.advancly.fineract.portfolio.savings.testutil.MoneyHelperInitializer;
import com.advancly.fineract.portfolio.savings.testutil.SavingsAccountSummaryTestBuilder;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import org.apache.fineract.infrastructure.businessdate.domain.BusinessDateType;
import org.apache.fineract.infrastructure.configuration.domain.ConfigurationDomainService;
import org.apache.fineract.infrastructure.core.api.JsonQuery;
import org.apache.fineract.infrastructure.core.exception.GeneralPlatformDomainRuleException;
import org.apache.fineract.infrastructure.core.serialization.FromJsonHelper;
import org.apache.fineract.infrastructure.core.service.ThreadLocalContextUtil;
import org.apache.fineract.infrastructure.security.service.PlatformSecurityContext;
import org.apache.fineract.organisation.monetary.domain.MonetaryCurrency;
import org.apache.fineract.portfolio.charge.domain.Charge;
import org.apache.fineract.portfolio.savings.DepositAccountType;
import org.apache.fineract.portfolio.savings.domain.SavingsAccount;
import org.apache.fineract.portfolio.savings.domain.SavingsAccountCharge;
import org.apache.fineract.portfolio.savings.domain.SavingsAccountRepositoryWrapper;
import org.apache.fineract.portfolio.savings.domain.SavingsAccountSummary;
import org.apache.fineract.useradministration.domain.AppUser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * Covers the {@code earlyWithdrawalChargePercentage} override on the early-withdrawal-charge preview endpoint: when the
 * request body carries it, the preview must compute the charge at that percentage rather than the one configured on the
 * charge, and when it is absent the configured percentage must stay in force.
 *
 * <p>
 * The cumulative-mode cases share {@link CumulativeInterestForfeitureService}'s real percentage resolution through a
 * stubbed resolver (whose answer is asserted), while the custom-period cases let the real
 * {@link AdvanclyInterestChargeApplicationService#validatePercentageOverride} and
 * {@link AdvanclyInterestChargeApplicationService#resolvePercentage(BigDecimal, SavingsAccountCharge)} run via a
 * {@code CALLS_REAL_METHODS} partial mock, so the arithmetic asserted is genuinely the production arithmetic.
 */
class EarlyWithdrawalChargeReadPlatformServiceImplTest {

    private static final Long ACCOUNT_ID = 1L;
    private static final Long CHARGE_ID = 7L;
    private static final String CHARGE_NAME = "Early Withdrawal Penalty";
    private static final MonetaryCurrency CURRENCY = new MonetaryCurrency("USD", 2, null);
    private static final LocalDate BUSINESS_DATE = LocalDate.of(2026, 9, 25);
    private static final LocalDate WITHDRAWAL_DATE = LocalDate.of(2026, 9, 20);
    private static final LocalDate SELECTED_FROM = LocalDate.of(2026, 9, 1);
    private static final LocalDate SELECTED_TO = LocalDate.of(2026, 9, 15);

    /** What the charge itself is configured at - the value an override has to beat. */
    private static final BigDecimal CONFIGURED_PERCENTAGE = new BigDecimal("25");

    private PlatformSecurityContext context;
    private SavingsAccountRepositoryWrapper savingsAccountRepositoryWrapper;
    private ConfigurationDomainService configurationDomainService;
    private CumulativeInterestForfeitureService cumulativeInterestForfeitureService;
    private AdvanclyInterestChargeApplicationService advanclyInterestChargeApplicationService;
    private DepositInterestChargeApplicationRepository interestChargeApplicationRepository;

    private final FromJsonHelper fromJsonHelper = new FromJsonHelper();

    private EarlyWithdrawalChargeReadPlatformServiceImpl service;

    @BeforeEach
    void setUp() {
        MoneyHelperInitializer.initialize();

        final HashMap<BusinessDateType, LocalDate> businessDates = new HashMap<>();
        businessDates.put(BusinessDateType.BUSINESS_DATE, BUSINESS_DATE);
        ThreadLocalContextUtil.setBusinessDates(businessDates);

        this.context = mock(PlatformSecurityContext.class);
        final AppUser appUser = mock(AppUser.class);
        lenient().when(this.context.authenticatedUser()).thenReturn(appUser);

        this.savingsAccountRepositoryWrapper = mock(SavingsAccountRepositoryWrapper.class);
        this.configurationDomainService = mock(ConfigurationDomainService.class);
        this.cumulativeInterestForfeitureService = mock(CumulativeInterestForfeitureService.class);
        this.interestChargeApplicationRepository = mock(DepositInterestChargeApplicationRepository.class);

        // Partial mock: validatePercentageOverride(...) and resolvePercentage(...) keep their real bodies (that is the
        // behaviour under test), while the lookups they need are stubbed below.
        this.advanclyInterestChargeApplicationService = mock(AdvanclyInterestChargeApplicationService.class,
                withSettings().defaultAnswer(Mockito.CALLS_REAL_METHODS));

        this.service = new EarlyWithdrawalChargeReadPlatformServiceImpl(this.context, this.savingsAccountRepositoryWrapper,
                this.configurationDomainService, this.fromJsonHelper, this.cumulativeInterestForfeitureService,
                this.advanclyInterestChargeApplicationService, this.interestChargeApplicationRepository);
    }

    // ------------------------------------------------------------------ cumulative mode

    @Test
    void cumulativeModeUsesRequestPercentageInsteadOfTheOneConfiguredOnTheCharge() {
        final SavingsAccount account = cumulativeAccount();
        // The real resolver prefers the override and falls back to the configured percentage - mirrored here so the
        // assertion below proves the preview passes the override in, and reports back what it resolved.
        when(this.cumulativeInterestForfeitureService.resolveQualifyingChargeWithPercentage(eq(account), any())).thenAnswer(invocation -> {
            final BigDecimal override = invocation.getArgument(1);
            return new CumulativeInterestForfeitureService.QualifyingCharge(accountChargeMock(),
                    override != null ? override : CONFIGURED_PERCENTAGE);
        });

        final EarlyWithdrawalChargeData result = this.service.calculate(ACCOUNT_ID, query("\"earlyWithdrawalChargePercentage\": 50"));

        assertThat(result.chargeApplicable()).isTrue();
        assertThat(result.percentage()).isEqualByComparingTo("50");
        // 1000 earned - 0 withheld - 0 already forfeited, at 50% rather than the charge's 25%.
        assertThat(result.chargeAmount()).isEqualByComparingTo("500");
        assertThat(result.basisAmount()).isEqualByComparingTo("1000");
        verify(this.cumulativeInterestForfeitureService).resolveQualifyingChargeWithPercentage(account, new BigDecimal("50"));
    }

    @Test
    void cumulativeModeFallsBackToTheConfiguredPercentageWhenTheRequestOmitsOne() {
        final SavingsAccount account = cumulativeAccount();
        when(this.cumulativeInterestForfeitureService.resolveQualifyingChargeWithPercentage(eq(account), any())).thenAnswer(invocation -> {
            final BigDecimal override = invocation.getArgument(1);
            return new CumulativeInterestForfeitureService.QualifyingCharge(accountChargeMock(),
                    override != null ? override : CONFIGURED_PERCENTAGE);
        });

        final EarlyWithdrawalChargeData result = this.service.calculate(ACCOUNT_ID, query());

        assertThat(result.chargeApplicable()).isTrue();
        assertThat(result.percentage()).isEqualByComparingTo("25");
        assertThat(result.chargeAmount()).isEqualByComparingTo("250");
        verify(this.cumulativeInterestForfeitureService).resolveQualifyingChargeWithPercentage(account, null);
    }

    @Test
    void rejectsAnOutOfRangePercentageBeforeResolvingAnything() {
        final SavingsAccount account = cumulativeAccount();

        assertThatThrownBy(() -> this.service.calculate(ACCOUNT_ID, query("\"earlyWithdrawalChargePercentage\": 150")))
                .isInstanceOfSatisfying(GeneralPlatformDomainRuleException.class, exception -> {
                    assertThat(exception.getGlobalisationMessageCode())
                            .isEqualTo("error.msg.savings.account.early.withdrawal.charge.percentage.invalid");
                    assertThat(exception.getDefaultUserMessage()).isEqualTo("earlyWithdrawalChargePercentage must be between 0 and 100.");
                });

        verify(this.advanclyInterestChargeApplicationService, never()).resolveSingleRule(account);
        verify(this.cumulativeInterestForfeitureService, never()).resolveQualifyingChargeWithPercentage(any(), any());
    }

    @Test
    void acceptsTheBoundaryPercentages() {
        cumulativeAccount();

        // 0 and 100 are the inclusive bounds the validator documents; both must get past it.
        assertThat(this.service.calculate(ACCOUNT_ID, query("\"earlyWithdrawalChargePercentage\": 0"))).isNotNull();
        assertThat(this.service.calculate(ACCOUNT_ID, query("\"earlyWithdrawalChargePercentage\": 100"))).isNotNull();
    }

    // ------------------------------------------------------------------ custom-period mode

    @Test
    void customPeriodModeRecomputesTheChargeAtTheRequestPercentage() {
        final SavingsAccount account = customPeriodAccount();

        final EarlyWithdrawalChargeData result = this.service.calculate(ACCOUNT_ID,
                query("\"selectedFromDate\": \"2026-09-01\", \"selectedToDate\": \"2026-09-15\", \"earlyWithdrawalChargePercentage\": 50"));

        assertThat(result.chargeApplicable()).isTrue();
        assertThat(result.interestBasisMode()).isEqualTo(InterestBasisMode.CUSTOM_PERIOD.name());
        assertThat(result.percentage()).isEqualByComparingTo("50");
        // 1000 net interest still chargeable, at 50% rather than the charge's 25%.
        assertThat(result.basisAmount()).isEqualByComparingTo("1000");
        assertThat(result.chargeAmount()).isEqualByComparingTo("500");
        assertThat(result.selectedFromDate()).isEqualTo(SELECTED_FROM);
        assertThat(result.selectedToDate()).isEqualTo(SELECTED_TO);
    }

    @Test
    void customPeriodModeFallsBackToThePercentageConfiguredOnTheCharge() {
        final SavingsAccount account = customPeriodAccount();

        final EarlyWithdrawalChargeData result = this.service.calculate(ACCOUNT_ID,
                query("\"selectedFromDate\": \"2026-09-01\", \"selectedToDate\": \"2026-09-15\""));

        assertThat(result.chargeApplicable()).isTrue();
        assertThat(result.percentage()).isEqualByComparingTo("25");
        assertThat(result.chargeAmount()).isEqualByComparingTo("250");
    }

    // ------------------------------------------------------------------ fixtures

    private SavingsAccountCharge accountChargeMock() {
        final Charge charge = mock(Charge.class);
        lenient().when(charge.getId()).thenReturn(CHARGE_ID);
        lenient().when(charge.getName()).thenReturn(CHARGE_NAME);
        lenient().when(charge.getAmount()).thenReturn(CONFIGURED_PERCENTAGE);

        final SavingsAccountCharge accountCharge = mock(SavingsAccountCharge.class);
        lenient().when(accountCharge.getCharge()).thenReturn(charge);
        lenient().when(accountCharge.getPercentage()).thenReturn(CONFIGURED_PERCENTAGE);
        return accountCharge;
    }

    private SavingsAccountSummary summaryWith(final BigDecimal totalInterestEarned, final BigDecimal totalWithholdTax) {
        final SavingsAccountSummary summary = Mockito
                .spy(new SavingsAccountSummaryTestBuilder().withAccountBalance(BigDecimal.valueOf(1000)).build());
        lenient().when(summary.getTotalInterestEarned()).thenReturn(totalInterestEarned);
        lenient().when(summary.getTotalWithholdTax()).thenReturn(totalWithholdTax);
        return summary;
    }

    private SavingsAccount accountWith(final SavingsAccountSummary summary) {
        final SavingsAccount account = mock(SavingsAccount.class);
        lenient().when(account.getId()).thenReturn(ACCOUNT_ID);
        lenient().when(account.productId()).thenReturn(20L);
        lenient().when(account.getCurrency()).thenReturn(CURRENCY);
        lenient().when(account.depositAccountType()).thenReturn(DepositAccountType.SAVINGS_DEPOSIT);
        lenient().when(account.getSummary()).thenReturn(summary);
        lenient().when(account.calculateInterestUsing(any(), any(), anyBoolean(), anyBoolean(), any(), any(), anyBoolean(), anyBoolean()))
                .thenReturn(null);
        lenient().when(this.savingsAccountRepositoryWrapper.findOneWithNotFoundDetection(ACCOUNT_ID)).thenReturn(account);
        return account;
    }

    /** An account whose product's single rule is cumulative, with 1000 earned and nothing withheld or forfeited yet. */
    private SavingsAccount cumulativeAccount() {
        final SavingsAccount account = accountWith(summaryWith(new BigDecimal("1000"), BigDecimal.ZERO));
        final AdvanclyChargeInterestRule rule = mock(AdvanclyChargeInterestRule.class);
        lenient().when(rule.isCumulative()).thenReturn(true);
        // doReturn(...).when(...), not when(...).thenReturn(...): on a CALLS_REAL_METHODS partial mock the latter would
        // invoke the real lookup (and NPE on the unset repository) while merely *recording* the stub.
        lenient().doReturn(rule).when(this.advanclyInterestChargeApplicationService).resolveSingleRule(account);
        lenient().when(this.cumulativeInterestForfeitureService.forcedPostingDate(eq(account), any(), anyBoolean()))
                .thenReturn(WITHDRAWAL_DATE);
        lenient().when(this.cumulativeInterestForfeitureService.sumActiveAppliedAmount(ACCOUNT_ID)).thenReturn(BigDecimal.ZERO);
        return account;
    }

    /** An account whose product's single rule is custom-period, with 1000 net interest still chargeable. */
    private SavingsAccount customPeriodAccount() {
        final SavingsAccount account = accountWith(summaryWith(BigDecimal.ZERO, BigDecimal.ZERO));
        final AdvanclyChargeInterestRule rule = mock(AdvanclyChargeInterestRule.class);
        lenient().when(rule.chargeId()).thenReturn(CHARGE_ID);
        lenient().when(rule.isCumulative()).thenReturn(false);
        lenient().when(rule.isCustomPeriod()).thenReturn(true);
        lenient().when(rule.customPeriodReapplyPolicy()).thenReturn(null);
        lenient().when(rule.interestBasisMode()).thenReturn(InterestBasisMode.CUSTOM_PERIOD);

        lenient().doReturn(rule).when(this.advanclyInterestChargeApplicationService).resolveSingleRule(account);
        lenient().doReturn(accountChargeMock()).when(this.advanclyInterestChargeApplicationService).resolveAccountCharge(account,
                CHARGE_ID);
        doNothing().when(this.advanclyInterestChargeApplicationService).validateDailyPostingPeriod(account);
        lenient().doReturn(SELECTED_FROM).when(this.advanclyInterestChargeApplicationService).selectedFromDate(eq(account), any(), any(),
                eq(rule));
        lenient().doReturn(SELECTED_TO).when(this.advanclyInterestChargeApplicationService).selectedToDate(any(), any(), eq(rule));
        lenient().doReturn(new BigDecimal("1000")).when(this.advanclyInterestChargeApplicationService).selectedPostedNetInterest(account,
                SELECTED_FROM, SELECTED_TO);
        lenient().when(this.interestChargeApplicationRepository.sumActiveAppliedAmountForSelectedPeriod(ACCOUNT_ID, CHARGE_ID,
                SELECTED_FROM, SELECTED_TO)).thenReturn(BigDecimal.ZERO);
        return account;
    }

    /**
     * A request body carrying the {@code withdrawalDate} every case needs, the {@code locale}/{@code dateFormat} pair
     * Fineract requires alongside any date parameter, plus whatever {@code extraFields} the case under test adds.
     */
    private JsonQuery query(final String... extraFields) {
        final StringBuilder json = new StringBuilder(
                "{\"locale\": \"en\", \"dateFormat\": \"yyyy-MM-dd\", \"withdrawalDate\": \"2026-09-20\"");
        for (final String field : extraFields) {
            json.append(", ").append(field);
        }
        json.append('}');
        final String body = json.toString();
        return JsonQuery.from(body, this.fromJsonHelper.parse(body), this.fromJsonHelper);
    }

    /** Guards the field name the endpoint documents, so the constant and the wire format cannot drift apart. */
    @Test
    void overrideFieldIsNamedAsDocumented() {
        assertThat(EARLY_WITHDRAWAL_CHARGE_PERCENTAGE).isEqualTo("earlyWithdrawalChargePercentage");
    }
}
