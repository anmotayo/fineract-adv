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

import com.advancly.fineract.portfolio.savings.domain.DynamicDepositAccount;
import com.advancly.fineract.portfolio.savings.domain.DynamicDepositAccountAssembler;
import com.advancly.fineract.portfolio.savings.domain.DynamicDepositAccountRepository;
import com.advancly.fineract.portfolio.savings.exception.DynamicDepositAccountNotFoundException;
import com.advancly.fineract.portfolio.savings.validator.DynamicDepositAccountDataValidator;
import jakarta.persistence.PersistenceException;
import java.time.format.DateTimeFormatter;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.apache.fineract.infrastructure.accountnumberformat.domain.AccountNumberFormat;
import org.apache.fineract.infrastructure.accountnumberformat.domain.AccountNumberFormatRepositoryWrapper;
import org.apache.fineract.infrastructure.accountnumberformat.domain.EntityAccountType;
import org.apache.fineract.infrastructure.core.api.JsonCommand;
import org.apache.fineract.infrastructure.core.data.ApiParameterError;
import org.apache.fineract.infrastructure.core.data.CommandProcessingResult;
import org.apache.fineract.infrastructure.core.data.CommandProcessingResultBuilder;
import org.apache.fineract.infrastructure.core.data.DataValidatorBuilder;
import org.apache.fineract.infrastructure.core.exception.ErrorHandler;
import org.apache.fineract.infrastructure.core.exception.PlatformApiDataValidationException;
import org.apache.fineract.infrastructure.security.service.PlatformSecurityContext;
import org.apache.fineract.portfolio.account.service.AccountNumberGenerator;
import org.apache.fineract.portfolio.client.domain.Client;
import org.apache.fineract.portfolio.client.exception.ClientNotActiveException;
import org.apache.fineract.portfolio.group.domain.Group;
import org.apache.fineract.portfolio.group.exception.CenterNotActiveException;
import org.apache.fineract.portfolio.group.exception.GroupNotActiveException;
import org.apache.fineract.portfolio.note.domain.Note;
import org.apache.fineract.portfolio.note.domain.NoteRepository;
import org.apache.fineract.portfolio.savings.data.SavingsAccountTransactionDataValidator;
import org.apache.fineract.portfolio.savings.service.SavingsAccountApplicationTransitionApiJsonValidator;
import org.apache.fineract.portfolio.savings.service.SavingsAccountWritePlatformService;
import org.apache.fineract.useradministration.domain.AppUser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Lean, self-contained write-side implementation for the {@code DYNAMICDEPOSITACCOUNT} entity. Structurally mirrors
 * {@code DepositApplicationProcessWritePlatformServiceJpaRepositoryImpl}'s fixed-deposit branch, reusing the generic
 * (core, not fixed-deposit-specific) {@link SavingsAccountApplicationTransitionApiJsonValidator} for the
 * approve/undo/reject/withdraw transition-guard validation. Deliberately excludes linked-savings-account association
 * and business-event notification wiring for Phase 1 leanness (see the implementation report).
 */
@Service
public class DynamicDepositAccountWritePlatformServiceJpaRepositoryImpl implements DynamicDepositAccountWritePlatformService {

    private static final Logger LOG = LoggerFactory.getLogger(DynamicDepositAccountWritePlatformServiceJpaRepositoryImpl.class);

    private final PlatformSecurityContext context;
    private final DynamicDepositAccountRepository dynamicDepositAccountRepository;
    private final DynamicDepositAccountDataValidator dynamicDepositAccountDataValidator;
    private final DynamicDepositAccountAssembler dynamicDepositAccountAssembler;
    private final AccountNumberGenerator accountNumberGenerator;
    private final AccountNumberFormatRepositoryWrapper accountNumberFormatRepository;
    private final NoteRepository noteRepository;
    private final SavingsAccountApplicationTransitionApiJsonValidator savingsAccountApplicationTransitionApiJsonValidator;
    private final SavingsAccountTransactionDataValidator savingsAccountTransactionDataValidator;
    private final SavingsAccountWritePlatformService savingsAccountWritePlatformService;

