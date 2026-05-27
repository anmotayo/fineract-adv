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
package com.advancly.fineract.portfolio.savings.starter;

import com.advancly.fineract.portfolio.account.service.AdvanclyAccountTransfersWritePlatformService;
import com.advancly.fineract.portfolio.savings.domain.AdvanclySavingsAccountAssembler;
import com.advancly.fineract.portfolio.savings.domain.AdvanclySavingsAccountTransactionRepository;
import com.advancly.fineract.portfolio.savings.service.AdvanclySavingsAccountDomainService;
import com.advancly.fineract.portfolio.savings.service.SavingsAccountWritePlatformServiceDelegate;
import org.apache.fineract.accounting.journalentry.service.JournalEntryWritePlatformService;
import org.apache.fineract.infrastructure.configuration.domain.ConfigurationDomainService;
import org.apache.fineract.infrastructure.core.config.FineractProperties;
import org.apache.fineract.infrastructure.core.exception.ErrorHandler;
import org.apache.fineract.infrastructure.core.service.ExternalIdFactory;
import org.apache.fineract.infrastructure.dataqueries.service.EntityDatatableChecksWritePlatformService;
import org.apache.fineract.infrastructure.event.business.service.BusinessEventNotifierService;
import org.apache.fineract.infrastructure.security.service.PlatformSecurityContext;
import org.apache.fineract.organisation.holiday.domain.HolidayRepositoryWrapper;
import org.apache.fineract.organisation.staff.domain.StaffRepositoryWrapper;
import org.apache.fineract.organisation.workingdays.domain.WorkingDaysRepositoryWrapper;
import org.apache.fineract.portfolio.account.data.AccountTransfersDataValidator;
import org.apache.fineract.portfolio.account.domain.AccountTransferAssembler;
import org.apache.fineract.portfolio.account.domain.AccountTransferDetailRepository;
import org.apache.fineract.portfolio.account.domain.AccountTransferRepository;
import org.apache.fineract.portfolio.account.domain.StandingInstructionRepository;
import org.apache.fineract.portfolio.account.service.AccountAssociationsReadPlatformService;
import org.apache.fineract.portfolio.account.service.AccountTransfersReadPlatformService;
import org.apache.fineract.portfolio.account.service.AccountTransfersWritePlatformService;
import org.apache.fineract.portfolio.charge.domain.ChargeRepositoryWrapper;
import org.apache.fineract.portfolio.loanaccount.domain.LoanAccountDomainService;
import org.apache.fineract.portfolio.loanaccount.service.LoanAssembler;
import org.apache.fineract.portfolio.loanaccount.service.LoanReadPlatformService;
import org.apache.fineract.portfolio.note.domain.NoteRepository;
import org.apache.fineract.portfolio.paymentdetail.service.PaymentDetailWritePlatformService;
import org.apache.fineract.portfolio.savings.data.SavingsAccountChargeDataValidator;
import org.apache.fineract.portfolio.savings.data.SavingsAccountDataValidator;
import org.apache.fineract.portfolio.savings.data.SavingsAccountTransactionDataValidator;
import org.apache.fineract.portfolio.savings.domain.DepositAccountOnHoldTransactionRepository;
import org.apache.fineract.portfolio.savings.domain.GSIMRepositoy;
import org.apache.fineract.portfolio.savings.domain.SavingsAccountAssembler;
import org.apache.fineract.portfolio.savings.domain.SavingsAccountChargeRepositoryWrapper;
import org.apache.fineract.portfolio.savings.domain.SavingsAccountDomainServiceJpa;
import org.apache.fineract.portfolio.savings.domain.SavingsAccountRepositoryWrapper;
import org.apache.fineract.portfolio.savings.domain.SavingsAccountTransactionRepository;
import org.apache.fineract.portfolio.savings.domain.SavingsAccountTransactionSummaryWrapper;
import org.apache.fineract.portfolio.savings.domain.SavingsHelper;
import org.apache.fineract.portfolio.savings.service.SavingsAccountDomainService;
import org.apache.fineract.portfolio.savings.service.SavingsAccountInterestPostingService;
import org.apache.fineract.portfolio.savings.service.SavingsAccountWritePlatformService;
import org.apache.fineract.portfolio.savings.service.SavingsAccountWritePlatformServiceJpaRepositoryImpl;
import org.apache.fineract.portfolio.savings.service.SavingsAccrualWritePlatformService;
import org.apache.fineract.useradministration.domain.AppUserRepositoryWrapper;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

@AutoConfiguration
@ComponentScan("com.advancly.fineract.portfolio.savings")
@EnableJpaRepositories(basePackages = "com.advancly.fineract.portfolio.savings")
@ConditionalOnProperty("advancly.savings.optimization.enabled")
public class AdvanclySavingsAutoConfiguration {

