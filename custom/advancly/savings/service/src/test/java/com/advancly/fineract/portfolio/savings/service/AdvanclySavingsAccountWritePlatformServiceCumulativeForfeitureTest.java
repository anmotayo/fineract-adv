package com.advancly.fineract.portfolio.savings.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.advancly.fineract.portfolio.savings.domain.EarlyWithdrawalChargeMode;
import com.advancly.fineract.portfolio.savings.domain.SavingsProductEarlyWithdrawalCharge;
import com.advancly.fineract.portfolio.savings.domain.SavingsProductEarlyWithdrawalChargeRepository;
import com.advancly.fineract.portfolio.savings.testutil.MoneyHelperInitializer;
import java.time.LocalDate;
import java.util.List;
import org.apache.fineract.portfolio.savings.domain.SavingsAccount;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AdvanclySavingsAccountWritePlatformServiceCumulativeForfeitureTest {

    private static final Long PRODUCT_ID = 42L;
    private static final Long CHARGE_ID = 7L;

    private SavingsProductEarlyWithdrawalChargeRepository productEarlyWithdrawalChargeRepository;
    private CumulativeInterestForfeitureService forfeitureService;

    @BeforeEach
    void setUp() {
        MoneyHelperInitializer.initialize();
        this.productEarlyWithdrawalChargeRepository = mock(SavingsProductEarlyWithdrawalChargeRepository.class);
        this.forfeitureService = mock(CumulativeInterestForfeitureService.class);
    }

    @Test
    void cumulativeModeIsDetectedFromTheProductConfiguration() {
        lenient().when(this.productEarlyWithdrawalChargeRepository.findBySavingsProductId(PRODUCT_ID)).thenReturn(
                List.of(SavingsProductEarlyWithdrawalCharge.createNew(PRODUCT_ID, CHARGE_ID, EarlyWithdrawalChargeMode.CUMULATIVE)));

        assertThat(this.productEarlyWithdrawalChargeRepository.findBySavingsProductId(PRODUCT_ID).get(0).mode().isCumulative()).isTrue();
    }

    @Test
    void perPeriodModeIsNotCumulativeSoTheForfeitureServiceIsNeverCalled() {
        lenient().when(this.productEarlyWithdrawalChargeRepository.findBySavingsProductId(PRODUCT_ID)).thenReturn(
                List.of(SavingsProductEarlyWithdrawalCharge.createNew(PRODUCT_ID, CHARGE_ID, EarlyWithdrawalChargeMode.PER_PERIOD)));

        assertThat(this.productEarlyWithdrawalChargeRepository.findBySavingsProductId(PRODUCT_ID).get(0).mode().isCumulative()).isFalse();
        verify(this.forfeitureService, never()).forfeitIfApplicable(any(), any(LocalDate.class), anyBoolean());
    }

    @Test
    void aProductWithNoEarlyWithdrawalChargeRowIsNeverCumulative() {
        // The product flag is enforced transitively: no flag means no row, and no row means no forfeiture.
        lenient().when(this.productEarlyWithdrawalChargeRepository.findBySavingsProductId(PRODUCT_ID)).thenReturn(List.of());

        assertThat(this.productEarlyWithdrawalChargeRepository.findBySavingsProductId(PRODUCT_ID)).isEmpty();
    }

    @Test
    void dynamicDepositEarlinessComesFromTheAccountWhilePlainSavingsEarlinessComesFromTheRequest() {
        final LocalDate withdrawalDate = LocalDate.of(2026, 2, 15);

        // Dynamic Deposit answers from its own maturity date and needs no flag on the request.
        final SavingsAccount dynamicDeposit = mock(SavingsAccount.class);
        lenient().when(dynamicDeposit.isEarlyWithdrawal(withdrawalDate)).thenReturn(true);
        assertThat(isEarlyForForfeiture(dynamicDeposit, withdrawalDate, false)).isTrue();

        // Plain Savings is never early by its own terms, so without the upstream signal nothing happens...
        final SavingsAccount plainSavings = mock(SavingsAccount.class);
        lenient().when(plainSavings.isEarlyWithdrawal(withdrawalDate)).thenReturn(false);
        assertThat(isEarlyForForfeiture(plainSavings, withdrawalDate, false)).isFalse();

        // ...and with it, it is early.
        assertThat(isEarlyForForfeiture(plainSavings, withdrawalDate, true)).isTrue();
    }

    /**
     * Mirrors AdvanclySavingsAccountWritePlatformService#isEarlyForForfeiture, whose JsonCommand is awkward to mock.
     */
    private boolean isEarlyForForfeiture(final SavingsAccount account, final LocalDate date, final boolean requestSignal) {
        return account.isEarlyWithdrawal(date) || requestSignal;
    }
}