    public DynamicDepositAccountWritePlatformServiceJpaRepositoryImpl(final PlatformSecurityContext context,
            final DynamicDepositAccountRepository dynamicDepositAccountRepository,
            final DynamicDepositAccountDataValidator dynamicDepositAccountDataValidator,
            final DynamicDepositAccountAssembler dynamicDepositAccountAssembler, final AccountNumberGenerator accountNumberGenerator,
            final AccountNumberFormatRepositoryWrapper accountNumberFormatRepository, final NoteRepository noteRepository,
            final SavingsAccountApplicationTransitionApiJsonValidator savingsAccountApplicationTransitionApiJsonValidator,
            final SavingsAccountTransactionDataValidator savingsAccountTransactionDataValidator,
            final SavingsAccountWritePlatformService savingsAccountWritePlatformService) {
        this.context = context;
        this.dynamicDepositAccountRepository = dynamicDepositAccountRepository;
        this.dynamicDepositAccountDataValidator = dynamicDepositAccountDataValidator;
        this.dynamicDepositAccountAssembler = dynamicDepositAccountAssembler;
        this.accountNumberGenerator = accountNumberGenerator;
        this.accountNumberFormatRepository = accountNumberFormatRepository;
        this.noteRepository = noteRepository;
        this.savingsAccountApplicationTransitionApiJsonValidator = savingsAccountApplicationTransitionApiJsonValidator;
        this.savingsAccountTransactionDataValidator = savingsAccountTransactionDataValidator;
        this.savingsAccountWritePlatformService = savingsAccountWritePlatformService;
    }

    @Transactional
    @Override
    public CommandProcessingResult submitApplication(final JsonCommand command) {
        try {
            this.dynamicDepositAccountDataValidator.validateForCreate(command.json());
            this.context.authenticatedUser();

            final DynamicDepositAccount account = this.dynamicDepositAccountAssembler.assembleFrom(command);

            this.dynamicDepositAccountRepository.saveAndFlush(account);

            if (account.isAccountNumberRequiresAutoGeneration()) {
                final AccountNumberFormat accountNumberFormat = this.accountNumberFormatRepository
                        .findByAccountType(EntityAccountType.SAVINGS);
                account.updateAccountNo(this.accountNumberGenerator.generate(account, accountNumberFormat));
                this.dynamicDepositAccountRepository.save(account);
            }

            final Long savingsId = account.getId();
            return new CommandProcessingResultBuilder() //
                    .withCommandId(command.commandId()) //
                    .withEntityId(savingsId) //
                    .withOfficeId(account.officeId()) //
                    .withClientId(account.clientId()) //
                    .withGroupId(account.groupId()) //
                    .withSavingsId(savingsId) //
                    .build();
        } catch (final DataAccessException dve) {
            handleDataIntegrityIssues(command, dve.getMostSpecificCause(), dve);
            return CommandProcessingResult.empty();
        } catch (final PersistenceException dve) {
            handleDataIntegrityIssues(command, ExceptionUtils.getRootCause(dve.getCause()), dve);
            return CommandProcessingResult.empty();
        }
    }

    @Transactional
    @Override
    public CommandProcessingResult modifyApplication(final Long accountId, final JsonCommand command) {
        try {
            this.dynamicDepositAccountDataValidator.validateForUpdate(command.json());

            final DynamicDepositAccount account = findAccount(accountId);
            checkClientOrGroupActive(account);

            final Map<String, Object> changes = new LinkedHashMap<>(20);
            account.modifyApplication(command, changes);
            account.validateNewApplicationState(
                    com.advancly.fineract.portfolio.savings.DynamicDepositApiConstants.DYNAMIC_DEPOSIT_ACCOUNT_RESOURCE_NAME);

            if (!changes.isEmpty()) {
                this.dynamicDepositAccountRepository.saveAndFlush(account);
            }

            return new CommandProcessingResultBuilder() //
                    .withCommandId(command.commandId()) //
                    .withEntityId(accountId) //
                    .withOfficeId(account.officeId()) //
                    .withClientId(account.clientId()) //
                    .withGroupId(account.groupId()) //
                    .withSavingsId(accountId) //
                    .with(changes) //
                    .build();
        } catch (final DataAccessException dve) {
            handleDataIntegrityIssues(command, dve.getMostSpecificCause(), dve);
            return CommandProcessingResult.empty();
        } catch (final PersistenceException dve) {
            handleDataIntegrityIssues(command, ExceptionUtils.getRootCause(dve.getCause()), dve);
            return CommandProcessingResult.empty();
        }
    }

