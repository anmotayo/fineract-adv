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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import com.advancly.fineract.portfolio.savings.data.BulkTransactionDataValidator;
import com.advancly.fineract.portfolio.savings.domain.AdvanclySavingsAccountAssembler;
import com.advancly.fineract.portfolio.savings.domain.AdvanclySavingsAccountTransactionRepository;
import com.advancly.fineract.portfolio.savings.domain.AssembledSavingsAccount;
import com.advancly.fineract.portfolio.savings.testutil.MoneyHelperInitializer;
import com.advancly.fineract.portfolio.savings.testutil.SavingsAccountTestBuilder;
import com.advancly.fineract.portfolio.savings.testutil.SavingsAccountTransactionTestBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;
import java.util.Optional;
import org.apache.fineract.infrastructure.core.api.JsonCommand;
import org.apache.fineract.infrastructure.core.data.CommandProcessingResult;
import org.apache.fineract.infrastructure.core.serialization.FromJsonHelper;
import org.apache.fineract.infrastructure.security.service.PlatformSecurityContext;
import org.apache.fineract.organisation.monetary.domain.Money;
import org.apache.fineract.portfolio.note.domain.NoteRepository;
import org.apache.fineract.portfolio.paymentdetail.domain.PaymentDetail;
import org.apache.fineract.portfolio.paymentdetail.domain.PaymentDetailRepository;
import org.apache.fineract.portfolio.paymentdetail.service.PaymentDetailWritePlatformService;
import org.apache.fineract.portfolio.paymenttype.domain.PaymentType;
import org.apache.fineract.portfolio.paymenttype.domain.PaymentTypeRepositoryWrapper;
import org.apache.fineract.portfolio.savings.data.SavingsAccountTransactionDataValidator;
import org.apache.fineract.portfolio.savings.domain.GSIMRepositoy;
import org.apache.fineract.portfolio.savings.domain.SavingsAccount;
import org.apache.fineract.portfolio.savings.domain.SavingsAccountTransaction;
import org.apache.fineract.portfolio.savings.service.SavingsAccountWritePlatformServiceJpaRepositoryImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AdvanclySavingsAccountWritePlatformServiceBulkTest {

    @Mock
    private PlatformSecurityContext context;
    @Mock
    private SavingsAccountTransactionDataValidator savingsAccountTransactionDataValidator;
    @Mock
    private AdvanclySavingsAccountAssembler assembler;
    @Mock
    private AdvanclySavingsAccountDomainService domainService;
    @Mock
    private AdvanclySavingsAccountTransactionRepository advanclyTransactionRepository;
    @Mock
    private PaymentDetailWritePlatformService paymentDetailWritePlatformService;
    @Mock
    private NoteRepository noteRepository;
    @Mock
    private GSIMRepositoy gsimRepository;
    @Mock
    private SavingsAccountWritePlatformServiceJpaRepositoryImpl delegate;
    @Mock
    private PaymentTypeRepositoryWrapper paymentTypeRepositoryWrapper;
    @Mock
    private PaymentDetailRepository paymentDetailRepository;

    private AdvanclySavingsAccountWritePlatformService service;
    private FromJsonHelper fromJsonHelper;
    private BulkTransactionDataValidator bulkValidator;

    @BeforeEach
    void setUp() {
        MoneyHelperInitializer.initialize();
        fromJsonHelper = new FromJsonHelper();
        bulkValidator = new BulkTransactionDataValidator(fromJsonHelper);
        service = new AdvanclySavingsAccountWritePlatformService(context, savingsAccountTransactionDataValidator, assembler, domainService,
                advanclyTransactionRepository, paymentDetailWritePlatformService, noteRepository, gsimRepository, delegate, bulkValidator,
                fromJsonHelper, paymentTypeRepositoryWrapper, paymentDetailRepository);
    }

    @Test
    void testBulkTransaction_twoDeposits_returnsReceiptToIdMap() {
        Long savingsId = 1L;
        SavingsAccount account = new SavingsAccountTestBuilder().withId(savingsId).build();
        AssembledSavingsAccount assembled = new AssembledSavingsAccount(account, null);

        when(advanclyTransactionRepository.findLastTransactionDate(savingsId)).thenReturn(Optional.empty());
        when(assembler.assembleForAppendPath(savingsId)).thenReturn(assembled);

        PaymentType paymentType = Mockito.mock(PaymentType.class);
        when(paymentTypeRepositoryWrapper.findOneWithNotFoundDetection(1L)).thenReturn(paymentType);
        PaymentDetail paymentDetail = Mockito.mock(PaymentDetail.class);
        when(paymentDetailRepository.saveAndFlush(any(PaymentDetail.class))).thenReturn(paymentDetail);

        SavingsAccountTransaction txn1 = new SavingsAccountTransactionTestBuilder().withId(101L)
                .withRunningBalance(BigDecimal.valueOf(5000)).build();
        SavingsAccountTransaction txn2 = new SavingsAccountTransactionTestBuilder().withId(102L)
                .withRunningBalance(BigDecimal.valueOf(7000)).build();

        when(domainService.handleDepositOptimized(eq(account), eq(LocalDate.of(2026, 4, 5)), eq(BigDecimal.valueOf(5000)), any(),
                any(Money.class), any(), any())).thenReturn(txn1);
        when(domainService.handleDepositOptimized(eq(account), eq(LocalDate.of(2026, 4, 5)), eq(BigDecimal.valueOf(2000)), any(),
                any(Money.class), any(), any())).thenReturn(txn2);

        String json = buildBulkPayload("deposit", "REC-001", 5000, "Deposit for rent", "deposit", "REC-002", 2000, "Deposit for bills");
        JsonCommand command = JsonCommand.fromExistingCommand(null, json, JsonParser.parseString(json), fromJsonHelper, null, null, null,
                null, null, null, savingsId, null, null, null, null, null, null, null);

        CommandProcessingResult result = service.bulkTransaction(savingsId, command);

        @SuppressWarnings("unchecked")
        Map<String, Long> txnIds = (Map<String, Long>) result.getChanges().get("transactionIds");
        assertThat(txnIds).containsEntry("REC-001", 101L).containsEntry("REC-002", 102L);
    }

    private String buildBulkPayload(String type1, String receipt1, int amount1, String note1, String type2, String receipt2, int amount2,
            String note2) {
        JsonObject payload = new JsonObject();
        payload.addProperty("dateFormat", "dd MMMM yyyy");
        payload.addProperty("locale", "en");

        JsonArray txns = new JsonArray();
        txns.add(buildTransactionObject(type1, receipt1, amount1, note1));
        txns.add(buildTransactionObject(type2, receipt2, amount2, note2));

        payload.add("transactions", txns);
        return payload.toString();
    }

    private JsonObject buildTransactionObject(String type, String receipt, int amount, String note) {
        JsonObject txn = new JsonObject();
        txn.addProperty("type", type);
        txn.addProperty("transactionDate", "05 April 2026");
        txn.addProperty("transactionAmount", amount);
        txn.addProperty("paymentTypeId", 1);
        txn.addProperty("receiptNumber", receipt);
        txn.addProperty("note", note);
        return txn;
    }
}
