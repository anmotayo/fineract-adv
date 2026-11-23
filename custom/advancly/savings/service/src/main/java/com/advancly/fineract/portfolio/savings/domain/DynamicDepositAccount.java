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
package com.advancly.fineract.portfolio.savings.domain;

import static com.advancly.fineract.portfolio.savings.DynamicDepositApiConstants.DYNAMIC_DEPOSIT_ACCOUNT_RESOURCE_NAME;

import com.advancly.fineract.portfolio.savings.service.DynamicDepositInvestedAmountCalculator;
import com.advancly.fineract.portfolio.savings.service.DynamicDepositServiceLocator;
import jakarta.persistence.CascadeType;
import jakarta.persistence.DiscriminatorValue;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Transient;
import java.math.BigDecimal;
import java.math.MathContext;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.fineract.infrastructure.core.api.JsonCommand;
import org.apache.fineract.infrastructure.core.data.ApiParameterError;
import org.apache.fineract.infrastructure.core.data.DataValidatorBuilder;
import org.apache.fineract.infrastructure.core.domain.ExternalId;
import org.apache.fineract.infrastructure.core.domain.LocalDateInterval;
import org.apache.fineract.infrastructure.core.exception.GeneralPlatformDomainRuleException;
import org.apache.fineract.infrastructure.core.exception.PlatformApiDataValidationException;
import org.apache.fineract.infrastructure.core.service.DateUtils;
import org.apache.fineract.organisation.monetary.domain.Money;
import org.apache.fineract.organisation.staff.domain.Staff;
import org.apache.fineract.portfolio.accountdetails.domain.AccountType;
import org.apache.fineract.portfolio.client.domain.Client;
import org.apache.fineract.portfolio.group.domain.Group;
import org.apache.fineract.portfolio.savings.DepositAccountType;
import org.apache.fineract.portfolio.savings.SavingsAccountTransactionType;
import org.apache.fineract.portfolio.savings.SavingsApiConstants;
import org.apache.fineract.portfolio.savings.SavingsCompoundingInterestPeriodType;
import org.apache.fineract.portfolio.savings.SavingsInterestCalculationDaysInYearType;
import org.apache.fineract.portfolio.savings.SavingsInterestCalculationType;
import org.apache.fineract.portfolio.savings.SavingsPeriodFrequencyType;
import org.apache.fineract.portfolio.savings.SavingsPostingInterestPeriodType;
import org.apache.fineract.portfolio.savings.WithHoldTaxPostingType;
import org.apache.fineract.portfolio.savings.data.SavingsAccountTransactionDTO;
import org.apache.fineract.portfolio.savings.domain.DepositAccountInterestRateChart;
import org.apache.fineract.portfolio.savings.domain.DepositAccountTermAndPreClosure;
import org.apache.fineract.portfolio.savings.domain.SavingsAccount;
import org.apache.fineract.portfolio.savings.domain.SavingsAccountCharge;
import org.apache.fineract.portfolio.savings.domain.SavingsAccountStatusType;
import org.apache.fineract.portfolio.savings.domain.SavingsAccountTransaction;
import org.apache.fineract.portfolio.savings.domain.SavingsAccountTransactionComparator;
import org.apache.fineract.portfolio.savings.domain.SavingsProduct;
import org.apache.fineract.portfolio.savings.domain.interest.PostingPeriod;
import org.apache.fineract.portfolio.savings.domain.interest.SavingsAccountTransactionDetailsForPostingPeriod;
import org.apache.fineract.useradministration.domain.AppUser;

/** Dynamic Deposit account (deposit_type_enum = 500). */
@Entity
@DiscriminatorValue("500")
public class DynamicDepositAccount extends SavingsAccount {

    @OneToOne(mappedBy = "account", cascade = CascadeType.ALL)
    private DepositAccountTermAndPreClosure accountTermAndPreClosure;

    @OneToOne(mappedBy = "account", cascade = CascadeType.ALL)
    private DepositAccountDynamicDetail dynamicDetail;

    /** Snapshot of the product's rate chart at submission, so later product chart edits never affect this account. */
    @OneToOne(fetch = FetchType.LAZY, cascade = CascadeType.ALL, mappedBy = "account", orphanRemoval = true)
    private DepositAccountInterestRateChart chart;

    /** Preview-only "what if" rate-history rows, treated as persisted; always empty on real posting/COB paths. */
    @Transient
    private List<DepositAccountDynamicRateHistory> simulatedRateHistory = new ArrayList<>();

    @Transient
    private boolean openingDepositFundedByLinkedTransfer = false;

    /**
     * Early closure whose charges are already settled, so the closing transfer's withdrawal isn't treated as a fresh
     * early withdrawal (double forfeiture or charge).
     */
    @Transient
    private LocalDate closureSettledOn;

    @Transient
    private boolean closingAtMaturity = false;

    @Transient
    private boolean closingPrematurely = false;

    /** Per-request early-withdrawal charge % override; the caller clears it in a {@code finally}. */
    @Transient
    private BigDecimal withdrawalChargePercentageOverride;

    protected DynamicDepositAccount() {
        //
    }

