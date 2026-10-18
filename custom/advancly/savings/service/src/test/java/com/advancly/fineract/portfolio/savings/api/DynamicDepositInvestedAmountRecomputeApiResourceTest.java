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
package com.advancly.fineract.portfolio.savings.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.advancly.fineract.portfolio.savings.service.DynamicDepositInvestedAmountRecomputeService;
import java.util.List;
import org.apache.fineract.infrastructure.core.exception.GeneralPlatformDomainRuleException;
import org.apache.fineract.infrastructure.core.serialization.DefaultToApiJsonSerializer;
import org.apache.fineract.infrastructure.core.serialization.FromJsonHelper;
import org.apache.fineract.infrastructure.security.service.PlatformSecurityContext;
import org.apache.fineract.useradministration.domain.AppUser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class DynamicDepositInvestedAmountRecomputeApiResourceTest {

    private AppUser user;
    private DynamicDepositInvestedAmountRecomputeService service;
    private DynamicDepositInvestedAmountRecomputeApiResource resource;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        final PlatformSecurityContext context = mock(PlatformSecurityContext.class);
        this.user = mock(AppUser.class);
        when(context.authenticatedUser()).thenReturn(this.user);
        this.service = mock(DynamicDepositInvestedAmountRecomputeService.class);
        when(this.service.run(anyCollection(), anyBoolean()))
                .thenReturn(new DynamicDepositInvestedAmountRecomputeService.Result(true, 0, 0, List.of()));
        final DefaultToApiJsonSerializer<DynamicDepositInvestedAmountRecomputeService.Result> serializer = mock(
                DefaultToApiJsonSerializer.class);
        when(serializer.serialize(org.mockito.ArgumentMatchers.<DynamicDepositInvestedAmountRecomputeService.Result>any()))
                .thenReturn("{}");
        this.resource = new DynamicDepositInvestedAmountRecomputeApiResource(context, this.service, serializer, new FromJsonHelper());
    }

    @Test
    void anEmptyBodyIsADryRunOfEveryAccount() {
        this.resource.recompute("");

        verify(this.service).run(List.of(), true);
    }

    @Test
    void nothingIsChangedUnlessDryRunIsExplicitlyFalse() {
        this.resource.recompute("{\"accountIds\":[4]}");
        verify(this.service).run(List.of(4L), true);

        this.resource.recompute("{\"dryRun\":false,\"accountIds\":[4,5]}");
        verify(this.service).run(List.of(4L, 5L), false);
    }

    @Test
    void itNeedsTheAdministrativePermissionAndDoesNothingWithout() {
        doThrow(new GeneralPlatformDomainRuleException("error.msg.no.permission", "no")).when(this.user)
                .validateHasPermissionTo(DynamicDepositInvestedAmountRecomputeApiResource.PERMISSION);

        assertThatThrownBy(() -> this.resource.recompute("{\"dryRun\":false}")).isInstanceOf(GeneralPlatformDomainRuleException.class);

        verify(this.service, never()).run(anyCollection(), anyBoolean());
    }

    @Test
    void thePermissionIsCheckedBeforeTheBodyIsRead() {
        this.resource.recompute("{\"dryRun\":false}");

        final ArgumentCaptor<String> permission = ArgumentCaptor.forClass(String.class);
        verify(this.user).validateHasPermissionTo(permission.capture());
        assertThat(permission.getValue()).isEqualTo("RECOMPUTE_INVESTEDAMOUNT_DYNAMICDEPOSITACCOUNT");
    }
}
