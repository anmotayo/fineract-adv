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

import com.advancly.fineract.portfolio.savings.service.DynamicDepositServiceLocator;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.DiscriminatorValue;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.OneToOne;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.fineract.infrastructure.core.api.JsonCommand;
import org.apache.fineract.infrastructure.core.data.ApiParameterError;
import org.apache.fineract.infrastructure.core.data.DataValidatorBuilder;
import org.apache.fineract.infrastructure.core.domain.ExternalId;
import org.apache.fineract.infrastructure.core.domain.LocalDateInterval;
import org.apache.fineract.infrastructure.core.exception.PlatformApiDataValidationException;
import org.apache.fineract.infrastructure.core.service.DateUtils;
import org.apache.fineract.organisation.monetary.domain.Money;
import org.apache.fineract.organisation.monetary.domain.MoneyHelper;
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
import org.apache.fineract.portfolio.savings.domain.SavingsAccountChargePaidBy;
import org.apache.fineract.portfolio.savings.domain.SavingsAccountStatusType;
import org.apache.fineract.portfolio.savings.domain.SavingsAccountTransaction;
import org.apache.fineract.portfolio.savings.domain.SavingsProduct;
import org.apache.fineract.portfolio.savings.domain.interest.PostingPeriod;
import org.apache.fineract.portfolio.savings.domain.interest.SavingsAccountTransactionDetailsForPostingPeriod;
import org.apache.fineract.useradministration.domain.AppUser;

/**
 * The Dynamic Deposit account (deposit_type_enum = 500).
 *
 * Deliberately lean for Phase 1 - a valid {@code SavingsAccount} subclass composing the reused, generic
 * {@link DepositAccountTermAndPreClosure} (fixed tenor + invested amount, exactly as {@code FixedDepositAccount} does)
 * and the new {@link DepositAccountDynamicDetail} (allow-withdrawal / dynamic-rate-enabled). It intentionally does NOT
 * port {@code FixedDepositAccount}'s ~900 lines of preclosure-penalty / maturity-date business logic - that is out of
 * scope for Phase 1 (see the implementation plan, phases 2-5) and this class relies on {@code SavingsAccount}'s own
 * default behaviour (e.g. {@code getEffectiveInterestRateAsFraction} simply uses {@code nominalAnnualInterestRate},
 * which {@code DynamicDepositAccountAssembler} resolves once at submission time per business rule 6 of the plan) until
 * the dynamic rate history / interest engine land in later phases.
 */
@Entity
@DiscriminatorValue("500")
public class DynamicDepositAccount extends SavingsAccount {

    @OneToOne(mappedBy = "account", cascade = CascadeType.ALL)
    private DepositAccountTermAndPreClosure accountTermAndPreClosure;

    @OneToOne(mappedBy = "account", cascade = CascadeType.ALL)
    private DepositAccountDynamicDetail dynamicDetail;

    /**
     * The account's own snapshot of the product's interest rate chart at submission time, mirroring
     * {@code FixedDepositAccount}/{@code RecurringDepositAccount}'s {@code chart} field - so a later edit to the
     * product's chart never retroactively changes the rate an already-open account resolves against. Rate resolution
     * ({@link com.advancly.fineract.portfolio.savings.service.DynamicDepositRateResolutionService}) reads this, never
     * the product's chart directly.
     */
    @OneToOne(fetch = FetchType.LAZY, cascade = CascadeType.ALL, mappedBy = "account", orphanRemoval = true)
    private DepositAccountInterestRateChart chart;

    /**
     * Implementation plan Section 5. Read-side conveniences only: {@code interestBasedChargeDerived} is the current
     * calculated/pending interest-based charge amount and {@code interestBasedChargePostedDerived} the total already
     * applied through interest posting. The spec is explicit that these are "not used as the source of truth for
     * posting, reversals, or accounting" - that remains {@code m_deposit_account_interest_charge} plus the linked
     * transactions.
     *
     * Mapped here, on the subclass, rather than on {@code SavingsAccount}: the hierarchy is
     * {@code InheritanceType.SINGLE_TABLE} (see {@code SavingsAccount}'s class annotations), so these become two extra
     * nullable columns on {@code m_savings_account} without touching the class every savings and deposit account type
     * shares. Nullable is therefore mandatory - every existing row, and every non-Dynamic-Deposit row, leaves them NULL
     * - which is why both accessors below normalise NULL to zero.
     */
    @Column(name = "interest_based_charge_derived", scale = 6, precision = 19)
    private BigDecimal interestBasedChargeDerived;

