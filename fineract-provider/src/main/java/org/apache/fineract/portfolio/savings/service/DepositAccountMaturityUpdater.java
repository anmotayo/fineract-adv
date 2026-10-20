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
package org.apache.fineract.portfolio.savings.service;

import org.apache.fineract.portfolio.savings.DepositAccountType;

/**
 * Lets a module bring a deposit account type that core does not know into the "Update Deposit Accounts Maturity
 * Details" job: the job hands every matured account of a supported type to the updater instead of looking for a Fixed
 * or Recurring Deposit.
 */
public interface DepositAccountMaturityUpdater {

    boolean supports(DepositAccountType depositAccountType);

    /**
     * Moves one account whose maturity date has been reached to its matured state. Runs inside the job's transaction
     * for that account; a failure is logged against that account and does not stop the others.
     */
    void updateMaturity(Long depositAccountId);
}
