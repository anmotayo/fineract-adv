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

import java.math.BigDecimal;
import java.math.MathContext;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.apache.fineract.infrastructure.core.domain.LocalDateInterval;
import org.apache.fineract.infrastructure.core.service.DateUtils;
import org.apache.fineract.organisation.monetary.domain.MonetaryCurrency;
import org.apache.fineract.organisation.monetary.domain.Money;
import org.apache.fineract.portfolio.savings.DepositAccountType;
import org.apache.fineract.portfolio.savings.SavingsCompoundingInterestPeriodType;
import org.apache.fineract.portfolio.savings.SavingsInterestCalculationDaysInYearType;
import org.apache.fineract.portfolio.savings.SavingsInterestCalculationType;
import org.apache.fineract.portfolio.savings.SavingsPostingInterestPeriodType;
import org.apache.fineract.portfolio.savings.WithHoldTaxPostingType;
import org.apache.fineract.portfolio.savings.data.SavingsAccountData;
import org.apache.fineract.portfolio.savings.data.SavingsAccountDynamicRateData;
import org.apache.fineract.portfolio.savings.data.SavingsAccountTransactionData;
import org.apache.fineract.portfolio.savings.domain.SavingsAccountTransactionDataComparator;
import org.apache.fineract.portfolio.savings.domain.SavingsHelper;
import org.apache.fineract.portfolio.savings.domain.interest.PostingPeriod;
import org.apache.fineract.portfolio.savings.service.SavingsAccountInterestPostingServiceImpl;
import org.apache.fineract.portfolio.tax.data.TaxComponentData;
import org.apache.fineract.portfolio.tax.service.TaxUtils;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

@Component
@Primary
public class AdvanclySavingsAccountInterestPostingService extends SavingsAccountInterestPostingServiceImpl {

    private final SavingsHelper savingsHelper;

    public AdvanclySavingsAccountInterestPostingService(final SavingsHelper savingsHelper) {
        super(savingsHelper);
        this.savingsHelper = savingsHelper;
    }

    @Override
    public SavingsAccountData postInterest(final MathContext mc, LocalDate interestPostingUpToDate, final boolean isInterestTransfer,
            final boolean isSavingsInterestPostingAtCurrentPeriodEnd, final Integer financialYearBeginningMonth,
            final LocalDate postInterestOnDate, final boolean backdatedTxnsAllowedTill, final SavingsAccountData savingsAccountData) {
        if (!DepositAccountType.fromInt(savingsAccountData.getDepositTypeId()).isDynamicDeposit()) {
            return super.postInterest(mc, interestPostingUpToDate, isInterestTransfer, isSavingsInterestPostingAtCurrentPeriodEnd,
                    financialYearBeginningMonth, postInterestOnDate, backdatedTxnsAllowedTill, savingsAccountData);
        }
        return postDynamicDepositInterest(mc, interestPostingUpToDate, isInterestTransfer, isSavingsInterestPostingAtCurrentPeriodEnd,
                financialYearBeginningMonth, postInterestOnDate, backdatedTxnsAllowedTill, savingsAccountData);
    }