    @Column(name = "interest_based_charge_posted_derived", scale = 6, precision = 19)
    private BigDecimal interestBasedChargePostedDerived;

    protected DynamicDepositAccount() {
        //
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

    /**
     * Without this override, {@code depositAccountType()} would fall through to the {@code SavingsAccount} base
     * implementation, which is hard-coded to {@code SAVINGS_DEPOSIT} - mirrors the equivalent overrides on
     * {@code FixedDepositAccount}/{@code RecurringDepositAccount}. Core code (e.g. the interest-based-charge gate in
     * {@code SavingsAccountChargeAssembler}/{@code SavingsAccountWritePlatformServiceJpaRepositoryImpl}) relies on this
     * to tell a Dynamic Deposit account apart from a plain savings account without depending on this custom-module
     * class directly.
     */
    @Override
    public DepositAccountType depositAccountType() {
        return DepositAccountType.fromInt(500);
    }

    /**
     * Structural mirror of {@code FixedDepositAccount}/{@code RecurringDepositAccount}#withHoldTaxPostingType(),
     * reading the same {@code withhold_tax_posting_type_enum} config off the reused, generic
     * {@link DepositAccountTermAndPreClosure} this class already composes. In practice this always returns {@code null}
     * today: neither {@code DynamicDepositAccountAssembler} nor {@code DynamicDepositApiConstants} ever populate or
     * accept a {@code withHoldTaxPostingTypeId} for a Dynamic Deposit account or product (Phase 1 deliberately has no
     * product-level {@code DepositProductTermAndPreClosure}-equivalent to default from either). Kept for structural
     * parity with FD/RD and in case a future phase adds real posting-type support - see
     * {@link #isWithHoldTaxApplicable(WithHoldTaxPostingType)} below, which does NOT consult this method's return
     * value, precisely because it can never be meaningfully populated yet.
     */
    @Override
    protected WithHoldTaxPostingType withHoldTaxPostingType() {
        final Integer withHoldTaxPostingTypeId = this.accountTermAndPreClosure.getWithHoldTaxPostingType();
        return withHoldTaxPostingTypeId != null ? WithHoldTaxPostingType.fromInt(withHoldTaxPostingTypeId) : null;
    }

    /**
     * {@code SavingsAccount}'s base implementation is {@code withHoldTax() && (depositAccountType().isSavingsDeposit()
     * || (withHoldTaxPostingType != null && withHoldTaxPostingType.isInterestPosting()))} - the first disjunct is
     * {@code false} for Dynamic Deposit (see the {@code depositAccountType()} override above), and the second can never
     * be {@code true} either, since {@link #withHoldTaxPostingType()} always returns {@code null} in practice (see its
     * javadoc). Left as-is, that combination would make withholding tax unconditionally unavailable for Dynamic Deposit
     * accounts regardless of the {@code withHoldTax} flag - silently disabling this fork's original core feature for
     * the new product type. Rather than adding new API surface to configure a real posting type (a deliberate product
     * decision for a future phase), this override restores the simpler, previously-working behaviour: for Dynamic
     * Deposit, WHT applicability is keyed on the {@code withHoldTax} flag alone.
     */
    @Override
    public boolean isWithHoldTaxApplicable(final WithHoldTaxPostingType withHoldTaxPostingType) {
        return withHoldTax();
    }

    public boolean isAllowWithdrawal() {
        return this.dynamicDetail != null && this.dynamicDetail.isAllowWithdrawal();
    }

    public boolean isDynamicRateEnabled() {
        return this.dynamicDetail != null && this.dynamicDetail.isDynamicRateEnabled();
    }

    public BigDecimal interestBasedChargeDerived() {
        return this.interestBasedChargeDerived == null ? BigDecimal.ZERO : this.interestBasedChargeDerived;
    }

    public BigDecimal interestBasedChargePostedDerived() {
        return this.interestBasedChargePostedDerived == null ? BigDecimal.ZERO : this.interestBasedChargePostedDerived;
    }

    public void updateInterestBasedChargeDerived(final BigDecimal amount) {
        this.interestBasedChargeDerived = amount == null ? BigDecimal.ZERO : amount;
    }

    public void updateInterestBasedChargePostedDerived(final BigDecimal amount) {
        this.interestBasedChargePostedDerived = amount == null ? BigDecimal.ZERO : amount;
    }

    /**
     * Mirrors {@code FixedDepositAccount#activateWithBalance()} - without this override, activation would use the
     * generic {@code SavingsAccount} default (no opening balance), and {@code processPostActiveActions} would never
     * create the opening-funding transaction this account's first rate-history row needs to key to (see
     * {@code DynamicDepositAccountWritePlatformServiceJpaRepositoryImpl#activate}).
     */
    @Override
    public Money activateWithBalance() {
        return Money.of(getCurrency(), this.accountTermAndPreClosure.depositAmount());
    }

    /**
     * Phase 4 prerequisite. Mirrors {@code FixedDepositAccount#calculateMaturityDate()}: the fixed tenor
     * ({@code depositPeriod} in {@code depositPeriodFrequencyType} units, held on the reused, generic
     * {@link DepositAccountTermAndPreClosure}) added to {@code accountSubmittedOrActivationDate()} - so the anchor is
     * the submitted-on date while the application is still pending and the activation date once the account is live.
     * Unlike FD this returns {@code null} rather than NPE-ing when the term is unusable (FD dereferences its own result
     * unguarded); {@link #updateMaturityDate()} treats {@code null} as "nothing to persist".
     */
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

    /**
     * Persists {@link #calculateMaturityDate()} onto the account's {@link DepositAccountTermAndPreClosure}. Phase 4
     * deliberately stores the DATE ONLY: {@code FixedDepositAccount} also projects a {@code maturityAmount} via a full
     * interest projection, which for Dynamic Deposit would mean projecting across every dynamic rate-history interval -
     * out of scope for this phase and needed by none of its requirements. The currently-stored {@code maturityAmount}
     * (always {@code null} today, since nothing writes it) is therefore passed straight back through, so this method
     * never invents an amount.
     */
    public void updateMaturityDate() {
        final LocalDate maturityDate = calculateMaturityDate();
        if (maturityDate == null) {
            return;
        }
        this.accountTermAndPreClosure.updateMaturityDetails(this.accountTermAndPreClosure.maturityAmount(), maturityDate);
    }

    public LocalDate maturityDate() {
        return this.accountTermAndPreClosure == null ? null : this.accountTermAndPreClosure.getMaturityDate();
    }

    /**
     * The binding Phase 4 definition of "early withdrawal": any withdrawal transaction dated strictly before the
     * account's maturity date. Premature closure is covered by the same predicate, because every close-with-withdrawal
     * path converges on a withdrawal transaction dated before maturity (see the plan's Global Constraints). Returns
     * {@code false} when no maturity date has been computed, so an unactivated or misconfigured account can never be
     * penalised.
     */
    public boolean isEarlyWithdrawal(final LocalDate transactionDate) {
        final LocalDate maturityDate = maturityDate();
        return maturityDate != null && transactionDate != null && DateUtils.isBefore(transactionDate, maturityDate);
    }

    /**
     * Lets {@link com.advancly.fineract.portfolio.savings.service.DynamicDepositRateHistoryService} keep the account's
     * own rate current between Phase 2 (this class) and the Phase 3 interest engine landing (see implementation plan,
     * Section 8) - {@code SavingsAccount.nominalAnnualInterestRate} is {@code protected}, so a same-hierarchy setter is
     * needed since callers of this class live outside the {@code org.apache.fineract.portfolio.savings.domain} package.
     */
    public void updateNominalAnnualInterestRate(final BigDecimal nominalAnnualInterestRate) {
        this.nominalAnnualInterestRate = nominalAnnualInterestRate;
    }

    /*
     * Phase 2 principal-changing-transaction hooks (implementation plan, Section 6). Overridden here rather than hooked
     * purely at the service layer because a few write paths call these entity methods directly, with no domain-service
     * in between: SavingsAccountWritePlatformServiceJpaRepositoryImpl#adjustSavingsTransaction (the replacement
     * transaction) and backdated account transfers (core SavingsAccountDomainServiceJpa, which bypasses the Advancly
     * wrapper). AdvanclySavingsAccountDomainService separately hooks the optimized append-path and reversal cases,
     * which never reach these entity methods at all - see that class's own override notes. Entities are not
     * Spring-managed, hence the static service locator instead of constructor injection.
     */

    @Override
    public SavingsAccountTransaction deposit(final SavingsAccountTransactionDTO transactionDTO,
            final SavingsAccountTransactionType savingsAccountTransactionType, final boolean backdatedTxnsAllowedTill,
            final Long relaxingDaysConfigForPivotDate, final String refNo) {
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
        final SavingsAccountTransaction transaction = super.withdraw(transactionDTO, applyWithdrawFee, backdatedTxnsAllowedTill,
                relaxingDaysConfigForPivotDate, refNo);
        DynamicDepositServiceLocator.rateHistoryService().recordPrincipalChangeEvent(this, transaction,
                DynamicDepositRateHistoryEventType.WITHDRAWAL);
        DynamicDepositServiceLocator.interestWithdrawalService().recordIfApplicable(this, transaction);
        // Phase 4: any withdrawal dated before maturity - including the withdrawal the generic core `close` command
        // issues when withdrawBalance=true, i.e. premature closure - creates a pending early-withdrawal charge row.
        DynamicDepositServiceLocator.earlyWithdrawalChargeService().recordIfApplicable(this, transaction);
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
        // The undone transaction may be an early-withdrawal charge posting or the withdrawal a pending charge row is
        // linked to; either way the repository queries backing these two columns already exclude reversed rows/links
        // (see DepositAccountInterestChargeRepository), so m_deposit_account_interest_charge itself stays correct -
        // but the fast-read derived columns on this row are otherwise only refreshed inside
        // applyPendingInterestBasedCharges(...) and would go stale (too high) until the next early
        // withdrawal happens to refresh them. Recompute unconditionally rather than only when transactionToUndo is a
        // charge/withdrawal, since it costs two cheap aggregate queries and keeps this correct regardless of which
        // transaction type was undone.
        final var interestChargeRepository = DynamicDepositServiceLocator.interestChargeRepository();
        updateInterestBasedChargeDerived(interestChargeRepository.sumPendingChargeAmount(getId()));
        updateInterestBasedChargePostedDerived(interestChargeRepository.sumPostedChargeAmount(getId()));
    }

    /**
     * Phase 3 interest engine (implementation plan Section 8). {@code SavingsAccount.postInterest(...)} - inherited
     * here unmodified - calls this method to get the list of {@code PostingPeriod}s it then turns into transactions,
     * corrections, and withholding tax exactly as it does for every other account type. The only thing this override
     * changes is which interest rate each {@code PostingPeriod} is built with: instead of one account-wide
     * {@code nominalAnnualInterestRate} for every period (core's behaviour), each core posting-period interval is first
     * split at every {@link DepositAccountDynamicRateHistory} transaction date that falls inside it
     * ({@link DynamicDepositInterestIntervalSplitter}), and each resulting sub-interval gets its own captured
     * {@code resolvedAnnualInterestRate}. Everything else - daily balance recalculation, the summary update, backdated
     * transaction handling - is copied from {@code SavingsAccount.calculateInterestUsing} unchanged; this account type
     * has no overdraft support (see the constructor, {@code allowOverdraft} is always {@code false}), so the
     * overdraft-related parameters core threads through are omitted entirely.
     */
    @Override
    public List<PostingPeriod> calculateInterestUsing(final MathContext mc, final LocalDate upToInterestCalculationDate,
            final boolean isInterestTransfer, final boolean isSavingsInterestPostingAtCurrentPeriodEnd,
            final Integer financialYearBeginningMonth, final LocalDate postInterestOnDate, final boolean backdatedTxnsAllowedTill,
            final boolean postReversals) {

        final Money openingAccountBalance = backdatedTxnsAllowedTill ? Money.of(this.currency, getSummary().getRunningBalanceOnPivotDate())
                : Money.zero(this.currency);
        recalculateDailyBalances(openingAccountBalance, upToInterestCalculationDate, backdatedTxnsAllowedTill, postReversals);

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
                    getStartInterestCalculationDate(), upToInterestCalculationDate, postingPeriodType, financialYearBeginningMonth,
                    postedAsOnDates);

            final List<DepositAccountDynamicRateHistory> rateHistoryAscending = DynamicDepositServiceLocator.rateHistoryRepository()
                    .findByAccountIdOrderByTransactionDateAscIdAsc(getId());
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
                        daysInYearType.getValue(), upToInterestCalculationDate, interestPostTransactions, isInterestTransfer,
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

    /**
     * Design correction (see implementation plan, Task 3): {@code SavingsAccount.postInterest(...)} writes one
     * transaction per <em>distinct</em> {@code PostingPeriod.dateOfPostingTransaction()} it sees in the list
     * {@link #calculateInterestUsing} returns. Since that override now returns one {@code PostingPeriod} per
     * rate-history sub-interval - not one per core posting-period boundary - calling the inherited {@code postInterest}
     * unmodified would write multiple, fragmented interest-posting transactions for a single posting period whenever a
     * rate change falls inside it (confirmed empirically during this task's first implementation attempt). This
     * override recomputes the same core posting-period boundaries that {@link #calculateInterestUsing} used internally
     * (a pure function of the same inputs, so recomputing it here is safe - it is not a race, and requires no change to
     * {@code calculateInterestUsing}'s inherited return type), groups the returned rate-varying sub-periods back into
     * those boundaries using {@code PostingPeriod.getPeriodInterval()} (public - each sub-period's interval start date
     * falls inside exactly one boundary, so this containment check is immune to any boundary/sub-period count mismatch
     * - no positional index is involved), sums each boundary's sub-periods into a single amount, and replicates
     * {@code SavingsAccount.postInterest}'s own transaction create-or-correct/withholding-tax branch and tail exactly,
     * substituting only the per-boundary summed amount and date for the per-{@code PostingPeriod} equivalents core
     * uses.
     */
    @Override
    public void postInterest(final MathContext mc, final LocalDate interestPostingUpToDate, final boolean isInterestTransfer,
            final boolean isSavingsInterestPostingAtCurrentPeriodEnd, final Integer financialYearBeginningMonth,
            final LocalDate postInterestOnDate, final boolean backdatedTxnsAllowedTill, final boolean postReversals) {

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
                interestPostingUpToDate, postingPeriodType, financialYearBeginningMonth, postedAsOnDates);

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
            // Group the rate-varying sub-periods calculateInterestUsing(...) returned back into this core boundary
            // using PostingPeriod.getPeriodInterval() (public) directly - no positional-index recomputation needed,
            // and therefore no way for a boundary/sub-period count mismatch to silently misattribute interest.
            Money interestEarnedToBePostedForPeriod = Money.zero(this.currency);
            for (final PostingPeriod subPeriod : ratedSubPeriods) {
                if (coreBoundary.contains(subPeriod.getPeriodInterval().startDate())) {
                    interestEarnedToBePostedForPeriod = interestEarnedToBePostedForPeriod.plus(subPeriod.getInterestEarned());
                }
            }
            final boolean isUserPosting = postedAsOnDates.contains(coreBoundary.endDate().plusDays(1));
            // Mirrors core's PostingPeriod construction: dateOfPostingTransaction is endDate() when
            // isSavingsInterestPostingAtCurrentPeriodEnd is true, endDate().plusDays(1) otherwise. isUserPosting above
            // stays keyed to endDate().plusDays(1) unconditionally - core computes it that way regardless of the flag
            // too (SavingsAccount.java:911).
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
                            createWithHoldTransaction(interestEarnedToBePostedForPeriod.getAmount(), interestPostingTransactionDate,
                                    backdatedTxnsAllowedTill);
                        }
                        // Phase 4, Section 10 steps 2/4/7/8 - run AFTER the withholding tax transaction exists, since
                        // the charge is capped at gross interest minus that tax. Deliberately not applied in the
                        // correction branch below: core reverses only the posting and withholding transactions there,
                        // so an already-applied charge transaction and its row links still stand.
                        applyPendingInterestBasedCharges(interestPostingTransactionDate, interestEarnedToBePostedForPeriod,
                                newPostingTransaction, backdatedTxnsAllowedTill);
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
                            createWithHoldTransaction(interestEarnedToBePostedForPeriod.getAmount(), interestPostingTransactionDate,
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

    private static final BigDecimal ONE_HUNDRED = BigDecimal.valueOf(100L);

    // NOT a static constant: MoneyHelper.getRoundingMode() resolves the CURRENT tenant's configured rounding mode
    // from a thread-local, so freezing it in a static initialiser would either fail at class-load time or apply one
    // tenant's setting to every other tenant. Core's own SavingsAccountCharge#percentageOf builds its MathContext per
    // call for the same reason.
    private static MathContext percentageMathContext() {
        return new MathContext(8, MoneyHelper.getRoundingMode());
    }

    /**
     * The authoritative cap on a period's interest-based charge (implementation plan Section 11 "Preserve principal",
     * Section 10 step 9). Gross interest has just been credited and the withholding tax just debited, so capping the
     * charge at their difference guarantees the whole posting's net effect on the balance is {@code gross - wht -
     * charge >= 0} - i.e. the charge always comes out of interest and never reaches principal. Capping at gross alone
     * would not be sufficient: with gross 500, withholding tax 50 and 500 recomputed, the balance would fall by 50.
     *
     * Package-private static so the arithmetic is unit-testable without building a whole posting run.
     */
    static BigDecimal cappedInterestBasedChargeAmount(final BigDecimal recomputedTotal, final BigDecimal grossInterestForPeriod,
            final BigDecimal withholdingTaxForPeriod) {
        final BigDecimal available = grossInterestForPeriod.subtract(withholdingTaxForPeriod).max(BigDecimal.ZERO);
        return recomputedTotal.min(available).max(BigDecimal.ZERO);
    }

    /**
     * Implementation plan Section 10 steps 2, 4, 7 and 8, for one core posting-period boundary:
     *
     * <ol>
     * <li>take the pending {@code m_deposit_account_interest_charge} rows whose interest period ended on or before this
     * boundary and RECOMPUTE each one's amount from its stored {@code charge_percentage} against this period's real
     * gross interest - the amount those rows were written with at withdrawal time is provisional and is deliberately
     * ignored (see Task 7), which is what lets a withdrawal taken before any interest calculation still charge
     * correctly;</li>
     * <li>write ONE interest-based charge transaction for the period - and, per step 7, none at all when there is
     * nothing pending, so posting behaves exactly as it did before Phase 4;</li>
     * <li>link every consumed row to both the interest posting transaction and that charge transaction;</li>
     * <li>refresh both derived read columns from the table rather than incrementing them, so they cannot drift.</li>
     * </ol>
     *
     * The transaction is a {@code PAY_CHARGE} built by {@code SavingsAccountTransaction.charge(...)} and linked to the
     * account charge through {@code SavingsAccountChargePaidBy}, exactly as core's own {@code handleChargeTransactions}
     * does - so accounting and the charge/transaction link behave like every other savings charge. The attributed
     * charge's own {@code amount_paid_derived}/{@code amount_outstanding_derived} are updated via
     * {@code SavingsAccountCharge.pay(...)} too, exactly as core's {@code SavingsAccount.payCharge(...)} does before
     * building that link - without it, {@code SavingsAccount.undoTransaction(Long)}'s symmetric
     * {@code chargeToUndo.undoPayment(...)} on this transaction being undone would decrement an amount that was never
     * incremented, driving the charge's paid/outstanding bookkeeping negative.
     */
    private void applyPendingInterestBasedCharges(final LocalDate interestPostingTransactionDate, final Money grossInterestForPeriod,
            final SavingsAccountTransaction interestPostingTransaction, final boolean backdatedTxnsAllowedTill) {

        final var interestChargeRepository = DynamicDepositServiceLocator.interestChargeRepository();
        final List<DepositAccountInterestCharge> pendingRows = interestChargeRepository.findPendingByAccountIdUpTo(getId(),
                interestPostingTransactionDate);
        if (pendingRows.isEmpty()) {
            return;
        }

        // Recompute every row's amount from its stored percentage against THIS period's real gross interest - the
        // same figure the interest posting transaction was just written with. The provisional charge_amount each row
        // was created with at withdrawal time is intentionally not read: it was a snapshot of whatever the account
        // summary happened to report then, which may have been stale or zero.
        final BigDecimal grossInterest = grossInterestForPeriod.getAmount();
        final List<BigDecimal> recomputedAmounts = new ArrayList<>(pendingRows.size());
        BigDecimal recomputedTotal = BigDecimal.ZERO;
        for (final DepositAccountInterestCharge row : pendingRows) {
            final BigDecimal recomputed = grossInterest.multiply(row.chargePercentage()).divide(ONE_HUNDRED, percentageMathContext())
                    .min(grossInterest).max(BigDecimal.ZERO);
            recomputedAmounts.add(recomputed);
            recomputedTotal = recomputedTotal.add(recomputed);
        }

        // Read the withholding tax this same posting just wrote: createWithHoldTransaction(...) returns only a
        // boolean, so the amount has to come back off the transaction itself via core's own helpers.
        final List<SavingsAccountTransaction> currentWithholdTransactions = backdatedTxnsAllowedTill
                ? findWithHoldSavingsTransactionsWithPivotConfig()
                : findWithHoldTransactions();
        final SavingsAccountTransaction withholdTransaction = findTransactionFor(interestPostingTransactionDate,
                currentWithholdTransactions);
        final BigDecimal withholdingTaxForPeriod = withholdTransaction == null ? BigDecimal.ZERO : withholdTransaction.getAmount();

        final BigDecimal chargeAmount = cappedInterestBasedChargeAmount(recomputedTotal, grossInterest, withholdingTaxForPeriod);
        if (chargeAmount.compareTo(BigDecimal.ZERO) <= 0) {
            return;
        }

        // All pending rows for an account reference the same charge - the product allows only one early-withdrawal
        // charge - so the single transaction is attributed to the oldest row's account charge.
        final SavingsAccountCharge attributedCharge = pendingRows.get(0).savingsAccountCharge();
        final SavingsAccountTransaction chargeTransaction = SavingsAccountTransaction.charge(this, office(), interestPostingTransactionDate,
                Money.of(this.currency, chargeAmount));
        // The transaction's own amount is the currency-rounded figure Money.of(...) just produced above - NOT the raw
        // chargeAmount this method computed it from. The rows below must be distributed against THAT rounded figure
        // (what actually posted, and therefore what SavingsAccountChargePaidBy/accounting move), or the rows would
        // sum to a value the ledger never saw whenever rounding changes the last digit.
        final BigDecimal appliedTotal = chargeTransaction.getAmount();
        // Mirrors SavingsAccount.payCharge(...): core calls SavingsAccountCharge.pay(...) before building the
        // SavingsAccountChargePaidBy link, so the charge's own paid/outstanding bookkeeping stays symmetric with
        // undoTransaction(Long)'s undoPayment(...) call on this same transaction.
        attributedCharge.pay(this.currency, Money.of(this.currency, appliedTotal));
        chargeTransaction.getSavingsAccountChargesPaid()
                .add(SavingsAccountChargePaidBy.instance(chargeTransaction, attributedCharge, appliedTotal));
        if (backdatedTxnsAllowedTill) {
            addTransactionToExisting(chargeTransaction);
        } else {
            addTransaction(chargeTransaction);
        }

        // Write back what was actually applied, pro-rated across the rows when the cap bit, so the last row absorbs
        // any rounding remainder and the rows sum to exactly the transaction amount. Without this the posted-charge
        // sum would keep reporting Task 7's provisional figures instead of the money that moved.
        //
        // Non-last rows are truncated DOWN (RoundingMode.DOWN), never rounded to the nearest per the tenant's default
        // mode: a default mode that rounds up can push the non-last rows' running sum above appliedTotal, which would
        // make the last row's appliedTotal.subtract(distributed) go negative - persisting a pending charge row with a
        // negative charge_amount even though the overall money movement and the row-sum invariant both stay correct.
        // Truncating down guarantees the non-last rows' running sum never exceeds the exact partial total they
        // approximate, so the last row's remainder is always in [0, appliedTotal].
        BigDecimal distributed = BigDecimal.ZERO;
        for (int i = 0; i < pendingRows.size(); i++) {
            final BigDecimal rowAmount;
            if (i == pendingRows.size() - 1) {
                rowAmount = appliedTotal.subtract(distributed);
            } else if (recomputedTotal.compareTo(BigDecimal.ZERO) == 0) {
                rowAmount = BigDecimal.ZERO;
            } else {
                rowAmount = recomputedAmounts.get(i).multiply(appliedTotal).divide(recomputedTotal, percentageMathContext())
                        .setScale(this.currency.getDigitsAfterDecimal(), RoundingMode.DOWN);
            }
            distributed = distributed.add(rowAmount);
            pendingRows.get(i).applyAtPosting(grossInterest, rowAmount, interestPostingTransaction, chargeTransaction);
        }
        interestChargeRepository.saveAll(pendingRows);
        interestChargeRepository.flush();

        updateInterestBasedChargeDerived(interestChargeRepository.sumPendingChargeAmount(getId()));
        updateInterestBasedChargePostedDerived(interestChargeRepository.sumPostedChargeAmount(getId()));
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
}