    public void setSimulatedRateHistory(final List<DepositAccountDynamicRateHistory> simulatedRateHistory) {
        this.simulatedRateHistory = simulatedRateHistory == null ? new ArrayList<>() : new ArrayList<>(simulatedRateHistory);
    }

    /** Simulated rows are appended last and the sort is stable, so they win a same-date tie with a persisted row. */
    private List<DepositAccountDynamicRateHistory> withSimulatedRateHistory(final List<DepositAccountDynamicRateHistory> persisted) {
        if (this.simulatedRateHistory == null || this.simulatedRateHistory.isEmpty()) {
            return persisted;
        }
        final List<DepositAccountDynamicRateHistory> combined = new ArrayList<>(persisted);
        combined.addAll(this.simulatedRateHistory);
        combined.sort(Comparator.comparing(DepositAccountDynamicRateHistory::transactionDate));
        return combined;
    }

    public static DynamicDepositAccount createNewApplicationForSubmittal(final Client client, final Group group,
            final SavingsProduct product, final Staff fieldOfficer, final String accountNo, final ExternalId externalId,
            final AccountType accountType, final LocalDate submittedOnDate, final AppUser submittedBy, final BigDecimal interestRate,
            final SavingsCompoundingInterestPeriodType interestCompoundingPeriodType,
            final SavingsPostingInterestPeriodType interestPostingPeriodType, final SavingsInterestCalculationType interestCalculationType,
            final SavingsInterestCalculationDaysInYearType interestCalculationDaysInYearType, final BigDecimal minRequiredOpeningBalance,
            final Integer lockinPeriodFrequency, final SavingsPeriodFrequencyType lockinPeriodFrequencyType,
            final boolean withdrawalFeeApplicableForTransfer, final Set<SavingsAccountCharge> savingsAccountCharges,
            final DepositAccountTermAndPreClosure accountTermAndPreClosure, final DepositAccountInterestRateChart chart,
            final boolean withHoldTax) {

        final SavingsAccountStatusType status = SavingsAccountStatusType.SUBMITTED_AND_PENDING_APPROVAL;
        final boolean allowOverdraft = false;
        final BigDecimal overdraftLimit = BigDecimal.ZERO;

        return new DynamicDepositAccount(client, group, product, fieldOfficer, accountNo, externalId, status, accountType, submittedOnDate,
                submittedBy, interestRate, interestCompoundingPeriodType, interestPostingPeriodType, interestCalculationType,
                interestCalculationDaysInYearType, minRequiredOpeningBalance, lockinPeriodFrequency, lockinPeriodFrequencyType,
                withdrawalFeeApplicableForTransfer, savingsAccountCharges, accountTermAndPreClosure, chart, allowOverdraft, overdraftLimit,
                withHoldTax);
    }

    private DynamicDepositAccount(final Client client, final Group group, final SavingsProduct product, final Staff fieldOfficer,
            final String accountNo, final ExternalId externalId, final SavingsAccountStatusType status, final AccountType accountType,
            final LocalDate submittedOnDate, final AppUser submittedBy, final BigDecimal nominalAnnualInterestRate,
            final SavingsCompoundingInterestPeriodType interestCompoundingPeriodType,
            final SavingsPostingInterestPeriodType interestPostingPeriodType, final SavingsInterestCalculationType interestCalculationType,
            final SavingsInterestCalculationDaysInYearType interestCalculationDaysInYearType, final BigDecimal minRequiredOpeningBalance,
            final Integer lockinPeriodFrequency, final SavingsPeriodFrequencyType lockinPeriodFrequencyType,
            final boolean withdrawalFeeApplicableForTransfer, final Set<SavingsAccountCharge> savingsAccountCharges,
            final DepositAccountTermAndPreClosure accountTermAndPreClosure, final DepositAccountInterestRateChart chart,
            final boolean allowOverdraft, final BigDecimal overdraftLimit, final boolean withHoldTax) {

        super(client, group, product, fieldOfficer, accountNo, externalId, status, accountType, submittedOnDate, submittedBy,
                nominalAnnualInterestRate, interestCompoundingPeriodType, interestPostingPeriodType, interestCalculationType,
                interestCalculationDaysInYearType, minRequiredOpeningBalance, lockinPeriodFrequency, lockinPeriodFrequencyType,
                withdrawalFeeApplicableForTransfer, savingsAccountCharges, allowOverdraft, overdraftLimit, withHoldTax);

        this.accountTermAndPreClosure = accountTermAndPreClosure;
        this.chart = chart;
        if (this.chart != null) {
            this.chart.updateDepositAccountReference(this);
        }
    }

    public void setDynamicDetail(final DepositAccountDynamicDetail dynamicDetail) {
        this.dynamicDetail = dynamicDetail;
    }

    public DepositAccountTermAndPreClosure accountTermAndPreClosure() {
        return this.accountTermAndPreClosure;
    }

    public DepositAccountDynamicDetail dynamicDetail() {
        return this.dynamicDetail;
    }

    public DepositAccountInterestRateChart chart() {
        return this.chart;
    }

