package com.advancly.fineract.portfolio.savings.service;

import com.advancly.fineract.portfolio.savings.domain.SavingsAccountInterestCharge;
import com.advancly.fineract.portfolio.savings.domain.SavingsAccountInterestChargeRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import org.apache.fineract.organisation.monetary.domain.Money;
import org.apache.fineract.portfolio.note.domain.Note;
import org.apache.fineract.portfolio.note.domain.NoteRepository;
import org.apache.fineract.portfolio.savings.domain.SavingsAccount;
import org.apache.fineract.portfolio.savings.domain.SavingsAccountCharge;
import org.apache.fineract.portfolio.savings.domain.SavingsAccountChargePaidBy;
import org.apache.fineract.portfolio.savings.domain.SavingsAccountTransaction;
import org.apache.fineract.portfolio.savings.service.SavingsAccountWritePlatformService;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Cumulative early-withdrawal interest forfeiture: an early withdrawal takes back all the interest the account has
 * earned since inception, computed and charged immediately rather than deferred to the next interest posting the way
 * per-period mode is.
 *
 * Runs at the write-platform layer, not from an entity hook, because it force-posts interest - and that posting does
 * saveAndFlush and writes journal entries, which would be re-entrant if invoked from inside a withdrawal already in
 * progress. Premature closure establishes the same shape at the same layer.
 *
 * Withholding tax on the forced posting is deferred: the automatic tax step inside postInterest is suppressed for that
 * one call, and applied afterward only to whatever of the newly posted stub survives forfeiture (forfeiture is treated
 * as consuming the newest interest first) - not to its gross amount. At 100% forfeiture the stub is entirely consumed
 * and nothing is taxed; tax on interest from periods before this one was already settled when it posted.
 */
@Component
public class CumulativeInterestForfeitureService {

