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
package org.apache.fineract.accounting.journalentry.service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.apache.fineract.accounting.closure.domain.GLClosure;
import org.apache.fineract.accounting.closure.domain.GLClosureRepository;
import org.apache.fineract.accounting.glaccount.data.GLAccountDataForLookup;
import org.apache.fineract.accounting.glaccount.domain.GLAccount;
import org.apache.fineract.accounting.glaccount.domain.GLAccountRepository;
import org.apache.fineract.accounting.glaccount.exception.GLAccountNotFoundException;
import org.apache.fineract.accounting.glaccount.service.GLAccountReadPlatformService;
import org.apache.fineract.accounting.journalentry.api.JournalEntryJsonInputParams;
import org.apache.fineract.accounting.journalentry.command.JournalEntryCommand;
import org.apache.fineract.accounting.journalentry.command.SingleDebitOrCreditEntryCommand;
import org.apache.fineract.accounting.journalentry.domain.JournalEntryType;
import org.apache.fineract.accounting.journalentry.exception.JournalEntryInvalidException;
import org.apache.fineract.accounting.journalentry.exception.JournalEntryInvalidException.GlJournalEntryInvalidReason;
import org.apache.fineract.accounting.journalentry.exception.JournalEntryRuntimeException;
import org.apache.fineract.accounting.journalentry.serialization.JournalEntryCommandFromApiJsonDeserializer;
import org.apache.fineract.accounting.rule.domain.AccountingRule;
import org.apache.fineract.accounting.rule.domain.AccountingRuleRepository;
import org.apache.fineract.accounting.rule.exception.AccountingRuleNotFoundException;
import org.apache.fineract.infrastructure.core.api.JsonCommand;
import org.apache.fineract.infrastructure.core.service.DateUtils;
import org.apache.fineract.organisation.monetary.domain.OrganisationCurrencyRepositoryWrapper;
import org.apache.fineract.organisation.office.domain.OfficeRepositoryWrapper;
import org.apache.fineract.portfolio.paymentdetail.PaymentDetailConstants;
import org.apache.fineract.portfolio.paymenttype.domain.PaymentTypeRepositoryWrapper;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class JournalEntryValidator {

    private final GLClosureRepository glClosureRepository;
    private final GLAccountRepository glAccountRepository;
    private final OfficeRepositoryWrapper officeRepositoryWrapper;
    private final JournalEntryCommandFromApiJsonDeserializer fromApiJsonDeserializer;
    private final AccountingRuleRepository accountingRuleRepository;
    private final GLAccountReadPlatformService glAccountReadPlatformService;
    private final OrganisationCurrencyRepositoryWrapper organisationCurrencyRepository;
    private final PaymentTypeRepositoryWrapper paymentTypeRepositoryWrapper;

    public void validateJournalEntry(final JsonCommand command) {
        final JournalEntryCommand journalEntryCommand = this.fromApiJsonDeserializer.commandFromApiJson(command.json());
        journalEntryCommand.validateForCreate();

        final Long officeId = command.longValueOfParameterNamed(JournalEntryJsonInputParams.OFFICE_ID.getValue());
        this.officeRepositoryWrapper.findOneWithNotFoundDetection(officeId);
        final Long accountRuleId = command.longValueOfParameterNamed(JournalEntryJsonInputParams.ACCOUNTING_RULE.getValue());
        final String currencyCode = command.stringValueOfParameterNamed(JournalEntryJsonInputParams.CURRENCY_CODE.getValue());

        validateBusinessRulesForJournalEntries(journalEntryCommand);

        final Long paymentTypeId = command.longValueOfParameterNamed(PaymentDetailConstants.paymentTypeParamName);
        if (paymentTypeId != null) {
            this.paymentTypeRepositoryWrapper.findOneWithNotFoundDetection(paymentTypeId);
        }

        if (accountRuleId != null) {
            validateAccountingRuleForJournalEntry(journalEntryCommand, accountRuleId);
        }

        validateAllDebitOrCreditEntries(currencyCode, journalEntryCommand.getDebits());
        validateAllDebitOrCreditEntries(currencyCode, journalEntryCommand.getCredits());
    }

    private void validateAccountingRuleForJournalEntry(final JournalEntryCommand journalEntryCommand, final Long accountRuleId) {
        final AccountingRule accountingRule = this.accountingRuleRepository.findById(accountRuleId)
                .orElseThrow(() -> new AccountingRuleNotFoundException(accountRuleId));

        if (accountingRule.getAccountToCredit() == null) {
            if (journalEntryCommand.getCredits() == null) {
                throw new JournalEntryInvalidException(GlJournalEntryInvalidReason.NO_DEBITS_OR_CREDITS, null, null, null);
            }
            if (journalEntryCommand.getDebits() != null) {
                checkDebitOrCreditAccountsAreValid(accountingRule, journalEntryCommand.getCredits(), journalEntryCommand.getDebits());
                checkDebitAndCreditAmounts(journalEntryCommand.getCredits(), journalEntryCommand.getDebits());
            }
        } else {
            final GLAccount creditAccountHead = accountingRule.getAccountToCredit();
            validateGLAccountForTransaction(creditAccountHead);
            validateDebitOrCreditArrayForExistingGLAccount(creditAccountHead, journalEntryCommand.getCredits());
        }

        if (accountingRule.getAccountToDebit() == null) {
            if (journalEntryCommand.getDebits() == null) {
                throw new JournalEntryInvalidException(GlJournalEntryInvalidReason.NO_DEBITS_OR_CREDITS, null, null, null);
            }
            if (journalEntryCommand.getCredits() != null) {
                checkDebitOrCreditAccountsAreValid(accountingRule, journalEntryCommand.getCredits(), journalEntryCommand.getDebits());
                checkDebitAndCreditAmounts(journalEntryCommand.getCredits(), journalEntryCommand.getDebits());
            }
        } else {
            final GLAccount debitAccountHead = accountingRule.getAccountToDebit();
            validateGLAccountForTransaction(debitAccountHead);
            validateDebitOrCreditArrayForExistingGLAccount(debitAccountHead, journalEntryCommand.getDebits());
        }
    }

    private void validateAllDebitOrCreditEntries(final String currencyCode,
            final SingleDebitOrCreditEntryCommand[] singleDebitOrCreditEntryCommands) {
        if (singleDebitOrCreditEntryCommands == null) {
            return;
        }
        for (final SingleDebitOrCreditEntryCommand singleDebitOrCreditEntryCommand : singleDebitOrCreditEntryCommands) {
            final GLAccount glAccount = this.glAccountRepository.findById(singleDebitOrCreditEntryCommand.getGlAccountId())
                    .orElseThrow(() -> new GLAccountNotFoundException(singleDebitOrCreditEntryCommand.getGlAccountId()));

            validateGLAccountForTransaction(glAccount);
            this.organisationCurrencyRepository.findOneWithNotFoundDetection(currencyCode);
        }
    }

    private void validateDebitOrCreditArrayForExistingGLAccount(final GLAccount glaccount,
            final SingleDebitOrCreditEntryCommand[] creditOrDebits) {
        if (creditOrDebits == null || creditOrDebits.length != 1) {
            throw new JournalEntryInvalidException(GlJournalEntryInvalidReason.INVALID_DEBIT_OR_CREDIT_ACCOUNTS, null, null, null);
        }
        for (final SingleDebitOrCreditEntryCommand creditOrDebit : creditOrDebits) {
            if (glaccount == null || creditOrDebit == null || !Objects.equals(glaccount.getId(), creditOrDebit.getGlAccountId())) {
                throw new JournalEntryInvalidException(GlJournalEntryInvalidReason.INVALID_DEBIT_OR_CREDIT_ACCOUNTS, null, null, null);
            }
        }
    }

    private void checkDebitOrCreditAccountsAreValid(final AccountingRule accountingRule, final SingleDebitOrCreditEntryCommand[] credits,
            final SingleDebitOrCreditEntryCommand[] debits) {
        List<GLAccountDataForLookup> allowedCreditGLAccounts;
        List<GLAccountDataForLookup> allowedDebitGLAccounts;
        int validCreditsNo = 0;
        int validDebitsNo = 0;

        if (credits != null && credits.length > 0) {
            allowedCreditGLAccounts = this.glAccountReadPlatformService.retrieveAccountsByTagId(accountingRule.getId(),
                    JournalEntryType.CREDIT.getValue());
            for (final GLAccountDataForLookup accountDataForLookup : allowedCreditGLAccounts) {
                for (final SingleDebitOrCreditEntryCommand credit : credits) {
                    if (credit.getGlAccountId().equals(accountDataForLookup.getId())) {
                        validCreditsNo++;
                    }
                }
            }
            if (credits.length != validCreditsNo) {
                throw new JournalEntryRuntimeException("error.msg.glJournalEntry.invalid.credits", "Invalid Credits.");
            }
        }

        if (debits != null && debits.length > 0) {
            allowedDebitGLAccounts = this.glAccountReadPlatformService.retrieveAccountsByTagId(accountingRule.getId(),
                    JournalEntryType.DEBIT.getValue());
            for (final GLAccountDataForLookup accountDataForLookup : allowedDebitGLAccounts) {
                for (final SingleDebitOrCreditEntryCommand debit : debits) {
                    if (debit.getGlAccountId().equals(accountDataForLookup.getId())) {
                        validDebitsNo++;
                    }
                }
            }
            if (debits.length != validDebitsNo) {
                throw new JournalEntryRuntimeException("error.msg.glJournalEntry.invalid.debits", "Invalid Debits");
            }
        }
    }

    private void checkDebitAndCreditAmounts(final SingleDebitOrCreditEntryCommand[] credits,
            final SingleDebitOrCreditEntryCommand[] debits) {
        if (credits == null || debits == null) {
            throw new JournalEntryInvalidException(GlJournalEntryInvalidReason.NO_DEBITS_OR_CREDITS, null, null, null);
        }

        BigDecimal creditsSum = BigDecimal.ZERO;
        BigDecimal debitsSum = BigDecimal.ZERO;
        for (final SingleDebitOrCreditEntryCommand creditEntryCommand : credits) {
            if (creditEntryCommand.getAmount() == null || creditEntryCommand.getGlAccountId() == null) {
                throw new JournalEntryInvalidException(GlJournalEntryInvalidReason.DEBIT_CREDIT_ACCOUNT_OR_AMOUNT_EMPTY, null, null, null);
            }
            creditsSum = creditsSum.add(creditEntryCommand.getAmount());
        }
        for (final SingleDebitOrCreditEntryCommand debitEntryCommand : debits) {
            if (debitEntryCommand.getAmount() == null || debitEntryCommand.getGlAccountId() == null) {
                throw new JournalEntryInvalidException(GlJournalEntryInvalidReason.DEBIT_CREDIT_ACCOUNT_OR_AMOUNT_EMPTY, null, null, null);
            }
            debitsSum = debitsSum.add(debitEntryCommand.getAmount());
        }
        if (creditsSum.compareTo(debitsSum) != 0) {
            throw new JournalEntryInvalidException(GlJournalEntryInvalidReason.DEBIT_CREDIT_SUM_MISMATCH, null, null, null);
        }
    }

    private void validateGLAccountForTransaction(final GLAccount creditOrDebitAccountHead) {
        if (creditOrDebitAccountHead.isDisabled()) {
            throw new JournalEntryInvalidException(GlJournalEntryInvalidReason.GL_ACCOUNT_DISABLED, null,
                    creditOrDebitAccountHead.getName(), creditOrDebitAccountHead.getGlCode());
        } else if (!creditOrDebitAccountHead.isManualEntriesAllowed()) {
            throw new JournalEntryInvalidException(GlJournalEntryInvalidReason.GL_ACCOUNT_MANUAL_ENTRIES_NOT_PERMITTED, null,
                    creditOrDebitAccountHead.getName(), creditOrDebitAccountHead.getGlCode());
        }
    }

    private void validateBusinessRulesForJournalEntries(final JournalEntryCommand command) {
        final LocalDate transactionDate = command.getTransactionDate();
        if (DateUtils.isDateInTheFuture(transactionDate)) {
            throw new JournalEntryInvalidException(GlJournalEntryInvalidReason.FUTURE_DATE, transactionDate, null, null);
        }
        final GLClosure latestGLClosure = this.glClosureRepository.getLatestGLClosureByBranch(command.getOfficeId());
        if (latestGLClosure != null && !DateUtils.isBefore(latestGLClosure.getClosingDate(), transactionDate)) {
            throw new JournalEntryInvalidException(GlJournalEntryInvalidReason.ACCOUNTING_CLOSED, latestGLClosure.getClosingDate(), null,
                    null);
        }

        final SingleDebitOrCreditEntryCommand[] credits = command.getCredits();
        final SingleDebitOrCreditEntryCommand[] debits = command.getDebits();
        if (credits == null || credits.length == 0 || debits == null || debits.length == 0) {
            throw new JournalEntryInvalidException(GlJournalEntryInvalidReason.NO_DEBITS_OR_CREDITS, null, null, null);
        }

        checkDebitAndCreditAmounts(credits, debits);
    }
}