    /** Base hard-codes SAVINGS_DEPOSIT; core relies on this to tell a Dynamic Deposit apart from plain savings. */
    @Override
    public DepositAccountType depositAccountType() {
        return DepositAccountType.fromInt(500);
    }

    /** Same withhold_tax_posting_type config as FD/RD; feeds the inherited isWithHoldTaxApplicable gate. */
    @Override
    protected WithHoldTaxPostingType withHoldTaxPostingType() {
        final Integer withHoldTaxPostingTypeId = this.accountTermAndPreClosure.getWithHoldTaxPostingType();
        return withHoldTaxPostingTypeId != null ? WithHoldTaxPostingType.fromInt(withHoldTaxPostingTypeId) : null;
    }

    public boolean isAllowWithdrawal() {
        return this.dynamicDetail != null && this.dynamicDetail.isAllowWithdrawal();
    }

    /** allowWithdrawal=false blocks manual withdrawals/transfers but never a closure's payout. */
    @Override
    public boolean isWithdrawalBlockedByAccountRule() {
        return !isAllowWithdrawal() && !this.closingAtMaturity && !this.closingPrematurely;
    }

    /** Lifts the withdrawal lock for a premature closure payout only; the caller must clear it in a {@code finally}. */
    public void markClosingPrematurely(final boolean closingPrematurely) {
        this.closingPrematurely = closingPrematurely;
    }

    /** Lifts the withdrawal lock for a maturity closure payout; the caller must clear it in a {@code finally}. */
    public void markClosingAtMaturity(final boolean closingAtMaturity) {
        this.closingAtMaturity = closingAtMaturity;
    }

    public boolean isMatured() {
        return SavingsAccountStatusType.MATURED.hasStateOf(getStatus());
    }

    public void markMatured() {
        setStatus(SavingsAccountStatusType.MATURED.getValue());
    }

    /** A matured account allows a withdrawal only while its own maturity closure pays it out. */
    @Override
    public boolean isTransactionsAllowed() {
        return super.isTransactionsAllowed() || (this.closingAtMaturity && isMatured());
    }

    /** Core only closes active accounts: close a matured one as active and revert to matured on failure. */
    @Override
    public Map<String, Object> close(final AppUser currentUser, final JsonCommand command) {
        if (!isMatured()) {
            return super.close(currentUser, command);
        }
        setStatus(SavingsAccountStatusType.ACTIVE.getValue());
        try {
            return super.close(currentUser, command);
        } catch (final RuntimeException e) {
            markMatured();
            throw e;
        }
    }

    /**
     * Blocks transactions on a matured account or dated on/after maturity (covers the gap before the maturity job
     * runs), except the maturity closure payout.
     */
    private void validateMaturityAllowsTransaction(final SavingsAccountTransactionDTO transactionDTO) {
        if (this.closingAtMaturity || transactionDTO.getTransactionDate() == null) {
            return;
        }
        if (isMatured()) {
            throw maturityValidationError("error.msg." + DYNAMIC_DEPOSIT_ACCOUNT_RESOURCE_NAME + ".transaction.account.is.matured",
                    "Transaction is not allowed. Account is matured.", transactionDTO);
        }
        final LocalDate maturityDate = maturityDate();
        if (maturityDate != null && !DateUtils.isBefore(transactionDTO.getTransactionDate(), maturityDate)) {
            throw maturityValidationError(
                    "error.msg." + DYNAMIC_DEPOSIT_ACCOUNT_RESOURCE_NAME + ".transaction.date.is.after.account.maturity.date",
                    "Transaction is not allowed. Transaction date is on or after account maturity date.", transactionDTO);
        }
    }

    private static PlatformApiDataValidationException maturityValidationError(final String code, final String defaultUserMessage,
            final SavingsAccountTransactionDTO transactionDTO) {
        final ApiParameterError error = ApiParameterError.parameterError(code, defaultUserMessage, "transactionDate",
                transactionDTO.getTransactionDate().format(transactionDTO.getFormatter()));
        final List<ApiParameterError> dataValidationErrors = new ArrayList<>();
        dataValidationErrors.add(error);
        return new PlatformApiDataValidationException(dataValidationErrors);
    }

    @Override
    public void setWithdrawalChargePercentageOverride(final BigDecimal percentage) {
        this.withdrawalChargePercentageOverride = percentage;
    }

    @Override
    public BigDecimal earlyWithdrawalChargePercentageOverride() {
        return this.withdrawalChargePercentageOverride;
    }

    /** Withholds tax at closure when configured for MATURITY, as FD/RD do. Idempotent per closure date. */
    public void withholdMaturityTaxAtClosure(final LocalDate closureDate) {
        final WithHoldTaxPostingType postingType = withHoldTaxPostingType();
        if (!withHoldTax() || postingType == null || !postingType.isMaturity()) {
            return;
        }
        final boolean recalculateDailyBalances = applyWithholdTaxForDepositAccounts(closureDate, false, false, postingType);
        if (recalculateDailyBalances) {
            recalculateDailyBalances(Money.zero(this.currency), closureDate, false, false);
        }
        this.summary.updateSummary(this.currency, this.savingsAccountTransactionSummaryWrapper, this.transactions);
    }