    @Transactional
    @Override
    public CommandProcessingResult deleteApplication(final Long accountId) {
        final DynamicDepositAccount account = findAccount(accountId);
        checkClientOrGroupActive(account);

        if (account.isNotSubmittedAndPendingApproval()) {
            final List<ApiParameterError> dataValidationErrors = new java.util.ArrayList<>();
            final DataValidatorBuilder baseDataValidator = new DataValidatorBuilder(dataValidationErrors).resource(
                    com.advancly.fineract.portfolio.savings.DynamicDepositApiConstants.DYNAMIC_DEPOSIT_ACCOUNT_RESOURCE_NAME + ".delete");
            baseDataValidator.reset().parameter("activatedOnDate")
                    .failWithCodeNoParameterAddedToErrorCode("not.in.submittedandpendingapproval.state");
            if (!dataValidationErrors.isEmpty()) {
                throw new PlatformApiDataValidationException(dataValidationErrors);
            }
        }

        final List<Note> relatedNotes = this.noteRepository.findBySavingsAccount(account);
        this.noteRepository.deleteAllInBatch(relatedNotes);

        this.dynamicDepositAccountRepository.delete(account);

        return new CommandProcessingResultBuilder() //
                .withEntityId(accountId) //
                .withOfficeId(account.officeId()) //
                .withClientId(account.clientId()) //
                .withGroupId(account.groupId()) //
                .withSavingsId(accountId) //
                .build();
    }

    @Transactional
    @Override
    public CommandProcessingResult approveApplication(final Long accountId, final JsonCommand command) {
        final AppUser currentUser = this.context.authenticatedUser();
        this.savingsAccountApplicationTransitionApiJsonValidator.validateApproval(command.json());

        final DynamicDepositAccount account = findAccount(accountId);
        checkClientOrGroupActive(account);

        final Map<String, Object> changes = account.approveApplication(currentUser, command);
        return saveAndBuildTransitionResult(account, command, changes);
    }

    @Transactional
    @Override
    public CommandProcessingResult undoApplicationApproval(final Long accountId, final JsonCommand command) {
        this.context.authenticatedUser();
        this.savingsAccountApplicationTransitionApiJsonValidator.validateForUndo(command.json());

        final DynamicDepositAccount account = findAccount(accountId);
        checkClientOrGroupActive(account);

        final Map<String, Object> changes = account.undoApplicationApproval();
        return saveAndBuildTransitionResult(account, command, changes);
    }

    @Transactional
    @Override
    public CommandProcessingResult rejectApplication(final Long accountId, final JsonCommand command) {
        final AppUser currentUser = this.context.authenticatedUser();
        this.savingsAccountApplicationTransitionApiJsonValidator.validateRejection(command.json());

        final DynamicDepositAccount account = findAccount(accountId);
        checkClientOrGroupActive(account);

        final Map<String, Object> changes = account.rejectApplication(currentUser, command);
        return saveAndBuildTransitionResult(account, command, changes);
    }

    @Transactional
    @Override
    public CommandProcessingResult withdrawApplication(final Long accountId, final JsonCommand command) {
        final AppUser currentUser = this.context.authenticatedUser();
        this.savingsAccountApplicationTransitionApiJsonValidator.validateApplicantWithdrawal(command.json());

        final DynamicDepositAccount account = findAccount(accountId);
        checkClientOrGroupActive(account);

        final Map<String, Object> changes = account.applicantWithdrawsFromApplication(currentUser, command);
        return saveAndBuildTransitionResult(account, command, changes);
    }