    private SavingsAccountData postDynamicDepositInterest(final MathContext mc, LocalDate interestPostingUpToDate,
            final boolean isInterestTransfer, final boolean isSavingsInterestPostingAtCurrentPeriodEnd,
            final Integer financialYearBeginningMonth, final LocalDate postInterestOnDate, final boolean backdatedTxnsAllowedTill,
            final SavingsAccountData savingsAccountData) {
        Money interestPostedToDate = Money.zero(savingsAccountData.getCurrency());
        final LocalDate startInterestDate = getStartInterestCalculationDate(savingsAccountData);

        final LocalDate effectiveInterestPostingUpToDate = interestPostingUpToDate(savingsAccountData, interestPostingUpToDate);
        if (DateUtils.isAfter(interestPostingUpToDate, effectiveInterestPostingUpToDate)) {
            interestPostingUpToDate = effectiveInterestPostingUpToDate.plusDays(1);
        }

        if (backdatedTxnsAllowedTill && savingsAccountData.getSummary().getInterestPostedTillDate() != null) {
            interestPostedToDate = Money.of(savingsAccountData.getCurrency(), savingsAccountData.getSummary().getTotalInterestPosted());
            savingsAccountData.setStartInterestCalculationDate(savingsAccountData.getSummary().getInterestPostedTillDate());
        } else {
            savingsAccountData.setStartInterestCalculationDate(startInterestDate);
        }

        final List<LocalDate> postedAsOnDates = getManualPostingDates(savingsAccountData);
        if (postInterestOnDate != null) {
            postedAsOnDates.add(postInterestOnDate);
        }
        final SavingsPostingInterestPeriodType postingPeriodType = SavingsPostingInterestPeriodType
                .fromInt(savingsAccountData.getInterestPostingPeriodTypeId());
        final List<LocalDateInterval> corePostingPeriodIntervals = this.savingsHelper.determineInterestPostingPeriods(
                savingsAccountData.getStartInterestCalculationDate(), effectiveInterestPostingUpToDate, postingPeriodType,
                financialYearBeginningMonth, postedAsOnDates);

        final List<PostingPeriod> ratedSubPeriods = calculateDynamicInterestUsing(mc, effectiveInterestPostingUpToDate, isInterestTransfer,
                isSavingsInterestPostingAtCurrentPeriodEnd, financialYearBeginningMonth, postInterestOnDate, backdatedTxnsAllowedTill,
                savingsAccountData, corePostingPeriodIntervals, postedAsOnDates);

        boolean recalucateDailyBalanceDetails = false;
        final boolean applyWithHoldTax = isWithHoldTaxApplicableForInterestPosting(savingsAccountData);
        final List<SavingsAccountTransactionData> withholdTransactions = findWithHoldSavingsTransactionsWithPivotConfig(savingsAccountData);

        for (final LocalDateInterval coreBoundary : corePostingPeriodIntervals) {
            Money interestEarnedToBePostedForPeriod = Money.zero(savingsAccountData.getCurrency());
            final List<PostingPeriod> boundarySubPeriods = subPeriodsWithin(coreBoundary, ratedSubPeriods);
            for (final PostingPeriod subPeriod : boundarySubPeriods) {
                interestEarnedToBePostedForPeriod = interestEarnedToBePostedForPeriod.plus(subPeriod.getInterestEarned());
            }
            final boolean isUserPosting = postedAsOnDates.contains(coreBoundary.endDate().plusDays(1));
            final LocalDate interestPostingTransactionDate = isSavingsInterestPostingAtCurrentPeriodEnd ? coreBoundary.endDate()
                    : coreBoundary.endDate().plusDays(1);

            if (!DateUtils.isAfter(interestPostingTransactionDate, interestPostingUpToDate)) {
                interestPostedToDate = interestPostedToDate.plus(interestEarnedToBePostedForPeriod);
                SavingsAccountTransactionData postingTransaction = findInterestPostingTransactionFor(interestPostingTransactionDate,
                        savingsAccountData);

                if (postingTransaction == null) {
                    SavingsAccountTransactionData newPostingTransaction = null;
                    if (interestEarnedToBePostedForPeriod.isGreaterThanOrEqualTo(Money.zero(savingsAccountData.getCurrency()))) {
                        if (interestEarnedToBePostedForPeriod.isGreaterThanZero()) {
                            newPostingTransaction = SavingsAccountTransactionData.interestPosting(savingsAccountData,
                                    interestPostingTransactionDate, interestEarnedToBePostedForPeriod, isUserPosting);
                        }
                    } else {
                        newPostingTransaction = SavingsAccountTransactionData.overdraftInterest(savingsAccountData,
                                interestPostingTransactionDate, interestEarnedToBePostedForPeriod.negated(), isUserPosting, false);
                    }

                    savingsAccountData.updateTransactions(newPostingTransaction);

                    if (applyWithHoldTax) {
                        createWithHoldTransaction(interestEarnedToBePostedForPeriod.getAmount(), interestPostingTransactionDate,
                                savingsAccountData);
                    }
                    recalucateDailyBalanceDetails = true;
                } else {
                    boolean correctionRequired;
                    if (postingTransaction.isInterestPostingAndNotReversed()) {
                        correctionRequired = postingTransaction.hasNotAmount(interestEarnedToBePostedForPeriod);
                    } else {
                        correctionRequired = postingTransaction.hasNotAmount(interestEarnedToBePostedForPeriod.negated());
                    }
                    if (correctionRequired) {
                        boolean applyWithHoldTaxForOldTransaction = false;
                        postingTransaction.reverse();

                        final SavingsAccountTransactionData withholdTransaction = findTransactionFor(interestPostingTransactionDate,
                                withholdTransactions);
                        if (withholdTransaction != null) {
                            withholdTransaction.reverse();
                            applyWithHoldTaxForOldTransaction = true;
                        }

                        if (applyWithHoldTax) {
                            applyWithHoldTaxForOldTransaction = true;
                        }

                        SavingsAccountTransactionData newPostingTransaction;
                        if (interestEarnedToBePostedForPeriod.isGreaterThanOrEqualTo(Money.zero(savingsAccountData.getCurrency()))) {
                            newPostingTransaction = SavingsAccountTransactionData.interestPosting(savingsAccountData,
                                    interestPostingTransactionDate, interestEarnedToBePostedForPeriod, isUserPosting);
                        } else {
                            newPostingTransaction = SavingsAccountTransactionData.overdraftInterest(savingsAccountData,
                                    interestPostingTransactionDate, interestEarnedToBePostedForPeriod.negated(), isUserPosting, false);
                        }

                        savingsAccountData.updateTransactions(newPostingTransaction);

                        if (applyWithHoldTaxForOldTransaction) {
                            createWithHoldTransaction(interestEarnedToBePostedForPeriod.getAmount(), interestPostingTransactionDate,
                                    savingsAccountData);
                        }
                        recalucateDailyBalanceDetails = true;
                    }
                }
            }
        }

        if (recalucateDailyBalanceDetails) {
            Money openingAccountBalance = Money.zero(savingsAccountData.getCurrency());
            if (backdatedTxnsAllowedTill) {
                if (savingsAccountData.getSummary().getLastInterestCalculationDate() == null) {
                    openingAccountBalance = Money.zero(savingsAccountData.getCurrency());
                } else {
                    openingAccountBalance = Money.of(savingsAccountData.getCurrency(),
                            savingsAccountData.getSummary().getRunningBalanceOnPivotDate());
                }
            }
            recalculateDailyBalances(openingAccountBalance, interestPostingUpToDate, backdatedTxnsAllowedTill, savingsAccountData);
        }

        if (!backdatedTxnsAllowedTill) {
            savingsAccountData.getSummary().updateSummary(savingsAccountData.getCurrency(),
                    savingsAccountData.getSavingsAccountTransactionSummaryWrapper(), savingsAccountData.getSavingsAccountTransactionData());
        } else {
            savingsAccountData.getSummary().updateSummaryWithPivotConfig(savingsAccountData.getCurrency(),
                    savingsAccountData.getSavingsAccountTransactionSummaryWrapper(), null,
                    savingsAccountData.getSavingsAccountTransactionData());
        }

        return savingsAccountData;
    }