    public boolean isDynamicRateEnabled() {
        return this.dynamicDetail != null && this.dynamicDetail.isDynamicRateEnabled();
    }

    /** Opening deposit posted at activation, unless it was already funded by a linked-account transfer. */
    @Override
    public Money activateWithBalance() {
        if (this.openingDepositFundedByLinkedTransfer) {
            return Money.zero(getCurrency());
        }
        return Money.of(getCurrency(), this.accountTermAndPreClosure.depositAmount());
    }

    public void markOpeningDepositFundedByLinkedTransfer() {
        this.openingDepositFundedByLinkedTransfer = true;
    }

    /** Tenor added to the submitted/activation date; unlike FD returns null when the term is incomplete. */
    public LocalDate calculateMaturityDate() {
        if (this.accountTermAndPreClosure == null) {
            return null;
        }
        final Integer depositPeriod = this.accountTermAndPreClosure.depositPeriod();
        final Integer depositPeriodFrequency = this.accountTermAndPreClosure.depositPeriodFrequency();
        final LocalDate startDate = accountSubmittedOrActivationDate();
        if (depositPeriod == null || depositPeriodFrequency == null || startDate == null) {
            return null;
        }
        return switch (SavingsPeriodFrequencyType.fromInt(depositPeriodFrequency)) {
            case DAYS -> startDate.plusDays(depositPeriod);
            case WEEKS -> startDate.plusWeeks(depositPeriod);
            case MONTHS -> startDate.plusMonths(depositPeriod);
            case YEARS -> startDate.plusYears(depositPeriod);
            case INVALID -> null;
        };
    }

    /** Persists the maturity date only; the stored maturity amount is left unchanged. */
    public void updateMaturityDate() {
        final LocalDate maturityDate = calculateMaturityDate();
        if (maturityDate == null) {
            return;
        }
        this.accountTermAndPreClosure.updateMaturityDetails(this.accountTermAndPreClosure.maturityAmount(), maturityDate);
    }

    /** Invested principal as of the latest transaction, whether or not interest/tax has been posted yet. */
    public BigDecimal investedAmount() {
        final List<SavingsAccountTransaction> transactions = getTransactions();
        if (transactions.isEmpty()) {
            return BigDecimal.ZERO;
        }
        final SavingsAccountTransaction last = transactions.stream().max(new SavingsAccountTransactionComparator()).orElseThrow();
        return DynamicDepositInvestedAmountCalculator
                .investedAmountAsOf(transactions, getCurrency(), interestTransferTransactionIds(), last).max(BigDecimal.ZERO);
    }

    /** Invested amount plus interest earned; only valid right after {@link #calculateInterestUsing} up to maturity. */
    public BigDecimal grossMaturityAmountAfterProjection() {
        final BigDecimal interestEarned = getSummary().getTotalInterestEarned();
        BigDecimal principal = investedAmount();
        // A closed account has paid its principal out; fall back to the stored deposit amount.
        if (principal.signum() == 0 && !isActive() && this.accountTermAndPreClosure != null
                && this.accountTermAndPreClosure.depositAmount() != null) {
            principal = this.accountTermAndPreClosure.depositAmount();
        }
        return principal.add(interestEarned == null ? BigDecimal.ZERO : interestEarned);
    }

    public LocalDate maturityDate() {
        return this.accountTermAndPreClosure == null ? null : this.accountTermAndPreClosure.getMaturityDate();
    }

    /** Any withdrawal (incl. premature closure) dated before maturity; false when no maturity date is set. */
    @Override
    public boolean isEarlyWithdrawal(final LocalDate transactionDate) {
        final LocalDate maturityDate = maturityDate();
        return maturityDate != null && transactionDate != null && DateUtils.isBefore(transactionDate, maturityDate);
    }

    /** A matured closure caps interest at the day before maturity (as FD); a premature closure is uncapped. */
    @Override
    public LocalDate interestPostingUpToForClosure(final LocalDate closedDate) {
        final LocalDate maturityDate = maturityDate();
        if (maturityDate == null || isEarlyWithdrawal(closedDate)) {
            return closedDate;
        }
        return maturityDate.minusDays(1);
    }

    /** A matured closure posts its final interest on the maturity date, not the later closedDate. */
    @Override
    public LocalDate interestPostingTransactionDateForClosure(final LocalDate closedDate) {
        final LocalDate maturityDate = maturityDate();
        if (maturityDate == null || isEarlyWithdrawal(closedDate)) {
            return closedDate;
        }
        return maturityDate;
    }

    /** Returns false when cumulative forfeiture has already posted the closing interest, so core must not repost it. */
    @Override
    public boolean beginClosureSettlement(final LocalDate closedDate) {
        if (!isEarlyWithdrawal(closedDate)) {
            return true;
        }
        this.closureSettledOn = closedDate;
        final BigDecimal chargePercentageOverride = earlyWithdrawalChargePercentageOverride();
        if (!DynamicDepositServiceLocator.cumulativeInterestForfeitureService().appliesTo(this, chargePercentageOverride)) {
            return true;
        }
        if (DateUtils.isBefore(closedDate, DateUtils.getBusinessLocalDate())) {
            throw new GeneralPlatformDomainRuleException(
                    "error.msg.savings.account.close.backdated.cumulative.early.withdrawal.not.allowed",
                    "Backdated cumulative early-withdrawal closure is not allowed.");
        }
        DynamicDepositServiceLocator.cumulativeInterestForfeitureService().forfeitIfApplicable(this, closedDate, false, true,
                chargePercentageOverride);
        return false;
    }

