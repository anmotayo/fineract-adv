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
package com.advancly.fineract.portfolio.savings.data;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * One row of the interest-calculation preview's posting-period breakdown - the interest earned within a single
 * compounding/posting period boundary, as computed by {@code SavingsAccount.calculateInterestUsing(...)}.
 */
public class PostingPeriodData implements Serializable {

    private final LocalDate fromDate;
    private final LocalDate toDate;
    private final BigDecimal interestEarned;

    public PostingPeriodData(final LocalDate fromDate, final LocalDate toDate, final BigDecimal interestEarned) {
        this.fromDate = fromDate;
        this.toDate = toDate;
        this.interestEarned = interestEarned;
    }

    public LocalDate fromDate() {
        return this.fromDate;
    }

    public LocalDate toDate() {
        return this.toDate;
    }

    public BigDecimal interestEarned() {
        return this.interestEarned;
    }
}