    private List<PostingPeriod> calculateDynamicInterestUsing(final MathContext mc, final LocalDate upToInterestCalculationDate,
            final boolean isInterestTransfer, final boolean isSavingsInterestPostingAtCurrentPeriodEnd,
            final Integer financialYearBeginningMonth, final LocalDate postInterestOnDate, final boolean backdatedTxnsAllowedTill,
            final SavingsAccountData savingsAccountData, final List<LocalDateInterval> corePostingPeriodIntervals,
            final List<LocalDate> postedAsOnDates) {

        Money openingAccountBalance = backdatedTxnsAllowedTill
                ? Money.of(savingsAccountData.getCurrency(), savingsAccountData.getSummary().getRunningBalanceOnPivotDate())
                : Money.zero(savingsAccountData.getCurrency());
        recalculateDailyBalances(openingAccountBalance, upToInterestCalculationDate, backdatedTxnsAllowedTill, savingsAccountData);

        final List<PostingPeriod> allPostingPeriods = new ArrayList<>();
        final List<SavingsAccountDynamicRateData> rateHistoryAscending = savingsAccountData.getDynamicRateHistory();
        if (rateHistoryAscending == null || rateHistoryAscending.isEmpty()) {
            throw new IllegalStateException("No dynamic deposit rate history found for account " + savingsAccountData.getId());
        }

        final SavingsCompoundingInterestPeriodType compoundingPeriodType = SavingsCompoundingInterestPeriodType
                .fromInt(savingsAccountData.getInterestCompoundingPeriodTypeId());
        final SavingsInterestCalculationDaysInYearType daysInYearType = SavingsInterestCalculationDaysInYearType
                .fromInt(savingsAccountData.getInterestCalculationDaysInYearTypeId());
        final SavingsInterestCalculationType interestCalculationType = SavingsInterestCalculationType
                .fromInt(savingsAccountData.getInterestCalculationTypeId());
        final List<DynamicDepositScheduledInterestIntervalSplitter.RatedInterval> ratedIntervals = DynamicDepositScheduledInterestIntervalSplitter
                .split(corePostingPeriodIntervals, rateHistoryAscending);

        Money periodStartingBalance = openingStartingBalance(savingsAccountData);
        final Collection<Long> interestPostTransactions = this.savingsHelper.fetchPostInterestTransactionIds(savingsAccountData.getId());
        final Money minBalanceForInterestCalculation = Money.of(savingsAccountData.getCurrency(),
                savingsAccountData.getMinBalanceForInterestCalculation());
        final MonetaryCurrency monetaryCurrency = MonetaryCurrency.fromCurrencyData(savingsAccountData.getCurrency());
        final List<SavingsAccountTransactionData> orderedNonInterestPostingTransactions = retrieveOrderedNonInterestPostingTransactions(
                savingsAccountData);

        for (final DynamicDepositScheduledInterestIntervalSplitter.RatedInterval ratedInterval : ratedIntervals) {
            final boolean isUserPosting = postedAsOnDates.contains(ratedInterval.periodInterval().endDate().plusDays(1));
            final BigDecimal interestRateAsFraction = ratedInterval.annualInterestRate().divide(BigDecimal.valueOf(100L), mc);

            final PostingPeriod postingPeriod = PostingPeriod.createFromDTO(ratedInterval.periodInterval(), periodStartingBalance,
                    orderedNonInterestPostingTransactions, monetaryCurrency, compoundingPeriodType, interestCalculationType,
                    interestRateAsFraction, daysInYearType.getValue(), upToInterestCalculationDate, interestPostTransactions,
                    isInterestTransfer, minBalanceForInterestCalculation, isSavingsInterestPostingAtCurrentPeriodEnd, BigDecimal.ZERO,
                    Money.zero(savingsAccountData.getCurrency()), isUserPosting, financialYearBeginningMonth, false);

            periodStartingBalance = postingPeriod.closingBalance();
            allPostingPeriods.add(postingPeriod);
        }

        this.savingsHelper.calculateInterestForAllPostingPeriods(monetaryCurrency, allPostingPeriods,
                getLockedInUntilLocalDate(savingsAccountData), false);

        if (savingsAccountData.hasStartInterestCalculationDate()) {
            final BigDecimal preStartInterest = this.savingsHelper.sumInterestPostingsOnOrBeforeDate(savingsAccountData.getId(),
                    savingsAccountData.getStartInterestCalculationDate());
            savingsAccountData.getSummary().setPreStartDateInterestEarned(preStartInterest);
        }
        savingsAccountData.getSummary().updateFromInterestPeriodSummaries(monetaryCurrency, allPostingPeriods,
                savingsAccountData.hasStartInterestCalculationDate());
        if (backdatedTxnsAllowedTill) {
            savingsAccountData.getSummary().updateSummaryWithPivotConfig(savingsAccountData.getCurrency(),
                    savingsAccountData.getSavingsAccountTransactionSummaryWrapper(), null,
                    savingsAccountData.getSavingsAccountTransactionData());
        } else {
            savingsAccountData.getSummary().updateSummary(savingsAccountData.getCurrency(),
                    savingsAccountData.getSavingsAccountTransactionSummaryWrapper(), savingsAccountData.getSavingsAccountTransactionData());
        }

        return allPostingPeriods;
    }

