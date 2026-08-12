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
package com.advancly.fineract.portfolio.savings.testutil;

import java.lang.reflect.Constructor;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.apache.fineract.organisation.monetary.domain.MonetaryCurrency;
import org.apache.fineract.organisation.office.domain.Office;
import org.apache.fineract.portfolio.client.domain.Client;
import org.apache.fineract.portfolio.savings.domain.SavingsAccount;
import org.apache.fineract.portfolio.savings.domain.SavingsAccountSummary;
import org.apache.fineract.portfolio.savings.domain.SavingsAccountTransaction;
import org.apache.fineract.portfolio.savings.domain.SavingsProduct;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;

public class SavingsAccountTestBuilder {

    private Long id = 1L;
    private BigDecimal nominalAnnualInterestRate = BigDecimal.ZERO;
    private BigDecimal nominalAnnualInterestRateOverdraft = BigDecimal.ZERO;
    private boolean allowOverdraft = false;
    private BigDecimal overdraftLimit = BigDecimal.ZERO;
    private LocalDate activatedOnDate = LocalDate.of(2025, 1, 1);
    private SavingsAccountSummary summary;
    private MonetaryCurrency currency = new MonetaryCurrency("USD", 2, null);
    private BigDecimal minRequiredBalance = BigDecimal.ZERO;
    private BigDecimal onHoldFunds = BigDecimal.ZERO;
    private BigDecimal savingsOnHoldAmount = BigDecimal.ZERO;
    private boolean enforceMinRequiredBalance = false;
    private List<SavingsAccountTransaction> transactions = new ArrayList<>();

    public SavingsAccountTestBuilder withId(Long id) {
        this.id = id;
        return this;
    }

    public SavingsAccountTestBuilder withInterestRate(BigDecimal rate) {
        this.nominalAnnualInterestRate = rate;
        return this;
    }

    public SavingsAccountTestBuilder withOverdraftInterestRate(BigDecimal rate) {
        this.nominalAnnualInterestRateOverdraft = rate;
        this.allowOverdraft = true;
        return this;
    }

    public SavingsAccountTestBuilder withActivationDate(LocalDate date) {
        this.activatedOnDate = date;
        return this;
    }

    public SavingsAccountTestBuilder withSummary(SavingsAccountSummary summary) {
        this.summary = summary;
        return this;
    }

    public SavingsAccountTestBuilder withMinRequiredBalance(BigDecimal balance) {
        this.minRequiredBalance = balance;
        this.enforceMinRequiredBalance = true;
        return this;
    }

    public SavingsAccountTestBuilder withTransactions(List<SavingsAccountTransaction> txns) {
        this.transactions = txns;
        return this;
    }

    public SavingsAccountTestBuilder withOverdraft(BigDecimal limit) {
        this.allowOverdraft = true;
        this.overdraftLimit = limit;
        return this;
    }

    public SavingsAccountTestBuilder withOnHoldFunds(BigDecimal amount) {
        this.onHoldFunds = amount;
        return this;
    }

    public SavingsAccountTestBuilder withSavingsOnHoldAmount(BigDecimal amount) {
        this.savingsOnHoldAmount = amount;
        return this;
    }

    public SavingsAccount build() {
        SavingsAccount account = createInstance(SavingsAccount.class);
        ReflectionTestUtils.setField(account, "id", id);
        ReflectionTestUtils.setField(account, "nominalAnnualInterestRate", nominalAnnualInterestRate);
        ReflectionTestUtils.setField(account, "nominalAnnualInterestRateOverdraft", nominalAnnualInterestRateOverdraft);
        ReflectionTestUtils.setField(account, "allowOverdraft", allowOverdraft);
        ReflectionTestUtils.setField(account, "overdraftLimit", overdraftLimit);
        ReflectionTestUtils.setField(account, "activatedOnDate", activatedOnDate);
        ReflectionTestUtils.setField(account, "currency", currency);
        ReflectionTestUtils.setField(account, "minRequiredBalance", minRequiredBalance);
        ReflectionTestUtils.setField(account, "enforceMinRequiredBalance", enforceMinRequiredBalance);
        if (summary == null) {
            summary = new SavingsAccountSummaryTestBuilder().withAccountBalance(BigDecimal.valueOf(1000))
                    .withTotalDeposits(BigDecimal.valueOf(1000)).build();
        }
        ReflectionTestUtils.setField(account, "summary", summary);
        ReflectionTestUtils.setField(account, "savingsAccountTransactions", transactions);
        ReflectionTestUtils.setField(account, "onHoldFunds", onHoldFunds);
        ReflectionTestUtils.setField(account, "savingsOnHoldAmount", savingsOnHoldAmount);

        // Set client with office mock for office() method
        Office office = Mockito.mock(Office.class);
        Mockito.lenient().when(office.getId()).thenReturn(1L);
        Client client = Mockito.mock(Client.class);
        Mockito.lenient().when(client.getOffice()).thenReturn(office);
        Mockito.lenient().when(client.officeId()).thenReturn(1L);
        Mockito.lenient().when(client.getId()).thenReturn(1L);
        ReflectionTestUtils.setField(account, "client", client);

        // Set product mock for journal entries
        SavingsProduct product = Mockito.mock(SavingsProduct.class);
        Mockito.lenient().when(product.getId()).thenReturn(1L);
        ReflectionTestUtils.setField(account, "product", product);

        return account;
    }

    private static <T> T createInstance(Class<T> clazz) {
        try {
            Constructor<T> constructor = clazz.getDeclaredConstructor();
            constructor.setAccessible(true);
            return constructor.newInstance();
        } catch (Exception e) {
            throw new RuntimeException("Failed to create instance of " + clazz.getName(), e);
        }
    }
}