    @Bean
    public AccountTransfersWritePlatformService accountTransfersWritePlatformService(
            AccountTransfersDataValidator accountTransfersDataValidator, AccountTransferAssembler accountTransferAssembler,
            AccountTransferRepository accountTransferRepository, AdvanclySavingsAccountAssembler savingsAccountAssembler,
            AdvanclySavingsAccountDomainService savingsAccountDomainService, LoanAssembler loanAccountAssembler,
            LoanAccountDomainService loanAccountDomainService, SavingsAccountWritePlatformService savingsAccountWritePlatformService,
            AccountTransferDetailRepository accountTransferDetailRepository, LoanReadPlatformService loanReadPlatformService,
            GSIMRepositoy gsimRepository, ConfigurationDomainService configurationDomainService, ExternalIdFactory externalIdFactory,
            FineractProperties fineractProperties, AdvanclySavingsAccountTransactionRepository transactionRepository) {
        return new AdvanclyAccountTransfersWritePlatformService(accountTransfersDataValidator, accountTransferAssembler,
                accountTransferRepository, savingsAccountAssembler, savingsAccountDomainService, loanAccountAssembler,
                loanAccountDomainService, savingsAccountWritePlatformService, accountTransferDetailRepository, loanReadPlatformService,
                gsimRepository, configurationDomainService, externalIdFactory, fineractProperties, transactionRepository);
    }

    /**
     * Creates an instance of the core SavingsAccountDomainServiceJpa so the custom AdvanclySavingsAccountDomainService
     * can delegate non-optimized methods (handleDeposit, handleWithdrawal, etc.) to the core implementation.
     */
    @Bean
    public SavingsAccountDomainServiceJpa coreSavingsAccountDomainService(PlatformSecurityContext context,
            SavingsAccountRepositoryWrapper savingsAccountRepository,
            SavingsAccountTransactionRepository savingsAccountTransactionRepository,
            SavingsAccountTransactionDataValidator savingsAccountTransactionDataValidator,
            JournalEntryWritePlatformService journalEntryWritePlatformService, ConfigurationDomainService configurationDomainService,
            DepositAccountOnHoldTransactionRepository depositAccountOnHoldTransactionRepository,
            BusinessEventNotifierService businessEventNotifierService,
            SavingsAccountTransactionSummaryWrapper savingsAccountTransactionSummaryWrapper, SavingsHelper savingsHelper) {
        return new SavingsAccountDomainServiceJpa(context, savingsAccountRepository, savingsAccountTransactionRepository,
                savingsAccountTransactionDataValidator, journalEntryWritePlatformService, configurationDomainService,
                depositAccountOnHoldTransactionRepository, businessEventNotifierService, savingsAccountTransactionSummaryWrapper,
                savingsHelper);
    }

    /**
     * Creates an instance of the core SavingsAccountWritePlatformServiceJpaRepositoryImpl as a
     * SavingsAccountWritePlatformServiceDelegate bean. This allows the custom WritePlatformService to delegate
     * non-optimized methods (activate, close, charges, etc.) to the core implementation without circular dependency.
     */
    @Bean
    public SavingsAccountWritePlatformServiceDelegate savingsAccountWritePlatformServiceDelegate(PlatformSecurityContext context,
            SavingsAccountDataValidator fromApiJsonDeserializer, SavingsAccountRepositoryWrapper savingAccountRepositoryWrapper,
            StaffRepositoryWrapper staffRepository, SavingsAccountTransactionRepository savingsAccountTransactionRepository,
            SavingsAccountAssembler savingAccountAssembler, SavingsAccountTransactionDataValidator savingsAccountTransactionDataValidator,
            SavingsAccountChargeDataValidator savingsAccountChargeDataValidator,
            PaymentDetailWritePlatformService paymentDetailWritePlatformService,
            JournalEntryWritePlatformService journalEntryWritePlatformService, SavingsAccountDomainService savingsAccountDomainService,
            NoteRepository noteRepository, AccountTransfersReadPlatformService accountTransfersReadPlatformService,
            AccountAssociationsReadPlatformService accountAssociationsReadPlatformService, ChargeRepositoryWrapper chargeRepository,
            SavingsAccountChargeRepositoryWrapper savingsAccountChargeRepository, HolidayRepositoryWrapper holidayRepository,
            WorkingDaysRepositoryWrapper workingDaysRepository, ConfigurationDomainService configurationDomainService,
            DepositAccountOnHoldTransactionRepository depositAccountOnHoldTransactionRepository,
            EntityDatatableChecksWritePlatformService entityDatatableChecksWritePlatformService, AppUserRepositoryWrapper appuserRepository,
            StandingInstructionRepository standingInstructionRepository, BusinessEventNotifierService businessEventNotifierService,
            GSIMRepositoy gsimRepository, SavingsAccountInterestPostingService savingsAccountInterestPostingService,
            SavingsAccrualWritePlatformService savingsAccrualWritePlatformService, ErrorHandler errorHandler) {

        // Create a delegate wrapper around the core implementation
        SavingsAccountWritePlatformServiceJpaRepositoryImpl coreImpl = new SavingsAccountWritePlatformServiceJpaRepositoryImpl(context,
                fromApiJsonDeserializer, savingAccountRepositoryWrapper, staffRepository, savingsAccountTransactionRepository,
                savingAccountAssembler, savingsAccountTransactionDataValidator, savingsAccountChargeDataValidator,
                paymentDetailWritePlatformService, journalEntryWritePlatformService, savingsAccountDomainService, noteRepository,
                accountTransfersReadPlatformService, accountAssociationsReadPlatformService, chargeRepository,
                savingsAccountChargeRepository, holidayRepository, workingDaysRepository, configurationDomainService,
                depositAccountOnHoldTransactionRepository, entityDatatableChecksWritePlatformService, appuserRepository,
                standingInstructionRepository, businessEventNotifierService, gsimRepository, savingsAccountInterestPostingService,
                savingsAccrualWritePlatformService, errorHandler);

        return new CoreSavingsWritePlatformServiceDelegateImpl(coreImpl);
    }
}