    @Transactional
    @Override
    public CommandProcessingResult activate(final Long accountId, final JsonCommand command) {
        final AppUser currentUser = this.context.authenticatedUser();
        this.savingsAccountTransactionDataValidator.validateActivation(command);

        final DynamicDepositAccount account = findAccount(accountId);
        checkClientOrGroupActive(account);

        final Map<String, Object> changes = account.activate(currentUser, command);
        if (!changes.isEmpty()) {
            // Mirrors SavingsAccountWritePlatformServiceJpaRepositoryImpl#activate: without this call, activation
            // never creates the opening-balance deposit transaction, so a Dynamic Deposit account would have no
            // transaction to key its first m_deposit_account_dynamic_rate_history row to (implementation plan,
            // Section 3 step 7 / Section 6). Reused directly rather than duplicated - see plan's "Confirmed gap".
            final DateTimeFormatter fmt = DateTimeFormatter.ofPattern(command.dateFormat()).withLocale(command.extractLocale());
            final Set<Long> existingTransactionIds = new HashSet<>();
            final Set<Long> existingReversedTransactionIds = new HashSet<>();
            this.savingsAccountWritePlatformService.processPostActiveActions(account, fmt, existingTransactionIds,
                    existingReversedTransactionIds);
        }
        return saveAndBuildTransitionResult(account, command, changes);
    }

    private CommandProcessingResult saveAndBuildTransitionResult(final DynamicDepositAccount account, final JsonCommand command,
            final Map<String, Object> changes) {
        if (!changes.isEmpty()) {
            this.dynamicDepositAccountRepository.saveAndFlush(account);

            final String noteText = command.stringValueOfParameterNamed("note");
            if (StringUtils.isNotBlank(noteText)) {
                final Note note = Note.savingNote(account, noteText);
                changes.put("note", noteText);
                this.noteRepository.save(note);
            }
        }

        return new CommandProcessingResultBuilder() //
                .withCommandId(command.commandId()) //
                .withEntityId(account.getId()) //
                .withOfficeId(account.officeId()) //
                .withClientId(account.clientId()) //
                .withGroupId(account.groupId()) //
                .withSavingsId(account.getId()) //
                .with(changes) //
                .build();
    }

    private DynamicDepositAccount findAccount(final Long accountId) {
        return this.dynamicDepositAccountRepository.findById(accountId)
                .orElseThrow(() -> new DynamicDepositAccountNotFoundException(accountId));
    }

    private void checkClientOrGroupActive(final DynamicDepositAccount account) {
        final Client client = account.getClient();
        if (client != null && client.isNotActive()) {
            throw new ClientNotActiveException(client.getId());
        }
        final Group group = account.group();
        if (group != null && group.isNotActive()) {
            if (group.isCenter()) {
                throw new CenterNotActiveException(group.getId());
            }
            throw new GroupNotActiveException(group.getId());
        }
    }

    private void handleDataIntegrityIssues(final JsonCommand command, final Throwable realCause, final Exception dve) {
        String msgCode = "error.msg.dynamicdepositaccount";
        String msg = "Unknown data integrity issue with dynamic deposit account.";
        String param = null;
        Object[] msgArgs;
        final Throwable checkEx = realCause == null ? dve : realCause;
        if (checkEx.getMessage() != null && checkEx.getMessage().contains("sa_account_no_UNIQUE")) {
            final String accountNo = command.stringValueOfParameterNamed("accountNo");
            msgCode += ".duplicate.accountNo";
            msg = "Dynamic deposit account with accountNo " + accountNo + " already exists";
            param = "accountNo";
            msgArgs = new Object[] { accountNo, dve };
        } else if (checkEx.getMessage() != null && checkEx.getMessage().contains("sa_external_id_UNIQUE")) {
            final String externalId = command.stringValueOfParameterNamed("externalId");
            msgCode += ".duplicate.externalId";
            msg = "Dynamic deposit account with externalId " + externalId + " already exists";
            param = "externalId";
            msgArgs = new Object[] { externalId, dve };
        } else {
            msgCode += ".unknown.data.integrity.issue";
            msgArgs = new Object[] { dve };
        }
        LOG.error("Error occurred.", dve);
        throw ErrorHandler.getMappable(dve, msgCode, msg, param, msgArgs);
    }
}
