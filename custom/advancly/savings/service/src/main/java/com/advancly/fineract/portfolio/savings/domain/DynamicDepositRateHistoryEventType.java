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
package com.advancly.fineract.portfolio.savings.domain;

/**
 * The principal-changing event that produced a {@link DepositAccountDynamicRateHistory} row - the {@code event_type}
 * column from the implementation plan (Section 4). Transfer-in/transfer-out and adjustment replacement transactions are
 * captured as {@link #DEPOSIT}/{@link #WITHDRAWAL} (plan Section 12: "transfer-in behaves like top-up", "transfer-out
 * behaves like withdrawal") - they reach the same {@code deposit()}/{@code withdraw()} hook as an ordinary
 * top-up/withdrawal, so there is no separate transaction-type signal to distinguish them by at this layer.
 */
public enum DynamicDepositRateHistoryEventType {

    ACCOUNT_ACTIVATION, //
    DEPOSIT, //
    WITHDRAWAL, //
    REVERSAL;
}
