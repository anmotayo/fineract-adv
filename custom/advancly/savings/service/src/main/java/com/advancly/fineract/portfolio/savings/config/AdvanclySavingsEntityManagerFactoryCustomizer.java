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
package com.advancly.fineract.portfolio.savings.config;

import java.util.Set;
import org.apache.fineract.infrastructure.core.config.jpa.EntityManagerFactoryCustomizer;
import org.springframework.stereotype.Component;

/**
 * Registers {@code com.advancly.fineract.portfolio.savings.domain} (where the new Dynamic Deposit JPA entities live) as
 * an additional package for the core {@code JPAConfig} to scan, since it only scans {@code org.apache.fineract} by
 * default. Being an {@code @Component} under the base-package that {@code AdvanclySavingsAutoConfiguration} already
 * {@code @ComponentScan}s, it is picked up automatically - no further wiring is required.
 */
@Component
public class AdvanclySavingsEntityManagerFactoryCustomizer implements EntityManagerFactoryCustomizer {

    @Override
    public Set<String> additionalPackagesToScan() {
        return Set.of("com.advancly.fineract.portfolio.savings.domain");
    }
}
