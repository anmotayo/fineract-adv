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

import com.advancly.fineract.portfolio.savings.data.InterestCalculationData;
import com.advancly.fineract.portfolio.savings.data.InterestCalculationTransactionData;
import com.advancly.fineract.portfolio.savings.data.PostingPeriodData;
import com.advancly.fineract.portfolio.savings.domain.SavingsAccountInterestChargeRepository;
import com.advancly.fineract.portfolio.savings.domain.DynamicDepositAccount;
import java.math.BigDecimal;
import java.math.MathContext;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.apache.fineract.infrastructure.configuration.domain.ConfigurationDomainService;
import org.apache.fineract.infrastructure.core.service.DateUtils;
import org.apache.fineract.infrastructure.security.service.PlatformSecurityContext;
import org.apache.fineract.organisation.monetary.domain.Money;
import org.apache.fineract.organisation.monetary.domain.MoneyHelper;
import org.apache.fineract.portfolio.savings.DepositAccountType;
import org.apache.fineract.portfolio.savings.domain.FixedDepositAccount;
import org.apache.fineract.portfolio.savings.domain.RecurringDepositAccount;
import org.apache.fineract.portfolio.savings.domain.SavingsAccount;
import org.apache.fineract.portfolio.savings.domain.SavingsAccountRepositoryWrapper;
import org.apache.fineract.portfolio.savings.domain.SavingsAccountTransaction;
import org.apache.fineract.portfolio.savings.domain.interest.PostingPeriod;
import org.apache.fineract.portfolio.savings.service.SavingsEnumerations;
import org.springframework.stereotype.Service;

/**
 * Backs {@code GET /interestcalculation/{savingsAccountId}} (see {@link InterestCalculationReadPlatformService}).
 *
 * Deliberately has NO {@code @Transactional} annotation of its own. {@link SavingsAccountRepositoryWrapper}'s own
 * {@code findOneWithNotFoundDetection(Long)} is independently {@code @Transactional(readOnly = true)}; called with no
 * outer transaction already open, that method starts and commits its own transaction before returning, which closes
 * the Hibernate session and detaches the entity it just loaded. Every mutation this class makes afterwards - adding a
 * simulated transaction, running {@code calculateInterestUsing(...)} (which rewrites daily balances and the in-memory
 * summary as a side effect) - therefore happens on an object nothing is tracking for a flush. This is the whole
 * safety guarantee behind "this endpoint never persists anything," so don't add {@code @Transactional} here without
 * re-deriving that guarantee some other way.
 */
@Service
@RequiredArgsConstructor
public class InterestCalculationReadPlatformServiceImpl implements InterestCalculationReadPlatformService {

    private final PlatformSecurityContext context;
    private final SavingsAccountRepositoryWrapper savingsAccountRepositoryWrapper;
    private final ConfigurationDomainService configurationDomainService;
    private final SavingsAccountInterestChargeRepository interestChargeRepository;

