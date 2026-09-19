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

import com.advancly.fineract.portfolio.savings.service.AdvanclySavingsSchedularInterestPoster;
import com.advancly.fineract.portfolio.savings.service.AdvanclySavingsSchedularInterestPosterTask;
import com.advancly.fineract.portfolio.savings.service.DynamicDepositScheduledRateHistoryReadPlatformService;
import org.apache.fineract.accounting.journalentry.service.JournalEntryWritePlatformService;
import org.apache.fineract.accounting.producttoaccountmapping.service.ProductToGLAccountMappingWritePlatformService;
import org.apache.fineract.infrastructure.configuration.domain.ConfigurationDomainService;
import org.apache.fineract.infrastructure.core.config.FineractProperties;
import org.apache.fineract.infrastructure.core.exception.ErrorHandler;
import org.apache.fineract.infrastructure.core.service.ExternalIdFactory;
import org.apache.fineract.infrastructure.dataqueries.service.EntityDatatableChecksWritePlatformService;
import org.apache.fineract.infrastructure.entityaccess.service.FineractEntityAccessUtil;
import org.apache.fineract.infrastructure.event.business.service.BusinessEventNotifierService;
import org.apache.fineract.infrastructure.security.service.PlatformSecurityContext;
import org.apache.fineract.organisation.holiday.domain.HolidayRepositoryWrapper;
import org.apache.fineract.organisation.monetary.domain.ApplicationCurrencyRepositoryWrapper;
import org.apache.fineract.organisation.staff.domain.StaffRepositoryWrapper;
import org.apache.fineract.organisation.workingdays.domain.WorkingDaysRepositoryWrapper;
import org.apache.fineract.portfolio.account.data.AccountTransfersDataValidator;
import org.apache.fineract.portfolio.account.domain.AccountTransferAssembler;
import org.apache.fineract.portfolio.account.domain.AccountTransferDetailRepository;
import org.apache.fineract.portfolio.account.domain.AccountTransferRepository;
import org.apache.fineract.portfolio.account.domain.StandingInstructionRepository;
import org.apache.fineract.portfolio.account.service.AccountAssociationsReadPlatformService;
import org.apache.fineract.portfolio.account.service.AccountTransfersReadPlatformService;
import org.apache.fineract.portfolio.account.service.AccountTransfersWritePlatformServiceImpl;
import org.apache.fineract.portfolio.charge.domain.ChargeRepositoryWrapper;
import org.apache.fineract.portfolio.loanaccount.domain.LoanAccountDomainService;
import org.apache.fineract.portfolio.loanaccount.service.LoanAssembler;
import org.apache.fineract.portfolio.loanaccount.service.LoanReadPlatformService;
import org.apache.fineract.portfolio.note.domain.NoteRepository;
import org.apache.fineract.portfolio.paymentdetail.service.PaymentDetailWritePlatformService;
import org.apache.fineract.portfolio.savings.data.SavingsAccountChargeDataValidator;
import org.apache.fineract.portfolio.savings.data.SavingsAccountDataValidator;
import org.apache.fineract.portfolio.savings.data.SavingsAccountTransactionDataValidator;
import org.apache.fineract.portfolio.savings.data.SavingsProductDataValidator;
import org.apache.fineract.portfolio.savings.domain.DepositAccountOnHoldTransactionRepository;
import org.apache.fineract.portfolio.savings.domain.GSIMRepositoy;
import org.apache.fineract.portfolio.savings.domain.SavingsAccountAssembler;
import org.apache.fineract.portfolio.savings.domain.SavingsAccountChargeRepositoryWrapper;
import org.apache.fineract.portfolio.savings.domain.SavingsAccountDomainServiceJpa;
import org.apache.fineract.portfolio.savings.domain.SavingsAccountRepositoryWrapper;
import org.apache.fineract.portfolio.savings.domain.SavingsAccountTransactionRepository;
import org.apache.fineract.portfolio.savings.domain.SavingsProductAssembler;
import org.apache.fineract.portfolio.savings.domain.SavingsProductRepository;
import org.apache.fineract.portfolio.savings.service.SavingsAccountDomainService;
import org.apache.fineract.portfolio.savings.service.SavingsAccountInterestPostingService;
import org.apache.fineract.portfolio.savings.service.SavingsAccountReadPlatformService;
import org.apache.fineract.portfolio.savings.service.SavingsAccountWritePlatformService;
import org.apache.fineract.portfolio.savings.service.SavingsAccountWritePlatformServiceJpaRepositoryImpl;
import org.apache.fineract.portfolio.savings.service.SavingsProductWritePlatformServiceJpaRepositoryImpl;
import org.apache.fineract.useradministration.domain.AppUserRepositoryWrapper;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Scope;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;