    private static final DateTimeFormatter NOTE_DATE = DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.ENGLISH);

    private final SavingsAccountInterestChargeRepository interestChargeRepository;
    private final DynamicDepositEarlyWithdrawalChargeService earlyWithdrawalChargeService;
    private final SavingsAccountWritePlatformService savingsAccountWritePlatformService;
    private final NoteRepository noteRepository;

    // @Lazy breaks a genuine bean cycle: AdvanclySavingsAccountWritePlatformService (@Primary) injects this service to
    // trigger forfeiture, and this service injects it back to force-post interest. Without @Lazy the context fails to
    // start with a circular-reference error. The proxy resolves on first use, long after startup.
    public CumulativeInterestForfeitureService(final SavingsAccountInterestChargeRepository interestChargeRepository,
            final DynamicDepositEarlyWithdrawalChargeService earlyWithdrawalChargeService,
            @Lazy final SavingsAccountWritePlatformService savingsAccountWritePlatformService, final NoteRepository noteRepository) {
        this.interestChargeRepository = interestChargeRepository;
        this.earlyWithdrawalChargeService = earlyWithdrawalChargeService;
        this.savingsAccountWritePlatformService = savingsAccountWritePlatformService;
        this.noteRepository = noteRepository;
    }

    /**
     * @return the forfeiture transaction, or {@code null} when there was nothing to forfeit (no qualifying charge, or
     *         no interest posted yet - the latter being how a withdrawal taken before the first posting correctly costs
     *         the customer nothing rather than eating into principal).
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public SavingsAccountTransaction forfeitIfApplicable(final SavingsAccount account, final LocalDate withdrawalDate,
            final boolean backdatedTxnsAllowedTill) {
        final DynamicDepositEarlyWithdrawalChargeService.QualifyingCharge qualifying = this.earlyWithdrawalChargeService
                .resolveQualifyingChargeWithPercentage(account);
        if (qualifying == null) {
            return null;
        }

        // Force-post first so the accrual up to this withdrawal becomes real, credited interest before any of it is
        // taken back. postInterestAs=true is load-bearing: it persists is_manual=true, which later recalculations
        // re-derive as a genuine posting-period boundary, so the next scheduled run posts only the increment instead
        // of re-posting the whole period on top of this one.
        //
        // withHoldTax is suppressed for this one call and restored immediately after - postInterest's automatic tax
        // step has no way to be told "wait to see how much of this survives forfeiture first", so it is turned off
        // here and applied manually, below, only to the survivor. The account's real tax configuration is unchanged.
        final LocalDate forcedPostingDate = withdrawalDate.minusDays(1);
        final boolean accountWithHoldTax = account.withHoldTax();
        account.setWithHoldTax(false);
        this.savingsAccountWritePlatformService.postInterest(account, true, forcedPostingDate, backdatedTxnsAllowedTill);
        account.setWithHoldTax(accountWithHoldTax);

        final SavingsAccountTransaction newlyPostedStub = latestInterestPostingOn(account, forcedPostingDate);
        noteOn(account, newlyPostedStub, "Interest accrued on pro rata basis from period start to day before withdrawal");

        final BigDecimal alreadyForfeited = this.interestChargeRepository.sumPostedChargeAmount(account.getId());
        final BigDecimal amount = CumulativeForfeitureCalculator.forfeitureAmount(account.getSummary().getTotalInterestPosted(),
                account.getSummary().getTotalWithholdTax(), alreadyForfeited, qualifying.percentage());

        // Forfeiture is treated as consuming the newest interest first - it's the interest this same withdrawal just
        // force-posted, so whatever of that stub ISN'T swallowed by the forfeiture is what actually reaches the
        // customer, and only that is taxable. Runs regardless of whether anything was forfeited at all (amount may
        // be zero, in which case the whole stub survives and is taxed exactly as an ordinary posting would).
        if (newlyPostedStub != null && accountWithHoldTax) {
            final BigDecimal newlyPostedStubAmount = newlyPostedStub.getAmount();
            final BigDecimal forfeitFromNewStub = amount.min(newlyPostedStubAmount);
            final BigDecimal taxableSurvivor = newlyPostedStubAmount.subtract(forfeitFromNewStub);
            if (taxableSurvivor.compareTo(BigDecimal.ZERO) > 0) {
                account.withholdTaxIfApplicable(taxableSurvivor, forcedPostingDate, backdatedTxnsAllowedTill);
            }
        }

        if (amount.compareTo(BigDecimal.ZERO) <= 0) {
            return null;
        }

        final SavingsAccountCharge accountCharge = qualifying.accountCharge();
        final SavingsAccountTransaction forfeiture = SavingsAccountTransaction.interestForfeiture(account, account.office(), withdrawalDate,
                Money.of(account.getCurrency(), amount));
        final BigDecimal appliedAmount = forfeiture.getAmount();

        // Mirrors SavingsAccount.payCharge(...): pay(...) before building the SavingsAccountChargePaidBy link, so the
        // charge's own paid/outstanding bookkeeping stays symmetric with undoTransaction's undoPayment(...).
        accountCharge.pay(account.getCurrency(), Money.of(account.getCurrency(), appliedAmount));
        forfeiture.getSavingsAccountChargesPaid().add(SavingsAccountChargePaidBy.instance(forfeiture, accountCharge, appliedAmount));
        if (backdatedTxnsAllowedTill) {
            account.addTransactionToExisting(forfeiture);
        } else {
            account.addTransaction(forfeiture);
        }
        noteOn(account, forfeiture, "Interest forfeiture due to early withdrawal on " + withdrawalDate.format(NOTE_DATE));

        this.interestChargeRepository.saveAndFlush(SavingsAccountInterestCharge.createApplied(account, forfeiture, accountCharge,
                accountCharge.getCharge(), this.earlyWithdrawalChargeService.currentPeriodStartDate(account), withdrawalDate,
                account.getSummary().getTotalInterestPosted(), qualifying.percentage(), appliedAmount, null, forfeiture));

        return forfeiture;
    }

    private SavingsAccountTransaction latestInterestPostingOn(final SavingsAccount account, final LocalDate date) {
        SavingsAccountTransaction found = null;
        for (final SavingsAccountTransaction transaction : account.getTransactions()) {
            if (transaction.isInterestPostingAndNotReversed() && date.equals(transaction.getTransactionDate())) {
                found = transaction;
            }
        }
        return found;
    }

    private void noteOn(final SavingsAccount account, final SavingsAccountTransaction transaction, final String text) {
        if (transaction == null) {
            return;
        }
        this.noteRepository.save(Note.savingsTransactionNote(account, transaction, text));
    }
}
