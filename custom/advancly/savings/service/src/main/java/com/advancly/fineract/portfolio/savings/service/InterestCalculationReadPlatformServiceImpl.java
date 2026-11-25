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
import com.advancly.fineract.portfolio.savings.data.InterestCalculationSummaryData;
import com.advancly.fineract.portfolio.savings.data.InterestCalculationTransactionData;
import com.advancly.fineract.portfolio.savings.data.PostingPeriodData;
import com.advancly.fineract.portfolio.savings.data.SimulatedRateData;
import com.advancly.fineract.portfolio.savings.domain.DynamicDepositAccount;
import com.advancly.fineract.portfolio.savings.domain.DynamicDepositRateHistoryEventType;
import com.advancly.fineract.portfolio.savings.service.DynamicDepositRatePreviewService.RatePreview;
import java.math.BigDecimal;
import java.math.MathContext;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.apache.fineract.infrastructure.configuration.domain.ConfigurationDomainService;
import org.apache.fineract.infrastructure.core.service.DateUtils;
import org.apache.fineract.infrastructure.security.service.PlatformSecurityContext;
import org.apache.fineract.organisation.monetary.domain.Money;
import org.apache.fineract.organisation.monetary.domain.MoneyHelper;
import org.apache.fineract.portfolio.account.domain.AccountTransferRepository;
import org.apache.fineract.portfolio.account.domain.AccountTransferTransaction;
import org.apache.fineract.portfolio.savings.DepositAccountType;
import org.apache.fineract.portfolio.savings.domain.FixedDepositAccount;
import org.apache.fineract.portfolio.savings.domain.RecurringDepositAccount;
import org.apache.fineract.portfolio.savings.domain.SavingsAccount;
import org.apache.fineract.portfolio.savings.domain.SavingsAccountAssembler;
import org.apache.fineract.portfolio.savings.domain.SavingsAccountRepositoryWrapper;
import org.apache.fineract.portfolio.savings.domain.SavingsAccountTransaction;
import org.apache.fineract.portfolio.savings.domain.interest.PostingPeriod;
import org.apache.fineract.portfolio.savings.service.SavingsEnumerations;
import org.springframework.stereotype.Service;

/**
 * Deliberately not {@code @Transactional}: the account is loaded in the repository's own read-only transaction and
 * comes back detached, so the simulated mutations below are never flushed. Adding a transaction would persist them.
 */
@Service
@RequiredArgsConstructor
public class InterestCalculationReadPlatformServiceImpl implements InterestCalculationReadPlatformService {

    private final PlatformSecurityContext context;
    private final SavingsAccountRepositoryWrapper savingsAccountRepositoryWrapper;
    private final SavingsAccountAssembler savingsAccountAssembler;
    private final ConfigurationDomainService configurationDomainService;
    private final DynamicDepositRatePreviewService ratePreviewService;
    private final AccountTransferRepository accountTransferRepository;

