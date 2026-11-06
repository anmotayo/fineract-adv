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
import com.advancly.fineract.portfolio.savings.validator.DynamicDepositAccountClosureDataValidator;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import org.apache.commons.lang3.StringUtils;
import org.apache.fineract.infrastructure.core.api.JsonCommand;
import org.apache.fineract.infrastructure.core.data.CommandProcessingResult;
import org.apache.fineract.infrastructure.core.data.CommandProcessingResultBuilder;
import org.apache.fineract.infrastructure.core.exception.GeneralPlatformDomainRuleException;
import org.apache.fineract.infrastructure.dataqueries.data.EntityTables;
import org.apache.fineract.infrastructure.dataqueries.data.StatusEnum;
import org.apache.fineract.infrastructure.dataqueries.service.EntityDatatableChecksWritePlatformService;
import org.apache.fineract.infrastructure.event.business.domain.savings.SavingsCloseBusinessEvent;
import org.apache.fineract.infrastructure.event.business.service.BusinessEventNotifierService;
import org.apache.fineract.infrastructure.security.service.PlatformSecurityContext;
import org.apache.fineract.portfolio.account.domain.AccountTransferStandingInstruction;
import org.apache.fineract.portfolio.account.domain.StandingInstructionRepository;
import org.apache.fineract.portfolio.account.domain.StandingInstructionStatus;
import org.apache.fineract.portfolio.account.service.AccountAssociationsReadPlatformService;
import org.apache.fineract.portfolio.note.domain.Note;
import org.apache.fineract.portfolio.note.domain.NoteRepository;
import org.apache.fineract.portfolio.paymentdetail.domain.PaymentDetail;
import org.apache.fineract.portfolio.paymentdetail.service.PaymentDetailWritePlatformService;
import org.apache.fineract.portfolio.savings.domain.SavingsAccount;
import org.apache.fineract.portfolio.savings.domain.SavingsAccountAssembler;
import org.apache.fineract.portfolio.savings.exception.SavingsAccountClosingNotAllowedException;
import org.apache.fineract.useradministration.domain.AppUser;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The {@code close} and {@code prematureClose} commands of a Dynamic Deposit account - the counterpart of
 * {@code DepositAccountWritePlatformServiceJpaRepositoryImpl#closeFDAccount}/{@code prematureCloseFDAccount}. It
 * validates the request and runs the surrounding checks; settlement and payout live in
 * {@link AdvanclyDepositAccountDomainService}.
 */
@Service
public class DynamicDepositAccountClosureWritePlatformService {

    private final PlatformSecurityContext context;
    private final DynamicDepositAccountClosureDataValidator closureDataValidator;
    private final SavingsAccountAssembler savingsAccountAssembler;
    private final PaymentDetailWritePlatformService paymentDetailWritePlatformService;
    private final AdvanclyDepositAccountDomainService depositAccountDomainService;
    private final AccountAssociationsReadPlatformService accountAssociationsReadPlatformService;
    private final EntityDatatableChecksWritePlatformService entityDatatableChecksWritePlatformService;
    private final NoteRepository noteRepository;
    private final BusinessEventNotifierService businessEventNotifierService;
    private final StandingInstructionRepository standingInstructionRepository;

    @Autowired
    public DynamicDepositAccountClosureWritePlatformService(final PlatformSecurityContext context,
            final DynamicDepositAccountClosureDataValidator closureDataValidator, final SavingsAccountAssembler savingsAccountAssembler,
            final PaymentDetailWritePlatformService paymentDetailWritePlatformService,
            final AdvanclyDepositAccountDomainService depositAccountDomainService,
            final AccountAssociationsReadPlatformService accountAssociationsReadPlatformService,
            final EntityDatatableChecksWritePlatformService entityDatatableChecksWritePlatformService, final NoteRepository noteRepository,
            final BusinessEventNotifierService businessEventNotifierService,
            final StandingInstructionRepository standingInstructionRepository) {
        this.context = context;
        this.closureDataValidator = closureDataValidator;
        this.savingsAccountAssembler = savingsAccountAssembler;
        this.paymentDetailWritePlatformService = paymentDetailWritePlatformService;
        this.depositAccountDomainService = depositAccountDomainService;
        this.accountAssociationsReadPlatformService = accountAssociationsReadPlatformService;
        this.entityDatatableChecksWritePlatformService = entityDatatableChecksWritePlatformService;
        this.noteRepository = noteRepository;
        this.businessEventNotifierService = businessEventNotifierService;
        this.standingInstructionRepository = standingInstructionRepository;
    }

    @Transactional
    public CommandProcessingResult prematureClose(final Long savingsId, final JsonCommand command) {
        return closeAccount(savingsId, command, true);
    }

    @Transactional
    public CommandProcessingResult close(final Long savingsId, final JsonCommand command) {
        return closeAccount(savingsId, command, false);
    }

    private CommandProcessingResult closeAccount(final Long savingsId, final JsonCommand command, final boolean isPreMatureClosure) {
        final AppUser user = this.context.authenticatedUser();
        this.closureDataValidator.validateClosing(command, isPreMatureClosure);

        final SavingsAccount assembled = this.savingsAccountAssembler.assembleFrom(savingsId, false);
        if (!(assembled instanceof DynamicDepositAccount account)) {
            throw new GeneralPlatformDomainRuleException("error.msg.dynamicdepositaccount.closure.not.a.dynamic.deposit",
                    "Account " + savingsId + " is not a Dynamic Deposit account.", savingsId);
        }

        if (this.accountAssociationsReadPlatformService.isLinkedWithAnyActiveAccount(savingsId)) {
            throw new SavingsAccountClosingNotAllowedException("linked",
                    "Closing savings account with id:" + savingsId + " is not allowed, since it is linked with one of the active accounts",
                    savingsId);
        }
        this.entityDatatableChecksWritePlatformService.runTheCheckForProduct(savingsId, EntityTables.SAVINGS.getName(),
                StatusEnum.CLOSE.getValue(), EntityTables.SAVINGS.getForeignKeyColumnNameOnDatatable(), account.productId());

        final Map<String, Object> changes = new LinkedHashMap<>();
        final PaymentDetail paymentDetail = this.paymentDetailWritePlatformService.createAndPersistPaymentDetail(command, changes);

        if (isPreMatureClosure) {
            this.depositAccountDomainService.handleDynamicDepositPreMatureClosure(account, paymentDetail, user, command, changes);
        } else {
            this.depositAccountDomainService.handleDynamicDepositClosure(account, paymentDetail, user, command, changes);
        }

        final String noteText = command.stringValueOfParameterNamed("note");
        if (StringUtils.isNotBlank(noteText)) {
            this.noteRepository.save(Note.savingNote(account, noteText));
            changes.put("note", noteText);
        }

        this.businessEventNotifierService.notifyPostBusinessEvent(new SavingsCloseBusinessEvent(account));
        disableStandingInstructionsLinkedToClosedSavings(account);

        return new CommandProcessingResultBuilder() //
                .withEntityId(savingsId) //
                .withOfficeId(account.officeId()) //
                .withClientId(account.clientId()) //
                .withGroupId(account.groupId()) //
                .withSavingsId(savingsId) //
                .with(changes) //
                .build();
    }

    private void disableStandingInstructionsLinkedToClosedSavings(final SavingsAccount account) {
        final Collection<AccountTransferStandingInstruction> standingInstructions = this.standingInstructionRepository
                .findBySavingsAccountAndStatus(account, StandingInstructionStatus.ACTIVE.getValue());
        for (final AccountTransferStandingInstruction standingInstruction : standingInstructions) {
            standingInstruction.updateStatus(StandingInstructionStatus.DISABLED.getValue());
            this.standingInstructionRepository.save(standingInstruction);
        }
    }
}
