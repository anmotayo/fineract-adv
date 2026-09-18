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

import com.advancly.fineract.portfolio.savings.domain.DepositProductDynamicDetail;
import com.advancly.fineract.portfolio.savings.domain.DepositProductDynamicDetailRepository;
import com.advancly.fineract.portfolio.savings.domain.SavingsAccountInterestCharge;
import com.advancly.fineract.portfolio.savings.domain.SavingsAccountInterestChargeRepository;
import com.advancly.fineract.portfolio.savings.domain.SavingsProductEarlyWithdrawalCharge;
import com.advancly.fineract.portfolio.savings.domain.SavingsProductEarlyWithdrawalChargeRepository;
import java.math.BigDecimal;
import java.math.MathContext;
import java.time.LocalDate;
import java.util.List;
import org.apache.fineract.organisation.monetary.domain.MoneyHelper;
import org.apache.fineract.portfolio.charge.domain.Charge;
import org.apache.fineract.portfolio.charge.domain.ChargeCalculationType;
import org.apache.fineract.portfolio.savings.domain.SavingsAccount;
import org.apache.fineract.portfolio.savings.domain.SavingsAccountCharge;
import org.apache.fineract.portfolio.savings.domain.SavingsAccountSummary;
import org.apache.fineract.portfolio.savings.domain.SavingsAccountTransaction;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Creates the pending {@code m_savings_account_interest_charge} row for an early withdrawal from a Dynamic Deposit
 * account (implementation plan Section 11).
 *
 * "Early withdrawal" is any withdrawal transaction dated strictly before the account's maturity date - which also
 * covers premature closure, since every close-with-withdrawal path produces exactly such a transaction. Nothing here
 * moves money: the withdrawal itself has already reduced the balance through the normal ledger, and the charge is not
 * applied until the next interest posting sums the pending rows (Section 10 steps 7-8). That deferral is what makes
 * "Preserve principal" achievable - the charge is only ever taken out of interest that has actually been posted.
 *
 * The durable output of this class is the row's {@code charge_percentage}: posting recomputes the amount from it
 * against the period's real gross interest, so the {@code charge_amount} written here is provisional and exists only
 * for audit and the {@code interest_based_charge_derived} fast-read column.
 *
 * Every disqualifying condition is a silent no-op rather than an exception: a withdrawal must never fail because the
 * product's penalty configuration is absent or incomplete.
 */
@Component
public class DynamicDepositEarlyWithdrawalChargeService {

    private static final BigDecimal ONE_HUNDRED = BigDecimal.valueOf(100L);

    // NOT a static constant: MoneyHelper.getRoundingMode() resolves the CURRENT tenant's configured rounding mode
    // from a thread-local, so capturing it in a static initialiser would either blow up at class-load time (no tenant
    // context yet) or freeze one tenant's setting for every other tenant. Core's own SavingsAccountCharge#percentageOf
    // builds its MathContext per call for the same reason.
    private static MathContext percentageMathContext() {
        return new MathContext(8, MoneyHelper.getRoundingMode());
    }

    private final SavingsAccountInterestChargeRepository interestChargeRepository;
    private final SavingsProductEarlyWithdrawalChargeRepository productEarlyWithdrawalChargeRepository;
    private final DepositProductDynamicDetailRepository productDynamicDetailRepository;