    private List<PostingPeriod> subPeriodsWithin(final LocalDateInterval coreBoundary, final List<PostingPeriod> ratedSubPeriods) {
        return ratedSubPeriods.stream().filter(subPeriod -> coreBoundary.contains(subPeriod.getPeriodInterval().startDate())).toList();
    }

    private Money openingStartingBalance(final SavingsAccountData savingsAccountData) {
        if (!savingsAccountData.hasStartInterestCalculationDate()) {
            return Money.zero(savingsAccountData.getCurrency());
        }
        final BigDecimal runningBalanceOnPivotDate = savingsAccountData.getSummary().getRunningBalanceOnPivotDate();
        return runningBalanceOnPivotDate == null ? Money.zero(savingsAccountData.getCurrency())
                : Money.of(savingsAccountData.getCurrency(), runningBalanceOnPivotDate);
    }

    private List<SavingsAccountTransactionData> retrieveOrderedNonInterestPostingTransactions(final SavingsAccountData savingsAccountData) {
        final List<SavingsAccountTransactionData> orderedNonInterestPostingTransactions = new ArrayList<>();
        for (final SavingsAccountTransactionData transaction : retrieveListOfTransactions(savingsAccountData)) {
            if (!(transaction.isInterestPostingAndNotReversed() || transaction.isOverdraftInterestAndNotReversed()
                    || transaction.isAccrualAndNotReversed()) && transaction.isNotReversed() && !transaction.isReversalTransaction()) {
                orderedNonInterestPostingTransactions.add(transaction);
            }
        }
        orderedNonInterestPostingTransactions.sort(new SavingsAccountTransactionDataComparator());
        return orderedNonInterestPostingTransactions;
    }