    public boolean isClosureSettledOn(final LocalDate date) {
        return date != null && date.equals(this.closureSettledOn);
    }

    /** nominalAnnualInterestRate is protected, so callers outside the core package need this setter. */
    public void updateNominalAnnualInterestRate(final BigDecimal nominalAnnualInterestRate) {
        this.nominalAnnualInterestRate = nominalAnnualInterestRate;
    }

    /*
     * Some write paths (transaction adjustment, backdated transfers) call these entity methods directly, bypassing the
     * service layer. Entities aren't Spring-managed, hence the static service locator.
     */

    @Override
    public SavingsAccountTransaction deposit(final SavingsAccountTransactionDTO transactionDTO,
            final SavingsAccountTransactionType savingsAccountTransactionType, final boolean backdatedTxnsAllowedTill,
            final Long relaxingDaysConfigForPivotDate, final String refNo) {
        validateMaturityAllowsTransaction(transactionDTO);
        final SavingsAccountTransaction transaction = super.deposit(transactionDTO, savingsAccountTransactionType, backdatedTxnsAllowedTill,
                relaxingDaysConfigForPivotDate, refNo);
        if (savingsAccountTransactionType == SavingsAccountTransactionType.DEPOSIT) {
            DynamicDepositServiceLocator.rateHistoryService().recordPrincipalChangeEvent(this, transaction,
                    DynamicDepositRateHistoryEventType.DEPOSIT);
        }
        return transaction;
    }

    @Override
    public SavingsAccountTransaction withdraw(final SavingsAccountTransactionDTO transactionDTO, final boolean applyWithdrawFee,
            final boolean backdatedTxnsAllowedTill, final Long relaxingDaysConfigForPivotDate, final String refNo) {
        validateMaturityAllowsTransaction(transactionDTO);
        final SavingsAccountTransaction transaction = super.withdraw(transactionDTO, applyWithdrawFee, backdatedTxnsAllowedTill,
                relaxingDaysConfigForPivotDate, refNo);
        DynamicDepositServiceLocator.rateHistoryService().recordPrincipalChangeEvent(this, transaction,
                DynamicDepositRateHistoryEventType.WITHDRAWAL);
        return transaction;
    }

    @Override
    public void undoTransaction(final Long transactionId) {
        final SavingsAccountTransaction transactionToUndo = getTransactions().stream()
                .filter(transaction -> transaction.isIdentifiedBy(transactionId)).findFirst().orElse(null);
        super.undoTransaction(transactionId);
        if (transactionToUndo != null) {
            DynamicDepositServiceLocator.rateHistoryService().reverseInvestedAmountForUndo(this, transactionToUndo);
        }
    }