    @Override
    public InterestCalculationData calculate(final Long savingsAccountId, final BigDecimal topUpAmount, final BigDecimal withdrawalAmount) {

        final SavingsAccount account = this.savingsAccountRepositoryWrapper.findOneWithNotFoundDetection(savingsAccountId);
        this.savingsAccountAssembler.setHelpers(account);

        final DepositAccountType depositAccountType = account.depositAccountType();
        this.context.authenticatedUser().validateHasReadPermission(depositAccountType.resourceName());

        final LocalDate today = DateUtils.getBusinessLocalDate().minusDays(1);
        final LocalDate maturityDate = maturityDateOf(account);

        final SavingsAccountTransaction lastSimulatedTransaction = addSimulatedTransactionIfPresent(account, today, topUpAmount,
                withdrawalAmount);
        SimulatedRateData simulatedRate = null;
        if (account instanceof DynamicDepositAccount dynamicDepositAccount && lastSimulatedTransaction != null) {
            // One net row: both legs share today's date. Set before calculateInterestUsing so both runs use it.
            final BigDecimal net = defaultToZero(topUpAmount).subtract(defaultToZero(withdrawalAmount));
            if (net.signum() != 0) {
                final RatePreview preview = this.ratePreviewService.preview(dynamicDepositAccount, lastSimulatedTransaction,
                        net.signum() > 0 ? DynamicDepositRateHistoryEventType.DEPOSIT : DynamicDepositRateHistoryEventType.WITHDRAWAL);
                if (preview != null) {
                    dynamicDepositAccount.setSimulatedRateHistory(List.of(preview.row()));
                    simulatedRate = preview.data();
                }
            }
        }

        final MathContext mc = new MathContext(15, MoneyHelper.getRoundingMode());
        final boolean isSavingsInterestPostingAtCurrentPeriodEnd = this.configurationDomainService
                .isSavingsInterestPostingAtCurrentPeriodEnd();
        final Integer financialYearBeginningMonth = this.configurationDomainService.retrieveFinancialYearBeginningMonth();
        final boolean isInterestTransfer = false;
        final boolean backdatedTxnsAllowedTill = false;
        final boolean postReversals = false;

        // Use the summary, not the periods: a period's interval is the full calendar period even mid-period, so
        // filtering by interval end would drop the in-progress partial accrual.
        List<PostingPeriod> periods = account.calculateInterestUsing(mc, today, isInterestTransfer,
                isSavingsInterestPostingAtCurrentPeriodEnd, financialYearBeginningMonth, null, backdatedTxnsAllowedTill, postReversals);
        // Snapshot now: the next run overwrites the summary.
        final InterestCalculationSummaryData asAtTodaySummary = InterestCalculationSummaryData.from(account.getSummary());

        BigDecimal maturityAmount = null;
        InterestCalculationSummaryData asAtMaturitySummary = null;
        if (maturityDate != null) {
            periods = account.calculateInterestUsing(mc, maturityDate, isInterestTransfer, isSavingsInterestPostingAtCurrentPeriodEnd,
                    financialYearBeginningMonth, null, backdatedTxnsAllowedTill, postReversals);
            asAtMaturitySummary = InterestCalculationSummaryData.from(account.getSummary());
            // The closing balance excludes interest; Dynamic Deposit maturity is invested amount plus gross interest.
            maturityAmount = account instanceof DynamicDepositAccount dynamicDepositAccount
                    ? dynamicDepositAccount.grossMaturityAmountAfterProjection()
                    : lastClosingBalanceOf(periods, account).getAmount();
        }

        final List<PostingPeriodData> postingPeriods = new ArrayList<>();
        for (final PostingPeriod period : periods) {
            final Money periodInterest = period.interest() == null ? Money.zero(account.getCurrency()) : period.interest();
            postingPeriods.add(new PostingPeriodData(period.getPeriodInterval().startDate(), period.getPeriodInterval().endDate(),
                    periodInterest.getAmount()));
        }

        final Map<Long, AccountTransferTransaction> transfersBySavingsTransactionId = transfersBySavingsTransactionId(account);
        final List<InterestCalculationTransactionData> transactions = new ArrayList<>();
        for (final SavingsAccountTransaction transaction : account.getTransactions()) {
            final AccountTransferTransaction transfer = transaction.getId() == null ? null
                    : transfersBySavingsTransactionId.get(transaction.getId());
            transactions.add(new InterestCalculationTransactionData(transaction.getId(),
                    SavingsEnumerations.transactionType(transaction.getTransactionType()), transaction.getTransactionDate(),
                    transaction.getAmount(account.getCurrency()).getAmount(), transaction.getRunningBalance(), transaction.isReversed(),
                    transaction.getId() == null, transaction.getCreatedDate().orElse(null), account.getCurrency().toData(),
                    transfer == null || transfer.accountTransferDetails() == null ? null : transfer.accountTransferDetails().getId(),
                    transfer == null ? null : transfer.getId()));
        }

        return new InterestCalculationData(account.getId(), account.getAccountNumber(),
                account.getExternalId() == null ? null : account.getExternalId().getValue(), account.clientId(), account.groupId(),
                account.productId(), SavingsEnumerations.status(account.getStatus()), account.getCurrency().toData(), maturityDate,
                account.getNominalAnnualInterestRate(), maturityAmount, asAtTodaySummary, asAtMaturitySummary, transactions, postingPeriods,
                topUpAmount, withdrawalAmount, simulatedRate);
    }

    private Map<Long, AccountTransferTransaction> transfersBySavingsTransactionId(final SavingsAccount account) {
        final List<Long> transactionIds = account.getTransactions().stream().map(SavingsAccountTransaction::getId)
                .filter(java.util.Objects::nonNull).toList();
        final Map<Long, AccountTransferTransaction> bySavingsTransactionId = new HashMap<>();
        if (transactionIds.isEmpty()) {
            return bySavingsTransactionId;
        }
        for (final AccountTransferTransaction transfer : this.accountTransferRepository.findBySavingsTransactionIds(transactionIds)) {
            if (transfer.getFromTransaction() != null) {
                bySavingsTransactionId.put(transfer.getFromTransaction().getId(), transfer);
            }
            if (transfer.getToSavingsTransaction() != null) {
                bySavingsTransactionId.put(transfer.getToSavingsTransaction().getId(), transfer);
            }
        }
        return bySavingsTransactionId;
    }

    private static BigDecimal defaultToZero(final BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
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

    /** Adds transient top-up/withdrawal transactions dated today; returns the last one added, or {@code null}. */
    private SavingsAccountTransaction addSimulatedTransactionIfPresent(final SavingsAccount account, final LocalDate today,
            final BigDecimal topUpAmount, final BigDecimal withdrawalAmount) {
        SavingsAccountTransaction last = null;
        if (topUpAmount != null && topUpAmount.compareTo(BigDecimal.ZERO) > 0) {
            last = SavingsAccountTransaction.deposit(account, account.office(), null, today, Money.of(account.getCurrency(), topUpAmount),
                    null);
            account.addTransaction(last);
        }
        if (withdrawalAmount != null && withdrawalAmount.compareTo(BigDecimal.ZERO) > 0) {
            last = SavingsAccountTransaction.withdrawal(account, account.office(), null, today,
                    Money.of(account.getCurrency(), withdrawalAmount), null);
            account.addTransaction(last);
        }
        return last;
    }
}