    private List<SavingsAccountTransactionData> retrieveListOfTransactions(final SavingsAccountData savingsAccountData) {
        final List<SavingsAccountTransactionData> listOfTransactionsSorted = new ArrayList<>();
        listOfTransactionsSorted.addAll(savingsAccountData.getSavingsAccountTransactionData());
        listOfTransactionsSorted.sort(new SavingsAccountTransactionDataComparator());
        return listOfTransactionsSorted;
    }

    private List<SavingsAccountTransactionData> findWithHoldSavingsTransactionsWithPivotConfig(
            final SavingsAccountData savingsAccountData) {
        final List<SavingsAccountTransactionData> withholdTransactions = new ArrayList<>();
        for (final SavingsAccountTransactionData transaction : savingsAccountData.getSavingsAccountTransactionData()) {
            if (transaction.isWithHoldTaxAndNotReversed()) {
                withholdTransactions.add(transaction);
            }
        }
        return withholdTransactions;
    }

    private SavingsAccountTransactionData createWithHoldTransaction(final BigDecimal amount, final LocalDate date,
            final SavingsAccountData savingsAccountData) {
        if (savingsAccountData.getTaxGroup() != null && savingsAccountData.getTaxGroup().getTaxAssociations() != null
                && amount.compareTo(BigDecimal.ZERO) > 0) {
            final Map<TaxComponentData, BigDecimal> taxSplit = TaxUtils.splitTaxData(amount, date,
                    savingsAccountData.getTaxGroup().getTaxAssociations().stream().collect(Collectors.toSet()), amount.scale());
            final BigDecimal totalTax = TaxUtils.totalTaxDataAmount(taxSplit);
            if (totalTax.compareTo(BigDecimal.ZERO) > 0) {
                final SavingsAccountTransactionData withholdTransaction = SavingsAccountTransactionData.withHoldTax(savingsAccountData,
                        date, Money.of(savingsAccountData.getCurrency(), totalTax), taxSplit);
                savingsAccountData.getSavingsAccountTransactionData().add(withholdTransaction);
                return withholdTransaction;
            }
        }
        return null;
    }

    private boolean isWithHoldTaxApplicableForInterestPosting(final SavingsAccountData savingsAccountData) {
        return savingsAccountData.isWithHoldTax() && (DepositAccountType.fromInt(savingsAccountData.getDepositTypeId()).isSavingsDeposit()
                || WithHoldTaxPostingType.fromInt(savingsAccountData.getWithHoldTaxPostingType().getId().intValue()).isInterestPosting());
    }

    private LocalDate interestPostingUpToDate(final SavingsAccountData savingsAccountData, final LocalDate interestPostingDate) {
        LocalDate interestPostingUpToDate = interestPostingDate;
        LocalDate uptoMaturityDate = savingsAccountData.getMaturityDate();
        if (uptoMaturityDate != null) {
            uptoMaturityDate = uptoMaturityDate.minusDays(1);
        }
        if (uptoMaturityDate != null && DateUtils.isBefore(uptoMaturityDate, interestPostingUpToDate)) {
            interestPostingUpToDate = uptoMaturityDate;
        }
        return interestPostingUpToDate;
    }
}