    public DynamicDepositEarlyWithdrawalChargeService(final SavingsAccountInterestChargeRepository interestChargeRepository,
            final SavingsProductEarlyWithdrawalChargeRepository productEarlyWithdrawalChargeRepository,
            final DepositProductDynamicDetailRepository productDynamicDetailRepository) {
        this.interestChargeRepository = interestChargeRepository;
        this.productEarlyWithdrawalChargeRepository = productEarlyWithdrawalChargeRepository;
        this.productDynamicDetailRepository = productDynamicDetailRepository;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void recordIfApplicable(final SavingsAccount account, final SavingsAccountTransaction withdrawalTransaction) {
        recordIfApplicable(account, withdrawalTransaction, false, null);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void recordIfApplicable(final SavingsAccount account, final SavingsAccountTransaction withdrawalTransaction,
            final boolean forceEarlyWithdrawal, final BigDecimal chargePercentageOverride) {
        if (withdrawalTransaction == null || withdrawalTransaction.isReversed()) {
            return;
        }
        // The settlement withdrawal issued by premature closure is the one withdrawal that must NOT create a pending
        // row here. Its penalty has already been computed - and charged - by DynamicDepositAccount's closure
        // settlement, folded into that period's single capped interest-based charge, and the account creates the
        // corresponding row itself (already applied, linked to this very transaction) as soon as this transaction
        // exists. A pending row from here would be a duplicate of a charge that was already taken, and, with no
        // further interest posting ever due on a closed account, one that could never be applied or cleared.
        //
        // Guarding inside this single method rather than at each call site keeps the rule in one place. Note that
        // only DynamicDepositAccount#withdraw actually reaches here: the optimized append path in
        // AdvanclySavingsAccountDomainService bypasses SavingsAccount#withdraw() entirely and so never records a
        // per-period row. CUMULATIVE-mode products need the same skip for a related but distinct reason, checked
        // separately just below: their entire lifetime interest is already forfeited synchronously at the
        // write-platform layer (see CumulativeInterestForfeitureService), so a pending per-period row here would
        // double the customer's penalty on an ordinary early withdrawal - and, on a premature closure, would be
        // exactly as orphaned as the duplicate this guard already exists to prevent, since a closed account never
        // posts interest again either.
        if (account.isClosureSettlementInProgress()) {
            return;
        }
        final LocalDate withdrawalDate = withdrawalTransaction.getTransactionDate();
        if (!forceEarlyWithdrawal && !account.isEarlyWithdrawal(withdrawalDate)) {
            return;
        }

        // Same lookup as AdvanclySavingsAccountWritePlatformService#isCumulativeMode: CUMULATIVE-mode's forfeiture
        // already ran for this very withdrawal at the write-platform layer, above this entity-level hook, taking the
        // account's whole lifetime interest. Checked before resolving a qualifying charge below so a CUMULATIVE
        // product never gets as far as that resolution just to have it discarded.
        final List<SavingsProductEarlyWithdrawalCharge> selections = this.productEarlyWithdrawalChargeRepository
                .findBySavingsProductId(account.productId());
        if (selections.size() == 1 && selections.get(0).mode().isCumulative()) {
            return;
        }

        final QualifyingCharge qualifying = resolveQualifyingChargeWithPercentage(account, chargePercentageOverride);
        if (qualifying == null) {
            return;
        }
        final SavingsAccountCharge qualifyingCharge = qualifying.accountCharge();
        final BigDecimal percentage = qualifying.percentage();

        // PROVISIONAL ONLY. Task 8 recomputes the amount from the stored percentage against the period's real,
        // just-calculated gross interest before applying anything, so this snapshot never determines what the customer
        // is charged - it exists for audit and for the interest_based_charge_derived fast-read column. Section 11's
        // "cap the charge so it cannot exceed the available interest basis" is applied here against the provisional
        // basis and again, authoritatively, at posting time against gross interest minus withholding tax.
        //
        // Deliberately NOT short-circuited on a zero basis: SavingsAccountSummary's totals are only refreshed by an
        // interest calculation or posting run, so a withdrawal taken before either has run since the last posting sees
        // zero here. Skipping the row in that case would discard the authoritative percentage and silently drop the
        // penalty.
        final BigDecimal interestBasis = currentPeriodInterestBasis(account);
        final BigDecimal provisionalChargeAmount = interestBasis.multiply(percentage).divide(ONE_HUNDRED, percentageMathContext())
                .min(interestBasis).max(BigDecimal.ZERO);

        this.interestChargeRepository.saveAndFlush(
                SavingsAccountInterestCharge.createNew(account, withdrawalTransaction, qualifyingCharge, qualifyingCharge.getCharge(),
                        currentPeriodStartDate(account), withdrawalDate, interestBasis, percentage, provisionalChargeAmount));

        // Section 11: "Update interest_based_charge_derived during interest calculation". Recomputed from the table
        // rather than incremented, so the derived value can never drift from its source of truth.
        account.updateInterestBasedChargeDerived(this.interestChargeRepository.sumPendingChargeAmount(account.getId()));
    }

    /**
     * The account charge an early withdrawal (or a premature closure) would be penalised with, together with the
     * percentage to apply - or {@code null} when the product/account configuration disqualifies it, or the percentage
     * is absent or not positive.
     *
     * Public because {@code DynamicDepositAccount}'s closure settlement resolves exactly the same charge and percentage
     * for the closure's own contribution to the period's interest-based charge; sharing this one method is what keeps
     * "which charge, at what percentage" a single answer rather than two that could drift.
     */
    public QualifyingCharge resolveQualifyingChargeWithPercentage(final SavingsAccount account) {
        return resolveQualifyingChargeWithPercentage(account, null);
    }

    public QualifyingCharge resolveQualifyingChargeWithPercentage(final SavingsAccount account, final BigDecimal chargePercentageOverride) {
        final SavingsAccountCharge qualifyingCharge = resolveQualifyingCharge(account);
        if (qualifyingCharge == null) {
            return null;
        }
        final BigDecimal percentage = resolvePercentage(account, qualifyingCharge, chargePercentageOverride);
        if (percentage == null || percentage.compareTo(BigDecimal.ZERO) <= 0) {
            return null;
        }
        return new QualifyingCharge(qualifyingCharge, percentage);
    }

    /** The resolved early-withdrawal penalty: the account charge it is taken against and the percentage to apply. */
    public record QualifyingCharge(SavingsAccountCharge accountCharge, BigDecimal percentage) {
    }

    /**
     * Section 11's qualification list, in order: the product has {@code early_withdrawal_penalty_enabled}; exactly one
     * charge is selected in {@code m_savings_product_early_withdrawal_charge}; the account carries that charge (which
     * {@code DynamicDepositAccountAssembler} guarantees for accounts created after Phase 4, and which the caller may
     * also have supplied as an override); the account charge is active; it or its definition is a penalty; and the
     * calculation type is {@code PERCENT_OF_INTEREST}.
     */
    private SavingsAccountCharge resolveQualifyingCharge(final SavingsAccount account) {
        final DepositProductDynamicDetail productDetail = this.productDynamicDetailRepository.findByProductId(account.productId())
                .orElse(null);
        if (productDetail == null || !productDetail.isEarlyWithdrawalPenaltyEnabled()) {
            return null;
        }

        final List<SavingsProductEarlyWithdrawalCharge> selections = this.productEarlyWithdrawalChargeRepository
                .findBySavingsProductId(account.productId());
        if (selections.size() != 1) {
            return null;
        }
        final Long selectedChargeId = selections.get(0).chargeId();

        for (final SavingsAccountCharge accountCharge : account.charges()) {
            final Charge definition = accountCharge.getCharge();
            if (definition == null || !selectedChargeId.equals(definition.getId())) {
                continue;
            }
            if (!accountCharge.isActive()) {
                continue;
            }
            if (!accountCharge.isPenaltyCharge() && !definition.isPenalty()) {
                continue;
            }
            if (definition.getChargeCalculation() == null
                    || !ChargeCalculationType.fromInt(definition.getChargeCalculation()).isPercentageOfInterest()) {
                continue;
            }
            return accountCharge;
        }
        return null;
    }

    /**
     * Resolution order: (1) a per-transaction override set via
     * {@link DynamicDepositAccount#setEarlyWithdrawalChargePercentageOverride(BigDecimal)} for THIS withdrawal only;
     * (2) Section 11's "use the account charge percentage when the client/account has an override; otherwise use the
     * product charge percentage" - a {@code SavingsAccountCharge} created from the product definition stores the
     * definition's own amount as its percentage, so the fallback only fires for a charge that genuinely carries none.
     */
    private BigDecimal resolvePercentage(final SavingsAccount account, final SavingsAccountCharge accountCharge,
            final BigDecimal chargePercentageOverride) {
        final BigDecimal transactionOverride = chargePercentageOverride != null ? chargePercentageOverride
                : account.earlyWithdrawalChargePercentageOverride();
        if (transactionOverride != null) {
            return transactionOverride;
        }
        final BigDecimal accountPercentage = accountCharge.getPercentage();
        if (accountPercentage != null && accountPercentage.compareTo(BigDecimal.ZERO) > 0) {
            return accountPercentage;
        }
        return accountCharge.getCharge().getAmount();
    }

    /**
     * The PROVISIONAL interest basis: interest accrued but not yet posted, i.e. the open interest period's interest.
     * Never principal. Already-posted interest is excluded because it belongs to the depositor and may already have
     * been withdrawn.
     *
     * These totals are only refreshed by an interest calculation or posting run, so a withdrawal taken before either
     * has run since the last posting sees a stale - possibly zero - value. That is by design and harmless: the row's
     * {@code charge_percentage} is the authoritative input, and the amount actually applied is recomputed from it at
     * posting time against the period's real gross interest. Forcing a recalculation here is deliberately avoided - the
     * optimized append path assembles accounts without the helpers {@code calculateInterestUsing} needs (see
     * {@code DynamicDepositPostInterestTasklet}'s javadoc).
     */
    private BigDecimal currentPeriodInterestBasis(final SavingsAccount account) {
        final SavingsAccountSummary summary = account.getSummary();
        if (summary == null) {
            return BigDecimal.ZERO;
        }
        final BigDecimal earned = summary.getTotalInterestEarned() == null ? BigDecimal.ZERO : summary.getTotalInterestEarned();
        final BigDecimal posted = summary.getTotalInterestPosted() == null ? BigDecimal.ZERO : summary.getTotalInterestPosted();
        final BigDecimal accrued = earned.subtract(posted);
        return accrued.compareTo(BigDecimal.ZERO) > 0 ? accrued : BigDecimal.ZERO;
    }

    /**
     * Start of the open interest period: the day after the latest non-reversed interest posting, or the account's
     * interest-calculation start date when nothing has been posted yet. Stored on the row for audit and for matching
     * pending rows to a posting boundary.
     *
     * Public for the same reason as {@link #resolveQualifyingChargeWithPercentage(SavingsAccount)}: the closure
     * settlement stamps its own row with the same period start an ordinary early withdrawal would have.
     */
    public LocalDate currentPeriodStartDate(final SavingsAccount account) {
        LocalDate lastPostingDate = null;
        for (final SavingsAccountTransaction transaction : account.getTransactions()) {
            if (!transaction.isInterestPostingAndNotReversed()) {
                continue;
            }
            final LocalDate transactionDate = transaction.getTransactionDate();
            if (lastPostingDate == null || transactionDate.isAfter(lastPostingDate)) {
                lastPostingDate = transactionDate;
            }
        }
        if (lastPostingDate != null) {
            return lastPostingDate.plusDays(1);
        }
        final LocalDate startInterestCalculationDate = account.getStartInterestCalculationDate();
        return startInterestCalculationDate != null ? startInterestCalculationDate : account.accountSubmittedOrActivationDate();
    }
}
