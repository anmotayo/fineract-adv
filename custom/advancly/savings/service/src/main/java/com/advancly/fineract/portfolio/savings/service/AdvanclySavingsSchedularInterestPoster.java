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

import static org.apache.fineract.infrastructure.core.domain.AuditableFieldsConstants.CREATED_BY_DB_FIELD;
import static org.apache.fineract.infrastructure.core.domain.AuditableFieldsConstants.CREATED_DATE_DB_FIELD;
import static org.apache.fineract.infrastructure.core.domain.AuditableFieldsConstants.LAST_MODIFIED_BY_DB_FIELD;
import static org.apache.fineract.infrastructure.core.domain.AuditableFieldsConstants.LAST_MODIFIED_DATE_DB_FIELD;

import com.advancly.fineract.portfolio.savings.domain.InterestBasedChargeMath;
import com.advancly.fineract.portfolio.savings.domain.SavingsAccountInterestCharge;
import com.advancly.fineract.portfolio.savings.domain.SavingsAccountInterestChargeRepository;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.apache.fineract.accounting.journalentry.domain.JournalEntryType;
import org.apache.fineract.infrastructure.core.service.DateUtils;
import org.apache.fineract.infrastructure.core.service.MathUtil;
import org.apache.fineract.infrastructure.jobs.exception.JobExecutionException;
import org.apache.fineract.infrastructure.security.service.PlatformSecurityContext;
import org.apache.fineract.organisation.monetary.domain.MonetaryCurrency;
import org.apache.fineract.organisation.monetary.domain.Money;
import org.apache.fineract.portfolio.savings.SavingsAccountTransactionType;
import org.apache.fineract.portfolio.savings.data.SavingsAccountData;
import org.apache.fineract.portfolio.savings.data.SavingsAccountSummaryData;
import org.apache.fineract.portfolio.savings.data.SavingsAccountTransactionData;
import org.apache.fineract.portfolio.savings.data.SavingsAccountTransactionEnumData;
import org.apache.fineract.portfolio.savings.domain.SavingsAccountTransaction;
import org.apache.fineract.portfolio.savings.domain.SavingsAccountTransactionRepository;
import org.apache.fineract.portfolio.savings.service.SavingsAccountReadPlatformService;
import org.apache.fineract.portfolio.savings.service.SavingsAccountWritePlatformService;
import org.apache.fineract.portfolio.savings.service.SavingsEnumerations;
import org.apache.fineract.portfolio.savings.service.SavingsSchedularInterestPoster;
import org.apache.fineract.portfolio.tax.data.TaxComponentData;
import org.apache.fineract.portfolio.tax.data.TaxGroupData;
import org.apache.fineract.portfolio.tax.data.TaxGroupMappingsData;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Task 8: applies pending per-period early-withdrawal charges (recorded by Task 5) against the interest just posted for
 * a plain-Savings account, in the same batch run that {@code SavingsSchedularInterestPoster} already performs.
 *
 * <p>
 * Two access-modifier constraints on the superclass shape this whole class:
 *
 * <ol>
 * <li>{@code SavingsSchedularInterestPoster} is {@code @Setter}-annotated (Lombok, class-level) - it generates public
 * *setters* for {@code savingAccounts}/{@code backdatedTxnsAllowedTill}, but no getters. This subclass can call
 * {@code super.setSavingAccounts(...)} but cannot read {@code super}'s copy back, so it keeps its own shadow copy
 * ({@link #savingAccountsShadow}/{@link #backdatedTxnsAllowedTillShadow}), populated by overriding both setters to
 * write to both the superclass (for any code that only knows about the supertype) and this subclass's own field.</li>
 * <li>{@code batchUpdate(...)}, {@code batchUpdateJournalEntries(...)} and their SQL-builder helpers are
 * {@code private} on the superclass, not {@code protected} - so {@code postInterest()} cannot be composed as "our new
 * logic, then {@code super.postInterest()}" (that would run the DTO interest engine twice per account, double-posting
 * interest), nor as "call some batch-write-only half of {@code super}" (no such method exists). The only viable
 * approach is to copy those private methods into this subclass verbatim and call this class's own copies - the same
 * "vendor and extend" tradeoff {@code AdvanclySavingsAccountWritePlatformService} already takes for
 * {@code deposit}/{@code withdrawal} relative to core. Those copied methods reference
 * {@code this.jdbcTemplate}/{@code this.platformSecurityContext}/{@code this.savingsAccountReadPlatformService}/
 * {@code this.savingsAccountWritePlatformService}, all of which are likewise {@code private} on the superclass and so
 * are shadowed here too, under the exact same names so the copied method bodies resolve them unchanged.</li>
 * </ol>
 */
@Slf4j
public class AdvanclySavingsSchedularInterestPoster extends SavingsSchedularInterestPoster {

    private static final String SAVINGS_TRANSACTION_IDENTIFIER = "S";

    // Shadow fields for the superclass's private constructor-injected dependencies (see class javadoc point 2). Named
    // identically to the superclass's own private fields so the nine methods copied verbatim below - which reference
    // them unqualified or via `this.` - resolve to these without any changes to their bodies.
    private final SavingsAccountWritePlatformService savingsAccountWritePlatformService;
    private final JdbcTemplate jdbcTemplate;
    private final SavingsAccountReadPlatformService savingsAccountReadPlatformService;
    private final PlatformSecurityContext platformSecurityContext;

    private final SavingsAccountInterestChargeRepository interestChargeRepository;
    private final SavingsAccountTransactionRepository savingsAccountTransactionRepository;

    // Shadow fields for the superclass's private, setter-only state (see class javadoc point 1).
    private Collection<SavingsAccountData> savingAccountsShadow = new ArrayList<>();
    private boolean backdatedTxnsAllowedTillShadow;

    public AdvanclySavingsSchedularInterestPoster(final SavingsAccountWritePlatformService savingsAccountWritePlatformService,
            final JdbcTemplate jdbcTemplate, final SavingsAccountReadPlatformService savingsAccountReadPlatformService,
            final PlatformSecurityContext platformSecurityContext, final SavingsAccountInterestChargeRepository interestChargeRepository,
            final SavingsAccountTransactionRepository savingsAccountTransactionRepository) {
        super(savingsAccountWritePlatformService, jdbcTemplate, savingsAccountReadPlatformService, platformSecurityContext);
        this.savingsAccountWritePlatformService = savingsAccountWritePlatformService;
        this.jdbcTemplate = jdbcTemplate;
        this.savingsAccountReadPlatformService = savingsAccountReadPlatformService;
        this.platformSecurityContext = platformSecurityContext;
        this.interestChargeRepository = interestChargeRepository;
        this.savingsAccountTransactionRepository = savingsAccountTransactionRepository;
    }

    @Override
    public void setSavingAccounts(final Collection<SavingsAccountData> savingAccounts) {
        super.setSavingAccounts(savingAccounts);
        this.savingAccountsShadow = savingAccounts;
    }

    @Override
    public void setBackdatedTxnsAllowedTill(final boolean backdatedTxnsAllowedTill) {
        super.setBackdatedTxnsAllowedTill(backdatedTxnsAllowedTill);
        this.backdatedTxnsAllowedTillShadow = backdatedTxnsAllowedTill;
    }

    @Override
    @Transactional(isolation = Isolation.READ_UNCOMMITTED, rollbackFor = Exception.class)
    public void postInterest() throws JobExecutionException {
        if (this.savingAccountsShadow.isEmpty()) {
            return;
        }
        final List<Throwable> errors = new ArrayList<>();
        final List<SavingsAccountData> postedAccounts = new ArrayList<>();
        final List<PendingChargeApplication> pendingApplications = new ArrayList<>();
        for (final SavingsAccountData savingsAccountData : this.savingAccountsShadow) {
            try {
                final SavingsAccountData postedAccountData = this.savingsAccountWritePlatformService.postInterest(savingsAccountData, false,
                        null, this.backdatedTxnsAllowedTillShadow);
                final PendingChargeApplication application = applyPendingChargeIfAny(postedAccountData);
                if (application != null) {
                    pendingApplications.add(application);
                }
                postedAccounts.add(postedAccountData);
            } catch (final Exception e) {
                errors.add(e);
            }
        }
        if (errors.isEmpty()) {
            try {
                batchUpdate(postedAccounts); // this class's own copy (see class javadoc), not the superclass's private
                                             // one
                linkAppliedCharges(pendingApplications);
                updateDerivedChargeColumns(pendingApplications);
            } catch (final Exception e) {
                errors.add(e);
            }
        }
        if (!errors.isEmpty()) {
            throw new JobExecutionException(errors);
        }
    }

    /**
     * Carries what {@link #linkAppliedCharges} needs once {@code batchUpdate} has assigned real database ids to the
     * DTOs referenced here. {@code batchUpdate} (copied from core in this same class) mutates the exact same
     * {@code SavingsAccountTransactionData} objects in place via its own {@code setId(...)} call - so
     * {@code postingTransaction}/{@code chargeTransaction} already carry their real ids the moment {@code batchUpdate}
     * returns; no second query is needed to learn them.
     */
    private record PendingChargeApplication(SavingsAccountTransactionData postingTransaction,
            SavingsAccountTransactionData chargeTransaction, List<SavingsAccountInterestCharge> pendingRows, List<BigDecimal> rowAmounts) {
    }

    private PendingChargeApplication applyPendingChargeIfAny(final SavingsAccountData savingsAccountData) {
        final LocalDate today = DateUtils.getBusinessLocalDate();
        final List<SavingsAccountInterestCharge> pendingRows = this.interestChargeRepository
                .findPendingByAccountIdUpTo(savingsAccountData.getId(), today);
        if (pendingRows.isEmpty()) {
            return null;
        }

        final SavingsAccountTransactionData interestPostingTransaction = findLatestInterestPostingTransaction(savingsAccountData);
        if (interestPostingTransaction == null) {
            return null;
        }
        final BigDecimal grossInterest = interestPostingTransaction.getAmount();

        BigDecimal recomputedTotal = BigDecimal.ZERO;
        final List<BigDecimal> recomputedAmounts = new ArrayList<>(pendingRows.size());
        for (final SavingsAccountInterestCharge row : pendingRows) {
            final BigDecimal recomputed = InterestBasedChargeMath.recomputedChargeAmount(grossInterest, row.chargePercentage());
            recomputedAmounts.add(recomputed);
            recomputedTotal = recomputedTotal.add(recomputed);
        }

        final BigDecimal withholdingTax = findWithholdingTaxOnSameDate(savingsAccountData, interestPostingTransaction.getDate());
        final BigDecimal chargeAmount = InterestBasedChargeMath.cappedInterestBasedChargeAmount(recomputedTotal, grossInterest,
                withholdingTax);
        if (chargeAmount.compareTo(BigDecimal.ZERO) <= 0) {
            return null;
        }

        final SavingsAccountTransactionEnumData transactionType = SavingsEnumerations
                .transactionType(SavingsAccountTransactionType.INTEREST_BASED_CHARGE.getValue());
        // NOTE: uses the create(...) overload that also sets the transient `transactionDate` field (distinct from
        // the final `date` field) - every other transaction on the account's list (interest posting, accrual,
        // withhold tax, ...) is built through the `createImport(...)` family, which always populates it, and
        // `updateCumulativeBalanceAndDates(...)` below unconditionally reads it via `getTransactionDate()`
        // (`LocalDateInterval.create(getTransactionDate(), endOfBalanceDate).daysInPeriodInclusiveOfEndDate()` throws
        // `IllegalArgumentException: Dates must not be null to get difference` otherwise). The 22-param overload the
        // original plan sketch used does not go through `createData(...)` and leaves `transactionDate` null, which
        // fails immediately on the very next line - confirmed against a real fixture while iterating on this task's
        // test.
        final SavingsAccountTransactionData chargeTransaction = SavingsAccountTransactionData.create(null, transactionType, null,
                savingsAccountData.getId(), savingsAccountData.getAccountNo(), interestPostingTransaction.getDate(),
                savingsAccountData.getCurrency(), chargeAmount, null, null, false, interestPostingTransaction.getSubmittedOnDate(), false,
                null, null, OffsetDateTime.now());
        chargeTransaction.updateRunningBalance(
                Money.of(savingsAccountData.getCurrency(), interestPostingTransaction.getRunningBalance()).minus(chargeAmount));
        chargeTransaction.updateCumulativeBalanceAndDates(MonetaryCurrency.fromCurrencyData(savingsAccountData.getCurrency()),
                interestPostingTransaction.getEndOfBalanceLocalDate());

        savingsAccountData.getSavingsAccountTransactionData().add(chargeTransaction);
        if (this.backdatedTxnsAllowedTillShadow) {
            savingsAccountData.getSummary().updateSummaryWithPivotConfig(savingsAccountData.getCurrency(), null, null,
                    savingsAccountData.getSavingsAccountTransactionData());
        } else {
            savingsAccountData.getSummary().updateSummary(savingsAccountData.getCurrency(), null,
                    savingsAccountData.getSavingsAccountTransactionData());
        }

        final List<BigDecimal> rowAmounts = new ArrayList<>(pendingRows.size());
        BigDecimal distributed = BigDecimal.ZERO;
        for (int i = 0; i < pendingRows.size(); i++) {
            final BigDecimal rowAmount;
            if (i == pendingRows.size() - 1) {
                rowAmount = chargeAmount.subtract(distributed);
            } else if (recomputedTotal.compareTo(BigDecimal.ZERO) == 0) {
                rowAmount = BigDecimal.ZERO;
            } else {
                rowAmount = recomputedAmounts.get(i).multiply(chargeAmount).divide(recomputedTotal, MathContext.DECIMAL64)
                        .setScale(savingsAccountData.getCurrency().getDecimalPlaces(), RoundingMode.DOWN);
            }
            distributed = distributed.add(rowAmount);
            rowAmounts.add(rowAmount);
        }
        return new PendingChargeApplication(interestPostingTransaction, chargeTransaction, pendingRows, rowAmounts);
    }

    /**
     * Runs AFTER {@code batchUpdate(postedAccounts)} has inserted every new transaction - so
     * {@code application.postingTransaction().getId()}/{@code .chargeTransaction().getId()} are real ids now.
     * {@code getReferenceById(...)} returns a lazy JPA proxy (no SELECT) - sufficient here because
     * {@code applyAtPosting(...)} only writes a foreign key onto each row, it never reads a field off either
     * transaction.
     */
    private void linkAppliedCharges(final List<PendingChargeApplication> pendingApplications) {
        for (final PendingChargeApplication application : pendingApplications) {
            final SavingsAccountTransaction postingTransaction = this.savingsAccountTransactionRepository
                    .getReferenceById(application.postingTransaction().getId());
            final SavingsAccountTransaction chargeTransaction = this.savingsAccountTransactionRepository
                    .getReferenceById(application.chargeTransaction().getId());
            final List<SavingsAccountInterestCharge> pendingRows = application.pendingRows();
            final List<BigDecimal> rowAmounts = application.rowAmounts();
            for (int i = 0; i < pendingRows.size(); i++) {
                pendingRows.get(i).applyAtPosting(application.postingTransaction().getAmount(), rowAmounts.get(i), postingTransaction,
                        chargeTransaction);
            }
            this.interestChargeRepository.saveAll(pendingRows);
        }
    }

    /**
     * Implementation plan Section 5 / Section 3.1 step 7 / this plan's own Final Self-Review Notes: the derived columns
     * on {@code m_savings_account} are read-side conveniences only (the source of truth is
     * {@code m_savings_account_interest_charge} plus the linked transactions, updated above by
     * {@link #linkAppliedCharges}) but still need refreshing so they reflect what was just applied.
     */
    private void updateDerivedChargeColumns(final List<PendingChargeApplication> pendingApplications) {
        for (final PendingChargeApplication application : pendingApplications) {
            final Long accountId = application.postingTransaction().getAccountId();
            final BigDecimal pending = this.interestChargeRepository.sumPendingChargeAmount(accountId);
            final BigDecimal posted = this.interestChargeRepository.sumPostedChargeAmount(accountId);
            this.jdbcTemplate.update(
                    "update m_savings_account set interest_based_charge_derived = ?, interest_based_charge_posted_derived = ? where id = ?",
                    pending, posted, accountId);
        }
    }

    private static SavingsAccountTransactionData findLatestInterestPostingTransaction(final SavingsAccountData savingsAccountData) {
        SavingsAccountTransactionData latest = null;
        for (final SavingsAccountTransactionData transaction : savingsAccountData.getSavingsAccountTransactionData()) {
            if (!transaction.isInterestPostingAndNotReversed()) {
                continue;
            }
            if (latest == null || transaction.getDate().isAfter(latest.getDate())) {
                latest = transaction;
            }
        }
        return latest;
    }

    private static BigDecimal findWithholdingTaxOnSameDate(final SavingsAccountData savingsAccountData, final LocalDate date) {
        for (final SavingsAccountTransactionData transaction : savingsAccountData.getSavingsAccountTransactionData()) {
            if (transaction.isWithHoldTaxAndNotReversed() && transaction.getDate().isEqual(date)) {
                return transaction.getAmount();
            }
        }
        return BigDecimal.ZERO;
    }

    // ---------------------------------------------------------------------------------------------------------
    // The nine methods below are copied verbatim from core's SavingsSchedularInterestPoster (fineract-savings),
    // which declares them all `private` with no getters for the fields they close over - see this class's javadoc.
    // Do not "clean up" or restructure them independently of core; any behavioral fix belongs in core first and then
    // ported here, to keep this vendored copy from silently drifting out of parity.
    // ---------------------------------------------------------------------------------------------------------

    private void batchUpdateJournalEntries(final List<SavingsAccountData> savingsAccountDataList,
            final HashMap<String, SavingsAccountTransactionData> savingsAccountTransactionDataHashMap)
            throws DataAccessException, NullPointerException {
        Long userId = platformSecurityContext.authenticatedUser().getId();
        String queryForJGLUpdate = batchQueryForJournalEntries();
        List<Object[]> paramsForGLInsertion = new ArrayList<>();
        for (SavingsAccountData savingsAccountData : savingsAccountDataList) {
            String currencyCode = savingsAccountData.getCurrency().getCode();
            final Set<Long> existingReversedTransactionIds = savingsAccountData.getExistingReversedTransactionIds();
            List<SavingsAccountTransactionData> savingsAccountTransactionDataList = savingsAccountData.getSavingsAccountTransactionData();
            for (SavingsAccountTransactionData savingsAccountTransactionData : savingsAccountTransactionDataList) {
                if (savingsAccountTransactionData.getId() == null && !MathUtil.isZero(savingsAccountTransactionData.getAmount())) {
                    final String key = savingsAccountTransactionData.getRefNo();
                    if (savingsAccountTransactionDataHashMap.containsKey(key)) {
                        final SavingsAccountTransactionData dataFromFetch = savingsAccountTransactionDataHashMap.get(key);
                        savingsAccountTransactionData.setId(dataFromFetch.getId());
                        if (savingsAccountData.getGlAccountIdForSavingsControl() != 0
                                && savingsAccountData.getGlAccountIdForInterestOnSavings() != 0) {
                            if (savingsAccountTransactionData.isWithHoldTax()) {
                                createJournalEntriesForWithHoldingTax(savingsAccountData, savingsAccountTransactionData,
                                        paramsForGLInsertion, currencyCode, userId);
                            } else {
                                createJournalEntries(savingsAccountData, savingsAccountTransactionData, paramsForGLInsertion, currencyCode,
                                        userId);
                            }
                        }
                    }
                } else if (savingsAccountTransactionData.isReversed()
                        && !existingReversedTransactionIds.contains(savingsAccountTransactionData.getAccountId())) {
                    if (!savingsAccountTransactionData.isWithHoldTax()) {
                        createJournalEntries(savingsAccountData, savingsAccountTransactionData, paramsForGLInsertion, currencyCode, userId);
                    } else {
                        createJournalEntriesForWithHoldingTax(savingsAccountData, savingsAccountTransactionData, paramsForGLInsertion,
                                currencyCode, userId);
                    }
                }
            }
        }

        if (!paramsForGLInsertion.isEmpty()) {
            this.jdbcTemplate.batchUpdate(queryForJGLUpdate, paramsForGLInsertion);
        }
    }

    private static void createJournalEntriesForWithHoldingTax(SavingsAccountData savingsAccountData,
            SavingsAccountTransactionData savingsAccountTransactionData, List<Object[]> paramsForGLInsertion, String currencyCode,
            Long userId) {
        final TaxGroupData taxGroup = savingsAccountData.getTaxGroup();
        if (taxGroup != null && taxGroup.getTaxAssociations() != null && !taxGroup.getTaxAssociations().isEmpty()) {
            boolean addedEntry = false;
            for (TaxGroupMappingsData taxGroupMappingsData : taxGroup.getTaxAssociations()) {
                final TaxComponentData taxComponentData = taxGroupMappingsData.getTaxComponent();
                if (taxComponentData != null) {
                    if (taxComponentData.getCreditAccount() != null) {
                        if (!savingsAccountTransactionData.isReversed()) {
                            createCreditJournalEntriesForWithHoldingTax(savingsAccountData, savingsAccountTransactionData,
                                    paramsForGLInsertion, currencyCode, userId, taxComponentData.getCreditAccount().getId());
                        } else {
                            createDebitJournalEntriesForWithHoldingTax(savingsAccountData, savingsAccountTransactionData,
                                    paramsForGLInsertion, currencyCode, userId, taxComponentData.getCreditAccount().getId());
                        }
                        addedEntry = true;
                    }

                    Long glAccountToDebit = savingsAccountData.getGlAccountIdForSavingsControl();
                    if (taxComponentData.getDebitAccount().getId() != 0) {
                        glAccountToDebit = taxComponentData.getDebitAccount().getId();
                    }
                    if (addedEntry) {
                        if (!savingsAccountTransactionData.isReversed()) {
                            createDebitJournalEntriesForWithHoldingTax(savingsAccountData, savingsAccountTransactionData,
                                    paramsForGLInsertion, currencyCode, userId, glAccountToDebit);
                        } else {
                            createCreditJournalEntriesForWithHoldingTax(savingsAccountData, savingsAccountTransactionData,
                                    paramsForGLInsertion, currencyCode, userId, glAccountToDebit);
                        }
                    }

                    addedEntry = false;
                }
            }

        }
    }

    private void createJournalEntries(SavingsAccountData savingsAccountData, SavingsAccountTransactionData savingsAccountTransactionData,
            List<Object[]> paramsForGLInsertion, String currencyCode, Long userId) {
        OffsetDateTime auditDatetime = DateUtils.getAuditOffsetDateTime();
        savingsAccountWritePlatformService.selectAccountId(savingsAccountTransactionData, savingsAccountData);
        long glAccountToDebit = savingsAccountTransactionData.getAccountDebit();
        long glAccountToCredit = savingsAccountTransactionData.getAccountCredit();
        if (savingsAccountTransactionData.isReversed()) {
            glAccountToDebit = savingsAccountTransactionData.getAccountCredit();
            glAccountToCredit = savingsAccountTransactionData.getAccountDebit();
        }

        paramsForGLInsertion.add(new Object[] { glAccountToCredit, savingsAccountData.getOfficeId(), null, currencyCode,
                SAVINGS_TRANSACTION_IDENTIFIER + savingsAccountTransactionData.getId().toString(), savingsAccountTransactionData.getId(),
                null, false, null, false, savingsAccountTransactionData.getTransactionDate(),
                JournalEntryType.CREDIT.getValue().longValue(), savingsAccountTransactionData.getAmount(), null,
                JournalEntryType.CREDIT.getValue().longValue(), savingsAccountData.getId(), auditDatetime, auditDatetime, false,
                BigDecimal.ZERO, BigDecimal.ZERO, null, savingsAccountTransactionData.getTransactionDate(), null, userId, userId,
                DateUtils.getBusinessLocalDate() });

        paramsForGLInsertion.add(new Object[] { glAccountToDebit, savingsAccountData.getOfficeId(), null, currencyCode,
                SAVINGS_TRANSACTION_IDENTIFIER + savingsAccountTransactionData.getId().toString(), savingsAccountTransactionData.getId(),
                null, false, null, false, savingsAccountTransactionData.getTransactionDate(), JournalEntryType.DEBIT.getValue().longValue(),
                savingsAccountTransactionData.getAmount(), null, JournalEntryType.DEBIT.getValue().longValue(), savingsAccountData.getId(),
                auditDatetime, auditDatetime, false, BigDecimal.ZERO, BigDecimal.ZERO, null,
                savingsAccountTransactionData.getTransactionDate(), null, userId, userId, DateUtils.getBusinessLocalDate() });
    }

    private static void createCreditJournalEntriesForWithHoldingTax(SavingsAccountData savingsAccountData,
            SavingsAccountTransactionData savingsAccountTransactionData, List<Object[]> paramsForGLInsertion, String currencyCode,
            Long userId, long glCreditAccountId) {
        OffsetDateTime auditDatetime = DateUtils.getAuditOffsetDateTime();
        paramsForGLInsertion.add(new Object[] { glCreditAccountId, savingsAccountData.getOfficeId(), null, currencyCode,
                SAVINGS_TRANSACTION_IDENTIFIER + savingsAccountTransactionData.getId().toString(), savingsAccountTransactionData.getId(),
                null, false, null, false, savingsAccountTransactionData.getTransactionDate(),
                JournalEntryType.CREDIT.getValue().longValue(), savingsAccountTransactionData.getAmount(), null,
                JournalEntryType.CREDIT.getValue().longValue(), savingsAccountData.getId(), auditDatetime, auditDatetime, false,
                BigDecimal.ZERO, BigDecimal.ZERO, null, savingsAccountTransactionData.getTransactionDate(), null, userId, userId,
                DateUtils.getBusinessLocalDate() });
    }

    private static void createDebitJournalEntriesForWithHoldingTax(SavingsAccountData savingsAccountData,
            SavingsAccountTransactionData savingsAccountTransactionData, List<Object[]> paramsForGLInsertion, String currencyCode,
            Long userId, long glDebitAccountId) {
        OffsetDateTime auditDatetime = DateUtils.getAuditOffsetDateTime();
        paramsForGLInsertion.add(new Object[] { glDebitAccountId, savingsAccountData.getOfficeId(), null, currencyCode,
                SAVINGS_TRANSACTION_IDENTIFIER + savingsAccountTransactionData.getId().toString(), savingsAccountTransactionData.getId(),
                null, false, null, false, savingsAccountTransactionData.getTransactionDate(), JournalEntryType.DEBIT.getValue().longValue(),
                savingsAccountTransactionData.getAmount(), null, JournalEntryType.DEBIT.getValue().longValue(), savingsAccountData.getId(),
                auditDatetime, auditDatetime, false, BigDecimal.ZERO, BigDecimal.ZERO, null,
                savingsAccountTransactionData.getTransactionDate(), null, userId, userId, DateUtils.getBusinessLocalDate() });
    }

    private String batchQueryForJournalEntries() {
        return "INSERT INTO acc_gl_journal_entry(account_id,office_id,reversal_id,currency_code,transaction_id,"
                + "savings_transaction_id,client_transaction_id,reversed,ref_num,manual_entry,entry_date,type_enum,"
                + "amount,description,entity_type_enum,entity_id,created_on_utc,"
                + "last_modified_on_utc,is_running_balance_calculated,office_running_balance,organization_running_balance,"
                + "payment_details_id,transaction_date,share_transaction_id, created_by, last_modified_by, submitted_on_date) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
    }

    private List<SavingsAccountTransactionData> fetchTransactionsFromIds(final List<String> refNo) throws DataAccessException {
        return this.savingsAccountReadPlatformService.retrieveAllTransactionData(refNo);
    }

    @SuppressWarnings("unused")
    private void batchUpdate(final List<SavingsAccountData> savingsAccountDataList) throws DataAccessException {
        String queryForSavingsUpdate = batchQueryForSavingsSummaryUpdate();
        String queryForTransactionInsertion = batchQueryForTransactionInsertion();
        String queryForTransactionUpdate = batchQueryForTransactionsUpdate();
        List<Object[]> paramsForTransactionInsertion = new ArrayList<>();
        List<Object[]> paramsForSavingsSummary = new ArrayList<>();
        List<Object[]> paramsForTransactionUpdate = new ArrayList<>();
        List<String> transRefNo = new ArrayList<>();
        LocalDate currentDate = DateUtils.getBusinessLocalDate();
        Long userId = platformSecurityContext.authenticatedUser().getId();
        for (SavingsAccountData savingsAccountData : savingsAccountDataList) {
            OffsetDateTime auditTime = DateUtils.getAuditOffsetDateTime();
            SavingsAccountSummaryData savingsAccountSummaryData = savingsAccountData.getSummary();
            paramsForSavingsSummary.add(new Object[] { savingsAccountSummaryData.getTotalDeposits(),
                    savingsAccountSummaryData.getTotalWithdrawals(), savingsAccountSummaryData.getTotalInterestEarned(),
                    savingsAccountSummaryData.getTotalInterestPosted(), savingsAccountSummaryData.getTotalWithdrawalFees(),
                    savingsAccountSummaryData.getTotalFeeCharge(), savingsAccountSummaryData.getTotalPenaltyCharge(),
                    savingsAccountSummaryData.getTotalAnnualFees(), savingsAccountSummaryData.getAccountBalance(),
                    savingsAccountSummaryData.getTotalOverdraftInterestDerived(), savingsAccountSummaryData.getTotalWithholdTax(),
                    savingsAccountSummaryData.getLastInterestCalculationDate(),
                    savingsAccountSummaryData.getInterestPostedTillDate() != null ? savingsAccountSummaryData.getInterestPostedTillDate()
                            : savingsAccountSummaryData.getLastInterestCalculationDate(),
                    auditTime, userId, savingsAccountData.getId() });
            List<SavingsAccountTransactionData> savingsAccountTransactionDataList = savingsAccountData.getSavingsAccountTransactionData();
            for (SavingsAccountTransactionData savingsAccountTransactionData : savingsAccountTransactionDataList) {
                if (savingsAccountTransactionData.getId() == null && !MathUtil.isZero(savingsAccountTransactionData.getAmount())) {
                    UUID uuid = UUID.randomUUID();
                    savingsAccountTransactionData.setRefNo(uuid.toString());
                    transRefNo.add(uuid.toString());
                    paramsForTransactionInsertion.add(new Object[] { savingsAccountData.getId(), savingsAccountData.getOfficeId(),
                            savingsAccountTransactionData.isReversed(), savingsAccountTransactionData.getTransactionType().getId(),
                            savingsAccountTransactionData.getTransactionDate(), savingsAccountTransactionData.getAmount(),
                            savingsAccountTransactionData.getBalanceEndDate(), savingsAccountTransactionData.getBalanceNumberOfDays(),
                            savingsAccountTransactionData.getRunningBalance(), savingsAccountTransactionData.getCumulativeBalance(),
                            auditTime, userId, auditTime, userId, savingsAccountTransactionData.isManualTransaction(),
                            savingsAccountTransactionData.getRefNo(), savingsAccountTransactionData.isReversalTransaction(),
                            savingsAccountTransactionData.getOverdraftAmount(), currentDate });
                } else {
                    paramsForTransactionUpdate.add(new Object[] { savingsAccountTransactionData.isReversed(),
                            savingsAccountTransactionData.getAmount(), savingsAccountTransactionData.getOverdraftAmount(),
                            savingsAccountTransactionData.getBalanceEndDate(), savingsAccountTransactionData.getBalanceNumberOfDays(),
                            savingsAccountTransactionData.getRunningBalance(), savingsAccountTransactionData.getCumulativeBalance(),
                            savingsAccountTransactionData.isReversalTransaction(), auditTime, userId,
                            savingsAccountTransactionData.getId() });
                }
            }
            savingsAccountData.setUpdatedTransactions(savingsAccountTransactionDataList);
        }

        if (transRefNo.size() > 0) {
            this.jdbcTemplate.batchUpdate(queryForSavingsUpdate, paramsForSavingsSummary);
            this.jdbcTemplate.batchUpdate(queryForTransactionInsertion, paramsForTransactionInsertion);
            this.jdbcTemplate.batchUpdate(queryForTransactionUpdate, paramsForTransactionUpdate);
            log.debug("`Total No Of Interest Posting:` {}", transRefNo.size());
            List<SavingsAccountTransactionData> savingsAccountTransactionDataList = fetchTransactionsFromIds(transRefNo);
            if (savingsAccountDataList != null) {
                log.debug("Fetched Transactions from DB: {}", savingsAccountTransactionDataList.size());
            }

            HashMap<String, SavingsAccountTransactionData> savingsAccountTransactionMap = new HashMap<>();
            for (SavingsAccountTransactionData savingsAccountTransactionData : savingsAccountTransactionDataList) {
                final String key = savingsAccountTransactionData.getRefNo();
                savingsAccountTransactionMap.put(key, savingsAccountTransactionData);
            }
            batchUpdateJournalEntries(savingsAccountDataList, savingsAccountTransactionMap);
        }

    }

    private String batchQueryForTransactionInsertion() {
        return "INSERT INTO m_savings_account_transaction (savings_account_id, office_id, is_reversed, transaction_type_enum, transaction_date, amount, balance_end_date_derived, "
                + "balance_number_of_days_derived, running_balance_derived, cumulative_balance_derived, " + CREATED_DATE_DB_FIELD + ", "
                + CREATED_BY_DB_FIELD + ", " + LAST_MODIFIED_DATE_DB_FIELD + ", " + LAST_MODIFIED_BY_DB_FIELD
                + ", is_manual, ref_no, is_reversal, "
                + "overdraft_amount_derived, submitted_on_date) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
    }

    private String batchQueryForSavingsSummaryUpdate() {
        return "update m_savings_account set total_deposits_derived=?, total_withdrawals_derived=?, total_interest_earned_derived=?, total_interest_posted_derived=?, total_withdrawal_fees_derived=?, "
                + "total_fees_charge_derived=?, total_penalty_charge_derived=?, total_annual_fees_derived=?, account_balance_derived=?, total_overdraft_interest_derived=?, total_withhold_tax_derived=?, "
                + "last_interest_calculation_date=?, interest_posted_till_date=?, " + LAST_MODIFIED_DATE_DB_FIELD + " = ?, "
                + LAST_MODIFIED_BY_DB_FIELD + " = ? WHERE id=? ";
    }

    private String batchQueryForTransactionsUpdate() {
        return "UPDATE m_savings_account_transaction "
                + "SET is_reversed=?, amount=?, overdraft_amount_derived=?, balance_end_date_derived=?, balance_number_of_days_derived=?, running_balance_derived=?, cumulative_balance_derived=?, is_reversal=?, "
                + LAST_MODIFIED_DATE_DB_FIELD + " = ?, " + LAST_MODIFIED_BY_DB_FIELD + " = ? " + "WHERE id=?";
    }
}