    @Override
    public InterestCalculationData calculate(final Long savingsAccountId, final BigDecimal topUpAmount,
            final BigDecimal withdrawalAmount) {

        final SavingsAccount account = this.savingsAccountRepositoryWrapper.findOneWithNotFoundDetection(savingsAccountId);

        final DepositAccountType depositAccountType = account.depositAccountType();
        this.context.authenticatedUser().validateHasReadPermission(depositAccountType.resourceName());

        final LocalDate today = DateUtils.getBusinessLocalDate();
        final LocalDate maturityDate = maturityDateOf(account);

        addSimulatedTransactionIfPresent(account, today, topUpAmount, withdrawalAmount);

        final MathContext mc = new MathContext(15, MoneyHelper.getRoundingMode());
        final boolean isSavingsInterestPostingAtCurrentPeriodEnd = this.configurationDomainService
                .isSavingsInterestPostingAtCurrentPeriodEnd();
        final Integer financialYearBeginningMonth = this.configurationDomainService.retrieveFinancialYearBeginningMonth();
        final boolean isInterestTransfer = false;
        final boolean backdatedTxnsAllowedTill = false;
        final boolean postReversals = false;

        // Run #1, bounded by today: PostingPeriod.getPeriodInterval() always reports the FULL calendar period (e.g.
        // the whole month), even mid-period - it is NOT capped at upToInterestCalculationDate, only the underlying
        // day-count/balance walk is. So "sum periods whose interval ends on/before today" silently drops the
        // in-progress period's partial accrual entirely once interest posts less often than daily. Reading
        // totalInterestEarned straight off the summary this call just updated sidesteps that: it's the same
        // correctly-truncated figure `command=calculateInterest` already computes, just actually returned here.
        List<PostingPeriod> periods = account.calculateInterestUsing(mc, today, isInterestTransfer,
                isSavingsInterestPostingAtCurrentPeriodEnd, financialYearBeginningMonth, null, backdatedTxnsAllowedTill, postReversals);
        final BigDecimal interestAsAtToday = account.getSummary().getTotalInterestEarned();

        BigDecimal interestAtMaturity = null;
        BigDecimal maturityAmount = null;
        if (maturityDate != null) {
            // Run #2, bounded by maturity: a second full recalculation (the engine always recomputes from the
            // beginning - see class javadoc) projecting through maturity on the amount actually invested as of
            // today, which is also what supplies the full posting-period breakdown below.
            periods = account.calculateInterestUsing(mc, maturityDate, isInterestTransfer, isSavingsInterestPostingAtCurrentPeriodEnd,
                    financialYearBeginningMonth, null, backdatedTxnsAllowedTill, postReversals);
            interestAtMaturity = account.getSummary().getTotalInterestEarned();
            maturityAmount = lastClosingBalanceOf(periods, account).getAmount();
        }

        final List<PostingPeriodData> postingPeriods = new ArrayList<>();
        for (final PostingPeriod period : periods) {
            final Money periodInterest = period.interest() == null ? Money.zero(account.getCurrency()) : period.interest();
            postingPeriods.add(new PostingPeriodData(period.getPeriodInterval().startDate(), period.getPeriodInterval().endDate(),
                    periodInterest.getAmount()));
        }

        BigDecimal pendingInterestBasedCharges = null;
        BigDecimal postedInterestBasedCharges = null;
        if (account instanceof DynamicDepositAccount) {
            pendingInterestBasedCharges = this.interestChargeRepository.sumPendingChargeAmount(savingsAccountId);
            postedInterestBasedCharges = this.interestChargeRepository.sumPostedChargeAmount(savingsAccountId);
        }

        final List<InterestCalculationTransactionData> transactions = new ArrayList<>();
        for (final SavingsAccountTransaction transaction : account.getTransactions()) {
            transactions.add(new InterestCalculationTransactionData(transaction.getId(),
                    SavingsEnumerations.transactionType(transaction.getTransactionType()), transaction.getTransactionDate(),
                    transaction.getAmount(account.getCurrency()).getAmount(), transaction.getRunningBalance(), transaction.isReversed(),
                    transaction.getId() == null));
        }

        // Note: summary.getTotalInterestEarned() itself now reflects whichever run happened LAST (the
        // maturity-bounded projection, when there is one) - use the captured interestAsAtToday value below instead
        // of re-reading it off the summary, so the general derived-totals block below stays "as at today" and isn't
        // silently polluted by the maturity projection.
        final var summary = account.getSummary();
        return new InterestCalculationData(account.getId(), account.getAccountNumber(),
                account.getExternalId() == null ? null : account.getExternalId().getValue(), account.clientId(), account.groupId(),
                account.productId(), SavingsEnumerations.status(account.getStatus()), account.getCurrency().toData(), maturityDate,
                interestAsAtToday, interestAtMaturity, maturityAmount, pendingInterestBasedCharges, postedInterestBasedCharges,
                summary.getTotalDeposits(), summary.getTotalWithdrawals(), summary.getTotalWithdrawalFees(), summary.getTotalAnnualFees(),
                interestAsAtToday, summary.getTotalInterestPosted(), summary.getAccountBalance(), summary.getTotalFeeCharge(),
                summary.getTotalPenaltyCharge(), summary.getTotalOverdraftInterestDerived(), summary.getTotalWithholdTax(),
                summary.getInterestPostedTillDate(), transactions, postingPeriods, topUpAmount, withdrawalAmount);
    }

    private Money lastClosingBalanceOf(final List<PostingPeriod> periods, final SavingsAccount account) {
        Money closingBalance = account.getSummary().getAccountBalance(account.getCurrency());
        for (final PostingPeriod period : periods) {
            closingBalance = period.closingBalance();
        }
        return closingBalance;
    }

    private LocalDate maturityDateOf(final SavingsAccount account) {
        if (account instanceof FixedDepositAccount fixedDepositAccount) {
            return fixedDepositAccount.maturityDate();
        } else if (account instanceof RecurringDepositAccount recurringDepositAccount) {
            return recurringDepositAccount.maturityDate();
        } else if (account instanceof DynamicDepositAccount dynamicDepositAccount) {
            return dynamicDepositAccount.maturityDate();
        }
        return null;
    }

    /**
     * Adds a transient (never persisted - see class javadoc) deposit and/or withdrawal transaction dated today, so
     * the calculation engine's daily-balance walk reflects "what if I did this today" from that point forward while
     * leaving every already-elapsed day untouched. Both are independently optional and can be combined.
     */
    private void addSimulatedTransactionIfPresent(final SavingsAccount account, final LocalDate today, final BigDecimal topUpAmount,
            final BigDecimal withdrawalAmount) {
        if (topUpAmount != null && topUpAmount.compareTo(BigDecimal.ZERO) > 0) {
            account.addTransaction(SavingsAccountTransaction.deposit(account, account.office(), null, today,
                    Money.of(account.getCurrency(), topUpAmount), null));
        }
        if (withdrawalAmount != null && withdrawalAmount.compareTo(BigDecimal.ZERO) > 0) {
            account.addTransaction(SavingsAccountTransaction.withdrawal(account, account.office(), null, today,
                    Money.of(account.getCurrency(), withdrawalAmount), null));
        }
    }
}
