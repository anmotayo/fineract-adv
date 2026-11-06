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

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.fineract.infrastructure.core.domain.JdbcSupport;
import org.apache.fineract.portfolio.savings.data.SavingsAccountDynamicRateData;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class DynamicDepositScheduledRateHistoryReadPlatformService {

    private final JdbcTemplate jdbcTemplate;

    public DynamicDepositScheduledRateHistoryReadPlatformService(final JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Map<Long, List<SavingsAccountDynamicRateData>> retrieveByAccountIds(final Collection<Long> accountIds) {
        if (accountIds == null || accountIds.isEmpty()) {
            return Collections.emptyMap();
        }

        final String placeholders = String.join(",", Collections.nCopies(accountIds.size(), "?"));
        final String sql = "select h.savings_account_id as accountId, h.transaction_date as transactionDate, "
                + "h.resolved_annual_interest_rate as resolvedAnnualInterestRate " + "from m_deposit_account_dynamic_rate_history h "
                + "join m_savings_account_transaction tr on tr.id = h.savings_account_transaction_id " + "where h.savings_account_id in ("
                + placeholders + ") " + "and tr.is_reversed = false and (tr.is_reversal = false or tr.is_reversal is null) "
                + "order by h.savings_account_id, h.transaction_date, h.id";

        return this.jdbcTemplate.query(sql, ps -> {
            int index = 1;
            for (final Long accountId : accountIds) {
                ps.setLong(index++, accountId);
            }
        }, this::extractRows);
    }

    private Map<Long, List<SavingsAccountDynamicRateData>> extractRows(final ResultSet rs) throws SQLException {
        final Map<Long, List<SavingsAccountDynamicRateData>> result = new LinkedHashMap<>();
        while (rs.next()) {
            final Long accountId = rs.getLong("accountId");
            result.computeIfAbsent(accountId, key -> new ArrayList<>()).add(new SavingsAccountDynamicRateData(accountId,
                    JdbcSupport.getLocalDate(rs, "transactionDate"), rs.getBigDecimal("resolvedAnnualInterestRate")));
        }
        return result;
    }
}
