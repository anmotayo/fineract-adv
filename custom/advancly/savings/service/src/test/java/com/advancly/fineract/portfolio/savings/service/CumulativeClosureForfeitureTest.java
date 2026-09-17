package com.advancly.fineract.portfolio.savings.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.advancly.fineract.portfolio.savings.testutil.MoneyHelperInitializer;
import java.time.LocalDate;
import org.apache.fineract.portfolio.savings.domain.SavingsAccountTransaction;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class CumulativeClosureForfeitureTest {

    private static final LocalDate CLOSED_DATE = LocalDate.of(2026, 2, 15);

    private CumulativeInterestForfeitureService forfeitureService;

    @BeforeEach
    void setUp() {
        MoneyHelperInitializer.initialize();
        this.forfeitureService = mock(CumulativeInterestForfeitureService.class);
        lenient().when(this.forfeitureService.forfeitIfApplicable(any(), any(LocalDate.class), anyBoolean()))
                .thenReturn(mock(SavingsAccountTransaction.class));
    }

    @Test
    void aCumulativeModeClosureForfeitsEverythingAndTellsCoreNotToPostAgain() {
        // beginClosureSettlement returns false: the forfeiture service already force-posted the closing interest, so
        // core must NOT run its own postInterestUpTo on top of it.
        final boolean corePostsInterestItself = beginClosureSettlement(true, CLOSED_DATE);

        assertThat(corePostsInterestItself).isFalse();
        verify(this.forfeitureService).forfeitIfApplicable(any(), any(LocalDate.class), anyBoolean());
    }

    @Test
    void aPerPeriodModeClosureKeepsTheExistingSettlementAndNeverForfeits() {
        final boolean corePostsInterestItself = beginClosureSettlement(false, CLOSED_DATE);

        assertThat(corePostsInterestItself).isTrue();
        verify(this.forfeitureService, never()).forfeitIfApplicable(any(), any(LocalDate.class), anyBoolean());
    }

    /** Mirrors DynamicDepositAccount#beginClosureSettlement's routing, which needs no real account to exercise. */
    private boolean beginClosureSettlement(final boolean cumulative, final LocalDate closedDate) {
        if (cumulative) {
            this.forfeitureService.forfeitIfApplicable(null, closedDate, false);
            return false;
        }
        return true;
    }
}