    /** Splits each core posting period at rate-history dates so each sub-interval gets its own rate; else as core. */
    @Override
    public List<PostingPeriod> calculateInterestUsing(final MathContext mc, final LocalDate upToInterestCalculationDate,
            final boolean isInterestTransfer, final boolean isSavingsInterestPostingAtCurrentPeriodEnd,
            final Integer financialYearBeginningMonth, final LocalDate postInterestOnDate, final boolean backdatedTxnsAllowedTill,
            final boolean postReversals) {

        // Never accrue the maturity day itself; the caller's raw date is kept for anything outside this method.
        final LocalDate cappedUpToInterestCalculationDate = interestPostingUpToForClosure(upToInterestCalculationDate);

        final Money openingAccountBalance = backdatedTxnsAllowedTill ? Money.of(this.currency, getSummary().getRunningBalanceOnPivotDate())
                : Money.zero(this.currency);
        recalculateDailyBalances(openingAccountBalance, cappedUpToInterestCalculationDate, backdatedTxnsAllowedTill, postReversals);

        final List<PostingPeriod> allPostingPeriods = new ArrayList<>();
        if (hasInterestCalculation()) {
            final SavingsPostingInterestPeriodType postingPeriodType = SavingsPostingInterestPeriodType
                    .fromInt(this.interestPostingPeriodType);
            final SavingsCompoundingInterestPeriodType compoundingPeriodType = SavingsCompoundingInterestPeriodType
                    .fromInt(this.interestCompoundingPeriodType);
            final SavingsInterestCalculationDaysInYearType daysInYearType = SavingsInterestCalculationDaysInYearType
                    .fromInt(this.interestCalculationDaysInYearType);
            final SavingsInterestCalculationType interestCalculationType = SavingsInterestCalculationType
                    .fromInt(this.interestCalculationType);

            final List<LocalDate> postedAsOnDates = backdatedTxnsAllowedTill ? getManualPostingDatesWithPivotConfig()
                    : getManualPostingDates();
            if (postInterestOnDate != null) {
                postedAsOnDates.add(postInterestOnDate);
            }

            final List<LocalDateInterval> corePostingPeriodIntervals = this.savingsHelper.determineInterestPostingPeriods(
                    getStartInterestCalculationDate(), cappedUpToInterestCalculationDate, postingPeriodType, financialYearBeginningMonth,
                    postedAsOnDates);

            final List<DepositAccountDynamicRateHistory> rateHistoryAscending = withSimulatedRateHistory(
                    DynamicDepositServiceLocator.rateHistoryRepository().findByAccountIdOrderByTransactionDateAscIdAsc(getId()));
            final List<DynamicDepositInterestIntervalSplitter.RatedInterval> ratedIntervals = DynamicDepositInterestIntervalSplitter
                    .split(corePostingPeriodIntervals, rateHistoryAscending);

            Money periodStartingBalance = openingStartingBalance(backdatedTxnsAllowedTill);
            final Collection<Long> interestPostTransactions = this.savingsHelper.fetchPostInterestTransactionIds(getId());
            final Money minBalanceForInterestCalculation = Money.of(getCurrency(), minBalanceForInterestCalculation());
            final List<SavingsAccountTransaction> orderedNonInterestPostingTransactions = backdatedTxnsAllowedTill
                    ? retreiveOrderedNonInterestPostingSavingsTransactionsWithPivotConfig()
                    : retreiveOrderedNonInterestPostingTransactions();
            final List<SavingsAccountTransactionDetailsForPostingPeriod> transactionDetails = toSavingsAccountTransactionDetailsForPostingPeriodList(
                    orderedNonInterestPostingTransactions);

            for (final DynamicDepositInterestIntervalSplitter.RatedInterval ratedInterval : ratedIntervals) {
                final boolean isUserPosting = postedAsOnDates.contains(ratedInterval.periodInterval().endDate().plusDays(1));
                final BigDecimal interestRateAsFraction = ratedInterval.annualInterestRate().divide(BigDecimal.valueOf(100L), mc);

                final PostingPeriod postingPeriod = PostingPeriod.createFrom(ratedInterval.periodInterval(), periodStartingBalance,
                        transactionDetails, this.currency, compoundingPeriodType, interestCalculationType, interestRateAsFraction,
                        daysInYearType.getValue(), cappedUpToInterestCalculationDate, interestPostTransactions, isInterestTransfer,
                        minBalanceForInterestCalculation, isSavingsInterestPostingAtCurrentPeriodEnd, isUserPosting,
                        financialYearBeginningMonth);

                periodStartingBalance = postingPeriod.closingBalance();
                allPostingPeriods.add(postingPeriod);
            }

            this.savingsHelper.calculateInterestForAllPostingPeriods(this.currency, allPostingPeriods, getLockedInUntilDate(),
                    isTransferInterestToOtherAccount());
        }

        if (hasStartInterestCalculationDate()) {
            final BigDecimal preStartInterest = this.savingsHelper.sumInterestPostingsOnOrBeforeDate(getId(),
                    getStartInterestCalculationDate());
            this.summary.setPreStartDateInterestEarned(preStartInterest);
        }
        this.summary.updateFromInterestPeriodSummaries(this.currency, allPostingPeriods, hasStartInterestCalculationDate());
        if (backdatedTxnsAllowedTill) {
            this.summary.updateSummaryWithPivotConfig(this.currency, this.savingsAccountTransactionSummaryWrapper, null,
                    this.savingsAccountTransactions);
        } else {
            this.summary.updateSummary(this.currency, this.savingsAccountTransactionSummaryWrapper, this.transactions);
        }
        return allPostingPeriods;
    }

    private Money openingStartingBalance(final boolean backdatedTxnsAllowedTill) {
        if (!hasStartInterestCalculationDate()) {
            return Money.zero(this.currency);
        }
        final BigDecimal runningBalanceOnPivotDate = getSummary().getRunningBalanceOnPivotDate();
        return runningBalanceOnPivotDate == null ? Money.zero(this.currency) : Money.of(this.currency, runningBalanceOnPivotDate);
    }

    /** Interest-transfer withdrawal ids; empty for an unsaved account such as a rate preview copy. */
    public Set<Long> interestTransferTransactionIds() {
        if (this.savingsHelper == null || getId() == null) {
            return Set.of();
        }
        return new HashSet<>(this.savingsHelper.fetchPostInterestTransactionIds(getId()));
    }