@AutoConfiguration
@ComponentScan({ "com.advancly.fineract.portfolio.savings", "com.advancly.fineract.portfolio.account" })
@EnableJpaRepositories(basePackages = "com.advancly.fineract.portfolio.savings")
@ConditionalOnProperty("advancly.savings.optimization.enabled")
public class AdvanclySavingsAutoConfiguration {

    @Bean("coreAccountTransfersWritePlatformService")
    public AccountTransfersWritePlatformServiceImpl coreAccountTransfersWritePlatformService(
            AccountTransfersDataValidator accountTransfersDataValidator, AccountTransferAssembler accountTransferAssembler,
            AccountTransferRepository accountTransferRepository, SavingsAccountAssembler savingsAccountAssembler,
            @org.springframework.beans.factory.annotation.Qualifier("coreSavingsAccountDomainService") SavingsAccountDomainService savingsAccountDomainService,
            LoanAssembler loanAccountAssembler, LoanAccountDomainService loanAccountDomainService,
            @org.springframework.beans.factory.annotation.Qualifier("coreSavingsAccountWritePlatformService") SavingsAccountWritePlatformService savingsAccountWritePlatformService,
            AccountTransferDetailRepository accountTransferDetailRepository, LoanReadPlatformService loanReadPlatformService,
            GSIMRepositoy gsimRepository, ConfigurationDomainService configurationDomainService, ExternalIdFactory externalIdFactory,
            FineractProperties fineractProperties) {
        return new AccountTransfersWritePlatformServiceImpl(accountTransfersDataValidator, accountTransferAssembler,
                accountTransferRepository, savingsAccountAssembler, savingsAccountDomainService, loanAccountAssembler,
                loanAccountDomainService, savingsAccountWritePlatformService, accountTransferDetailRepository, loanReadPlatformService,
                gsimRepository, configurationDomainService, externalIdFactory, fineractProperties);
    }

    /**
     * Creates an instance of the core SavingsAccountDomainServiceJpa so the custom AdvanclySavingsAccountDomainService
     * can delegate non-optimized methods (handleDeposit, handleWithdrawal, etc.) to the core implementation.
     */
    @Bean("coreSavingsAccountDomainService")
    public SavingsAccountDomainService coreSavingsAccountDomainService(SavingsAccountRepositoryWrapper savingsAccountRepository,
            SavingsAccountTransactionRepository savingsAccountTransactionRepository,
            ApplicationCurrencyRepositoryWrapper applicationCurrencyRepositoryWrapper,
            JournalEntryWritePlatformService journalEntryWritePlatformService, ConfigurationDomainService configurationDomainService,
            PlatformSecurityContext context, DepositAccountOnHoldTransactionRepository depositAccountOnHoldTransactionRepository,
            BusinessEventNotifierService businessEventNotifierService) {
        return new SavingsAccountDomainServiceJpa(savingsAccountRepository, savingsAccountTransactionRepository,
                applicationCurrencyRepositoryWrapper, journalEntryWritePlatformService, configurationDomainService, context,
                depositAccountOnHoldTransactionRepository, businessEventNotifierService);
    }

