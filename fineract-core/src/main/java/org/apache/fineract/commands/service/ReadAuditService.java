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
package org.apache.fineract.commands.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.fineract.commands.domain.CommandSource;
import org.apache.fineract.commands.domain.CommandSourceRepository;
import org.apache.fineract.infrastructure.core.domain.ExternalId;
import org.apache.fineract.infrastructure.security.service.PlatformSecurityContext;
import org.apache.fineract.useradministration.domain.AppUser;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Service for auditing READ operations on resources.
 * <p>
 * This service logs read access to the m_portfolio_command_source table when the authenticated user has read auditing
 * enabled. Auditing is performed when:
 * <ul>
 * <li>enableReadAudit is explicitly true</li>
 * </ul>
 * <p>
 * Auditing is skipped when enableReadAudit is false or null.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ReadAuditService {

    private final PlatformSecurityContext securityContext;
    private final CommandSourceRepository commandSourceRepository;

    /**
     * Audits a READ operation for the specified entity and resource with external ID.
     *
     * @param entityName
     *            the name of the entity being read (e.g., "CLIENT", "LOAN", "SAVINGSACCOUNT")
     * @param resourceId
     *            the ID of the resource being read
     * @param resourceExternalId
     *            the external ID of the resource being read (optional)
     */
    @Transactional
    public void auditRead(final String entityName, final Long resourceId, final ExternalId resourceExternalId) {
        try {
            AppUser user = securityContext.authenticatedUser();

            // Only audit if enableReadAudit is explicitly true
            if (Boolean.TRUE.equals(user.getEnableReadAudit())) {
                CommandSource readAudit = CommandSource.readAuditEntry(entityName, resourceId, resourceExternalId, user);
                commandSourceRepository.save(readAudit);
            }
        } catch (Exception e) {
            // Fail silently - log the error but don't disrupt the read operation
            log.warn("Failed to audit READ operation for entity {} with resourceId {}: {}", entityName, resourceId, e.getMessage());
        }
    }
}