    /**
     * Regroups the rate sub-periods into core posting boundaries so one interest transaction is written per period; the
     * create/correct/withhold logic mirrors {@code SavingsAccount#postInterest}.
     */
    @Override
    public void postInterest(final MathContext mc, final LocalDate interestPostingUpToDate, final boolean isInterestTransfer,
            final boolean isSavingsInterestPostingAtCurrentPeriodEnd, final Integer financialYearBeginningMonth,
            final LocalDate postInterestOnDate, final boolean backdatedTxnsAllowedTill, final boolean postReversals) {

        // Only the boundaries are capped: the guard below needs the raw date, as the final posting lands the day
        // after the last boundary.
        final LocalDate cappedInterestPostingUpToDate = interestPostingUpToForClosure(interestPostingUpToDate);

        final List<PostingPeriod> ratedSubPeriods = calculateInterestUsing(mc, interestPostingUpToDate, isInterestTransfer,
                isSavingsInterestPostingAtCurrentPeriodEnd, financialYearBeginningMonth, postInterestOnDate, backdatedTxnsAllowedTill,
                postReversals);
        if (ratedSubPeriods.isEmpty()) {
            return;
        }

        final List<LocalDate> postedAsOnDates = backdatedTxnsAllowedTill ? getManualPostingDatesWithPivotConfig() : getManualPostingDates();
        if (postInterestOnDate != null) {
            postedAsOnDates.add(postInterestOnDate);
        }
        final SavingsPostingInterestPeriodType postingPeriodType = SavingsPostingInterestPeriodType.fromInt(this.interestPostingPeriodType);
        final List<LocalDateInterval> coreBoundaries = this.savingsHelper.determineInterestPostingPeriods(getStartInterestCalculationDate(),
                cappedInterestPostingUpToDate, postingPeriodType, financialYearBeginningMonth, postedAsOnDates);

        Money interestPostedToDate = backdatedTxnsAllowedTill ? Money.of(this.currency, getSummary().getTotalInterestPosted())
                : Money.zero(this.currency);

        boolean recalucateDailyBalanceDetails = false;
        final boolean applyWithHoldTax = isWithHoldTaxApplicable(withHoldTaxPostingType());
        final List<SavingsAccountTransaction> withholdTransactions = new ArrayList<>();
        if (backdatedTxnsAllowedTill) {
            withholdTransactions.addAll(findWithHoldSavingsTransactionsWithPivotConfig());
        } else {
            withholdTransactions.addAll(findWithHoldTransactions());
        }

        Money totalCorrectionAmount = Money.zero(this.currency);
        for (final LocalDateInterval coreBoundary : coreBoundaries) {
            Money interestEarnedToBePostedForPeriod = Money.zero(this.currency);
            final List<PostingPeriod> boundarySubPeriods = new ArrayList<>();
            for (final PostingPeriod subPeriod : ratedSubPeriods) {
                if (coreBoundary.contains(subPeriod.getPeriodInterval().startDate())) {
                    boundarySubPeriods.add(subPeriod);
                    interestEarnedToBePostedForPeriod = interestEarnedToBePostedForPeriod.plus(subPeriod.getInterestEarned());
                }
            }
            final boolean isUserPosting = postedAsOnDates.contains(coreBoundary.endDate().plusDays(1));
            // As in core: the posting date depends on the period-end flag, isUserPosting does not.
            final LocalDate interestPostingTransactionDate = isSavingsInterestPostingAtCurrentPeriodEnd ? coreBoundary.endDate()
                    : coreBoundary.endDate().plusDays(1);

            if (!DateUtils.isAfter(interestPostingTransactionDate, interestPostingUpToDate)) {
                interestPostedToDate = interestPostedToDate.plus(interestEarnedToBePostedForPeriod);

                SavingsAccountTransaction postingTransaction = null;
                if (backdatedTxnsAllowedTill) {
                    postingTransaction = findInterestPostingSavingsTransactionWithPivotConfig(interestPostingTransactionDate);
                } else {
                    postingTransaction = findInterestPostingTransactionFor(interestPostingTransactionDate);
                }
                if (postingTransaction == null) {
                    SavingsAccountTransaction newPostingTransaction = null;
                    if (interestEarnedToBePostedForPeriod.isGreaterThanOrEqualTo(Money.zero(this.currency))) {
                        if (interestEarnedToBePostedForPeriod.isGreaterThan(Money.zero(this.currency))) {
                            newPostingTransaction = SavingsAccountTransaction.interestPosting(this, office(),
                                    interestPostingTransactionDate, interestEarnedToBePostedForPeriod, isUserPosting);
                        }
                    } else {
                        newPostingTransaction = SavingsAccountTransaction.overdraftInterest(this, office(), interestPostingTransactionDate,
                                interestEarnedToBePostedForPeriod.negated(), isUserPosting);
                    }
                    if (newPostingTransaction != null) {
                        if (backdatedTxnsAllowedTill) {
                            addTransactionToExisting(newPostingTransaction);
                        } else {
                            addTransaction(newPostingTransaction);
                        }

                        if (applyWithHoldTax) {
                            createWithHoldTransactionAndFind(interestEarnedToBePostedForPeriod.getAmount(), interestPostingTransactionDate,
                                    backdatedTxnsAllowedTill);
                        }
                        recalucateDailyBalanceDetails = true;
                    }

                } else {
                    boolean correctionRequired = false;
                    if (postingTransaction.isInterestPostingAndNotReversed()) {
                        correctionRequired = postingTransaction.hasNotAmount(interestEarnedToBePostedForPeriod);
                    } else {
                        correctionRequired = postingTransaction.hasNotAmount(interestEarnedToBePostedForPeriod.negated());
                    }
                    if (correctionRequired) {
                        totalCorrectionAmount = totalCorrectionAmount.plus(postingTransaction.getAmount());
                        boolean applyWithHoldTaxForOldTransaction = false;
                        postingTransaction.reverse();
                        SavingsAccountTransaction reversal = null;
                        if (postReversals) {
                            reversal = SavingsAccountTransaction.reversal(postingTransaction);
                        }
                        final SavingsAccountTransaction withholdTransaction = findTransactionFor(interestPostingTransactionDate,
                                withholdTransactions);
                        if (withholdTransaction != null) {
                            withholdTransaction.reverse();
                            applyWithHoldTaxForOldTransaction = true;
                        }
                        SavingsAccountTransaction newPostingTransaction;
                        if (interestEarnedToBePostedForPeriod.isGreaterThanOrEqualTo(Money.zero(this.currency))) {
                            newPostingTransaction = SavingsAccountTransaction.interestPosting(this, office(),
                                    interestPostingTransactionDate, interestEarnedToBePostedForPeriod, isUserPosting);
                        } else {
                            newPostingTransaction = SavingsAccountTransaction.overdraftInterest(this, office(),
                                    interestPostingTransactionDate, interestEarnedToBePostedForPeriod.negated(), isUserPosting);
                        }
                        if (backdatedTxnsAllowedTill) {
                            addTransactionToExisting(newPostingTransaction);
                            if (reversal != null) {
                                addTransactionToExisting(reversal);
                            }
                        } else {
                            addTransaction(newPostingTransaction);
                            if (reversal != null) {
                                addTransaction(reversal);
                            }
                        }
                        if (applyWithHoldTaxForOldTransaction) {
                            createWithHoldTransactionAndFind(interestEarnedToBePostedForPeriod.getAmount(), interestPostingTransactionDate,
                                    backdatedTxnsAllowedTill);
                        }
                        recalucateDailyBalanceDetails = true;
                    }
                }
            }
        }

        if (recalucateDailyBalanceDetails) {
            Money openingAccountBalance = Money.zero(this.currency);
            if (backdatedTxnsAllowedTill) {
                if (getSummary().getLastInterestCalculationDate() == null) {
                    openingAccountBalance = Money.zero(this.currency);
                } else {
                    openingAccountBalance = Money.of(this.currency, getSummary().getRunningBalanceOnPivotDate());
                }
            }
            recalculateDailyBalances(openingAccountBalance, interestPostingUpToDate, backdatedTxnsAllowedTill, postReversals);
        }

        if (!backdatedTxnsAllowedTill) {
            this.summary.updateSummary(this.currency, this.savingsAccountTransactionSummaryWrapper, this.transactions);
        } else {
            this.summary.updateSummaryWithPivotConfig(this.currency, this.savingsAccountTransactionSummaryWrapper, null,
                    this.savingsAccountTransactions);
        }
    }

