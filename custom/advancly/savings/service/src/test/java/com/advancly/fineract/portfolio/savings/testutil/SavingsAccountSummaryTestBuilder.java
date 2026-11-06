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
import org.apache.fineract.portfolio.savings.domain.SavingsAccountSummary;
import org.springframework.test.util.ReflectionTestUtils;

public class SavingsAccountSummaryTestBuilder {

    private BigDecimal totalDeposits = BigDecimal.ZERO;
    private BigDecimal totalWithdrawals = BigDecimal.ZERO;
    private BigDecimal totalInterestPosted = BigDecimal.ZERO;
    private BigDecimal totalWithdrawalFees = BigDecimal.ZERO;
    private BigDecimal totalFeeCharge = BigDecimal.ZERO;
    private BigDecimal totalPenaltyCharge = BigDecimal.ZERO;
    private BigDecimal totalOverdraftInterestDerived = BigDecimal.ZERO;
    private BigDecimal totalWithholdTax = BigDecimal.ZERO;
    private BigDecimal accountBalance = BigDecimal.ZERO;
    private BigDecimal runningBalanceOnPivotDate = BigDecimal.ZERO;
    private LocalDate interestPostedTillDate;
    private LocalDate lastInterestCalculationDate;

    public SavingsAccountSummaryTestBuilder withAccountBalance(BigDecimal balance) {
        this.accountBalance = balance;
        return this;
    }

    public SavingsAccountSummaryTestBuilder withTotalDeposits(BigDecimal deposits) {
        this.totalDeposits = deposits;
        return this;
    }

    public SavingsAccountSummaryTestBuilder withTotalWithdrawals(BigDecimal withdrawals) {
        this.totalWithdrawals = withdrawals;
        return this;
    }

    public SavingsAccountSummaryTestBuilder withRunningBalanceOnPivotDate(BigDecimal balance) {
        this.runningBalanceOnPivotDate = balance;
        return this;
    }

    public SavingsAccountSummaryTestBuilder withInterestPostedTillDate(LocalDate date) {
        this.interestPostedTillDate = date;
        return this;
    }

    public SavingsAccountSummaryTestBuilder withLastInterestCalculationDate(LocalDate date) {
        this.lastInterestCalculationDate = date;
        return this;
    }

    @SuppressWarnings("unchecked")
    public SavingsAccountSummary build() {
        SavingsAccountSummary summary = createInstance(SavingsAccountSummary.class);
        ReflectionTestUtils.setField(summary, "totalDeposits", totalDeposits);
        ReflectionTestUtils.setField(summary, "totalWithdrawals", totalWithdrawals);
        ReflectionTestUtils.setField(summary, "totalInterestPosted", totalInterestPosted);
        ReflectionTestUtils.setField(summary, "totalWithdrawalFees", totalWithdrawalFees);
        ReflectionTestUtils.setField(summary, "totalFeeCharge", totalFeeCharge);
        ReflectionTestUtils.setField(summary, "totalPenaltyCharge", totalPenaltyCharge);
        ReflectionTestUtils.setField(summary, "totalOverdraftInterestDerived", totalOverdraftInterestDerived);
        ReflectionTestUtils.setField(summary, "totalWithholdTax", totalWithholdTax);
        ReflectionTestUtils.setField(summary, "accountBalance", accountBalance);
        ReflectionTestUtils.setField(summary, "runningBalanceOnInterestPostingTillDate", runningBalanceOnPivotDate);
        ReflectionTestUtils.setField(summary, "interestPostedTillDate", interestPostedTillDate);
        ReflectionTestUtils.setField(summary, "lastInterestCalculationDate", lastInterestCalculationDate);
        return summary;
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
