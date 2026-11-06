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

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.apache.fineract.infrastructure.core.domain.LocalDateInterval;
import org.apache.fineract.infrastructure.core.service.DateUtils;
import org.apache.fineract.portfolio.savings.data.SavingsAccountDynamicRateData;

/**
 * DTO counterpart to {@code DynamicDepositInterestIntervalSplitter}: split scheduled posting periods at Dynamic Deposit
 * rate-history dates without loading {@code DynamicDepositAccount} entities.
 */
final class DynamicDepositScheduledInterestIntervalSplitter {

    private DynamicDepositScheduledInterestIntervalSplitter() {
        //
    }

    static List<RatedInterval> split(final List<LocalDateInterval> corePostingPeriods,
            final List<SavingsAccountDynamicRateData> rateHistoryAscending) {
        final List<RatedInterval> result = new ArrayList<>();
        for (final LocalDateInterval corePeriod : corePostingPeriods) {
            LocalDate subIntervalStart = corePeriod.startDate();
            BigDecimal currentRate = resolveRateAsOf(rateHistoryAscending, subIntervalStart);
            for (final SavingsAccountDynamicRateData row : rateHistoryAscending) {
                final LocalDate changeDate = row.transactionDate();
                final boolean fallsStrictlyInsideRemainderOfPeriod = DateUtils.isAfter(changeDate, subIntervalStart)
                        && !DateUtils.isAfter(changeDate, corePeriod.endDate());
                if (fallsStrictlyInsideRemainderOfPeriod) {
                    result.add(new RatedInterval(LocalDateInterval.create(subIntervalStart, changeDate.minusDays(1)), currentRate));
                    subIntervalStart = changeDate;
                    currentRate = row.resolvedAnnualInterestRate();
                }
            }
            result.add(new RatedInterval(LocalDateInterval.create(subIntervalStart, corePeriod.endDate()), currentRate));
        }
        return result;
    }

    private static BigDecimal resolveRateAsOf(final List<SavingsAccountDynamicRateData> rateHistoryAscending, final LocalDate date) {
        BigDecimal rate = null;
        for (final SavingsAccountDynamicRateData row : rateHistoryAscending) {
            if (!DateUtils.isAfter(row.transactionDate(), date)) {
                rate = row.resolvedAnnualInterestRate();
            } else {
                break;
            }
        }
        if (rate == null) {
            throw new IllegalStateException(
                    "No dynamic deposit rate history found for account as of date " + date + " - cannot resolve interest rate");
        }
        return rate;
    }

    record RatedInterval(LocalDateInterval periodInterval, BigDecimal annualInterestRate) {
    }
}