    /**
     * Creates an instance of the core SavingsAccountWritePlatformServiceJpaRepositoryImpl. This allows the custom
     * WritePlatformService to delegate non-optimized methods (activate, close, charges, etc.) to the core
     * implementation.
     */
    @Bean("coreSavingsAccountWritePlatformService")
    public SavingsAccountWritePlatformServiceJpaRepositoryImpl coreSavingsAccountWritePlatformService(PlatformSecurityContext context,
            SavingsAccountDataValidator fromApiJsonDeserializer, SavingsAccountRepositoryWrapper savingAccountRepositoryWrapper,
            StaffRepositoryWrapper staffRepository, SavingsAccountTransactionRepository savingsAccountTransactionRepository,
            SavingsAccountAssembler savingAccountAssembler, SavingsAccountTransactionDataValidator savingsAccountTransactionDataValidator,
            SavingsAccountChargeDataValidator savingsAccountChargeDataValidator,
            PaymentDetailWritePlatformService paymentDetailWritePlatformService,
            JournalEntryWritePlatformService journalEntryWritePlatformService,
            @org.springframework.beans.factory.annotation.Qualifier("coreSavingsAccountDomainService") SavingsAccountDomainService savingsAccountDomainService,
            NoteRepository noteRepository, AccountTransfersReadPlatformService accountTransfersReadPlatformService,
            AccountAssociationsReadPlatformService accountAssociationsReadPlatformService, ChargeRepositoryWrapper chargeRepository,
            SavingsAccountChargeRepositoryWrapper savingsAccountChargeRepository, HolidayRepositoryWrapper holidayRepository,
            WorkingDaysRepositoryWrapper workingDaysRepository, ConfigurationDomainService configurationDomainService,
            DepositAccountOnHoldTransactionRepository depositAccountOnHoldTransactionRepository,
            EntityDatatableChecksWritePlatformService entityDatatableChecksWritePlatformService, AppUserRepositoryWrapper appuserRepository,
            StandingInstructionRepository standingInstructionRepository, BusinessEventNotifierService businessEventNotifierService,
            GSIMRepositoy gsimRepository, SavingsAccountInterestPostingService savingsAccountInterestPostingService,
            ErrorHandler errorHandler) {

        return new SavingsAccountWritePlatformServiceJpaRepositoryImpl(context, fromApiJsonDeserializer, savingAccountRepositoryWrapper,
                staffRepository, savingsAccountTransactionRepository, savingAccountAssembler, savingsAccountTransactionDataValidator,
                savingsAccountChargeDataValidator, paymentDetailWritePlatformService, journalEntryWritePlatformService,
                savingsAccountDomainService, noteRepository, accountTransfersReadPlatformService, accountAssociationsReadPlatformService,
                chargeRepository, savingsAccountChargeRepository, holidayRepository, workingDaysRepository, configurationDomainService,
                depositAccountOnHoldTransactionRepository, entityDatatableChecksWritePlatformService, appuserRepository,
                standingInstructionRepository, businessEventNotifierService, gsimRepository, savingsAccountInterestPostingService,
                errorHandler);
    }

    /**
     * Creates an instance of the core SavingsProductWritePlatformServiceJpaRepositoryImpl so the custom
     * AdvanclySavingsProductWritePlatformService can delegate the actual product create/update to the core
     * implementation before layering on early-withdrawal-charge reconciliation.
     */
    @Bean("coreSavingsProductWritePlatformService")
    public SavingsProductWritePlatformServiceJpaRepositoryImpl coreSavingsProductWritePlatformService(PlatformSecurityContext context,
            SavingsProductRepository savingProductRepository, SavingsProductDataValidator fromApiJsonDataValidator,
            SavingsProductAssembler savingsProductAssembler,
            ProductToGLAccountMappingWritePlatformService accountMappingWritePlatformService,
            FineractEntityAccessUtil fineractEntityAccessUtil) {
        return new SavingsProductWritePlatformServiceJpaRepositoryImpl(context, savingProductRepository, fromApiJsonDataValidator,
                savingsProductAssembler, accountMappingWritePlatformService, fineractEntityAccessUtil);
    }