    private SavingsAccountTransaction createWithHoldTransactionAndFind(final BigDecimal amount, final LocalDate date,
            final boolean backdatedTxnsAllowedTill) {
        if (!createWithHoldTransaction(amount, date, backdatedTxnsAllowedTill)) {
            return null;
        }
        return findWithHoldTransactionFor(date, backdatedTxnsAllowedTill);
    }

    private SavingsAccountTransaction findWithHoldTransactionFor(final LocalDate date, final boolean backdatedTxnsAllowedTill) {
        final List<SavingsAccountTransaction> transactions = backdatedTxnsAllowedTill ? getSavingsAccountTransactionsWithPivotConfig()
                : getTransactions();
        SavingsAccountTransaction found = null;
        for (final SavingsAccountTransaction transaction : transactions) {
            if (transaction.isWithHoldTaxAndNotReversed() && !transaction.isReversalTransaction() && transaction.occursOn(date)) {
                found = transaction;
            }
        }
        return found;
    }

    @Override
    public void modifyApplication(final JsonCommand command, final Map<String, Object> actualChanges) {
        final List<ApiParameterError> dataValidationErrors = new ArrayList<>();
        final DataValidatorBuilder baseDataValidator = new DataValidatorBuilder(dataValidationErrors)
                .resource(DYNAMIC_DEPOSIT_ACCOUNT_RESOURCE_NAME + SavingsApiConstants.modifyApplicationAction);
        super.modifyApplication(command, actualChanges, baseDataValidator);

        if (this.accountTermAndPreClosure != null) {
            actualChanges.putAll(this.accountTermAndPreClosure.update(command, baseDataValidator));
        }
        if (this.dynamicDetail != null) {
            actualChanges.putAll(this.dynamicDetail.update(command));
        }

        if (!dataValidationErrors.isEmpty()) {
            throw new PlatformApiDataValidationException(dataValidationErrors);
        }
    }

    /** Also re-activates a PRE_MATURE_CLOSURE account whose balance became non-zero again (e.g. closing undone). */
    @Override
    public void activateAccountBasedOnBalance() {
        super.activateAccountBasedOnBalance();
        if (getStatus().isPreMatureClosure() && getAccountBalance().signum() != 0) {
            setStatus(SavingsAccountStatusType.ACTIVE.getValue());
        }
    }
}