    /**
     * Registers {@link AdvanclySavingsSchedularInterestPoster} as a {@code SavingsSchedularInterestPoster} bean.
     *
     * <p>
     * Note on {@code @ConditionalOnMissingBean}: core's own {@code savingsSchedularInterestPoster()} bean method in
     * {@code SavingsConfiguration} is guarded by
     * {@code @ConditionalOnMissingBean(SavingsSchedularInterestPoster.class)}, but {@code SavingsConfiguration} is a
     * plain, eagerly component-scanned {@code @Configuration} (picked up by
     * {@code @ComponentScan(basePackages = "org.apache.fineract.**")}), whereas this class is a deferred
     * {@code @AutoConfiguration}. Spring registers bean definitions from eagerly-scanned configuration classes before
     * it processes deferred auto-configuration imports, so by the time core's condition is evaluated, this bean does
     * not exist yet - the condition is satisfied regardless, and core's bean gets registered too. This was verified
     * empirically (a throwaway {@code ApplicationContextRunner} probe reproducing this exact eager-vs-deferred
     * relationship showed both beans present). {@code @Primary} is therefore required so that any by-type resolution -
     * including {@code applicationContext.getBean(...)} - deterministically picks this bean over core's orphaned one,
     * exactly like every other override in this class ({@code AdvanclySavingsAccountWritePlatformService},
     * {@code AdvanclySavingsAccountDomainService}, etc.) already does.
     *
     * <p>
     * Task 7 gave this class zero behavior change from core (it just called {@code super.postInterest()}); Task 8 has
     * since added the actual per-period-charge application logic on top of it (see
     * {@link AdvanclySavingsSchedularInterestPoster#postInterest()}), which is why its constructor now also takes the
     * two extra repositories below. Dynamic Deposit scheduled posting now also uses the same DTO/JDBC batch path; the
     * poster only needs a bulk rate-history reader for the current page of accounts.
     */
    @Bean
    @Primary
    @Scope("prototype")
    public AdvanclySavingsSchedularInterestPoster advanclySavingsSchedularInterestPoster(
            SavingsAccountWritePlatformService savingsAccountWritePlatformService, JdbcTemplate jdbcTemplate,
            SavingsAccountReadPlatformService savingsAccountReadPlatformService, PlatformSecurityContext platformSecurityContext,
            com.advancly.fineract.portfolio.savings.domain.SavingsAccountInterestChargeRepository interestChargeRepository,
            SavingsAccountTransactionRepository savingsAccountTransactionRepository,
            DynamicDepositScheduledRateHistoryReadPlatformService dynamicRateHistoryReadPlatformService) {
        return new AdvanclySavingsSchedularInterestPoster(savingsAccountWritePlatformService, jdbcTemplate,
                savingsAccountReadPlatformService, platformSecurityContext, interestChargeRepository, savingsAccountTransactionRepository,
                dynamicRateHistoryReadPlatformService);
    }

    /**
     * Registers {@link AdvanclySavingsSchedularInterestPosterTask} as a {@code SavingsSchedularInterestPosterTask}
     * bean, wrapping the {@link AdvanclySavingsSchedularInterestPoster} bean above.
     *
     * <p>
     * {@code @Primary} is required here for the same reason as on the poster bean above: core's own
     * {@code savingsSchedularInterestPosterTask()} bean in {@code SavingsConfiguration} also ends up registered (its
     * {@code @ConditionalOnMissingBean} check runs before this deferred auto-configuration exists), so without
     * {@code @Primary} here, {@code PostInterestForSavingTasklet}'s
     * {@code applicationContext.getBean(SavingsSchedularInterestPosterTask.class)} would be ambiguous between the two
     * and throw {@code NoUniqueBeanDefinitionException} the first time the job runs. With {@code @Primary}, that lookup
     * deterministically resolves to this bean - no change needed to {@code PostInterestForSavingTasklet} itself.
     */
    @Bean
    @Primary
    @Scope("prototype")
    public AdvanclySavingsSchedularInterestPosterTask advanclySavingsSchedularInterestPosterTask(
            AdvanclySavingsSchedularInterestPoster interestPoster) {
        return new AdvanclySavingsSchedularInterestPosterTask(interestPoster);
    }
}
