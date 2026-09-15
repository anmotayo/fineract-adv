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
package com.advancly.fineract.portfolio.savings.handler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.apache.fineract.infrastructure.core.api.JsonCommand;
import org.apache.fineract.infrastructure.core.data.CommandProcessingResult;
import org.apache.fineract.portfolio.savings.service.SavingsAccountWritePlatformService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class DynamicDepositAccountCommandHandlerTest {

    private static final Long SAVINGS_ID = 42L;
    private static final Long TRANSACTION_ID = 99L;

    private SavingsAccountWritePlatformService writePlatformService;
    private JsonCommand command;
    private CommandProcessingResult commandProcessingResult;

    @BeforeEach
    void setUp() {
        this.writePlatformService = mock(SavingsAccountWritePlatformService.class);
        this.command = mock(JsonCommand.class);
        this.commandProcessingResult = mock(CommandProcessingResult.class);

        when(this.command.getSavingsId()).thenReturn(SAVINGS_ID);
        when(this.command.getTransactionId()).thenReturn(TRANSACTION_ID.toString());
    }

    @Test
    void depositCallsSavingsAccountWritePlatformServiceDeposit() {
        when(this.writePlatformService.deposit(SAVINGS_ID, this.command)).thenReturn(this.commandProcessingResult);

        final CommandProcessingResult result = new DynamicDepositAccountDepositCommandHandler(this.writePlatformService)
                .processCommand(this.command);

        assertThat(result).isSameAs(this.commandProcessingResult);
        verify(this.writePlatformService).deposit(SAVINGS_ID, this.command);
    }

    @Test
    void withdrawalCallsSavingsAccountWritePlatformServiceWithdrawal() {
        when(this.writePlatformService.withdrawal(SAVINGS_ID, this.command)).thenReturn(this.commandProcessingResult);

        final CommandProcessingResult result = new WithdrawalDynamicDepositAccountCommandHandler(this.writePlatformService)
                .processCommand(this.command);

        assertThat(result).isSameAs(this.commandProcessingResult);
        verify(this.writePlatformService).withdrawal(SAVINGS_ID, this.command);
    }

    @Test
    void closeCallsSavingsAccountWritePlatformServiceClose() {
        when(this.writePlatformService.close(SAVINGS_ID, this.command)).thenReturn(this.commandProcessingResult);

        final CommandProcessingResult result = new CloseDynamicDepositAccountCommandHandler(this.writePlatformService)
                .processCommand(this.command);

        assertThat(result).isSameAs(this.commandProcessingResult);
        verify(this.writePlatformService).close(SAVINGS_ID, this.command);
    }

    @Test
    void prematureCloseCallsSavingsAccountWritePlatformServiceClose() {
        when(this.writePlatformService.close(SAVINGS_ID, this.command)).thenReturn(this.commandProcessingResult);

        final CommandProcessingResult result = new PrematureCloseDynamicDepositAccountCommandHandler(this.writePlatformService)
                .processCommand(this.command);

        assertThat(result).isSameAs(this.commandProcessingResult);
        verify(this.writePlatformService).close(SAVINGS_ID, this.command);
    }

    @Test
    void undoTransactionCallsSavingsAccountWritePlatformServiceUndoTransaction() {
        when(this.writePlatformService.undoTransaction(SAVINGS_ID, TRANSACTION_ID, false)).thenReturn(this.commandProcessingResult);

        final CommandProcessingResult result = new UndoTransactionDynamicDepositAccountCommandHandler(this.writePlatformService)
                .processCommand(this.command);

        assertThat(result).isSameAs(this.commandProcessingResult);
        verify(this.writePlatformService).undoTransaction(SAVINGS_ID, TRANSACTION_ID, false);
    }

    @Test
    void adjustTransactionCallsSavingsAccountWritePlatformServiceAdjustSavingsTransaction() {
        when(this.writePlatformService.adjustSavingsTransaction(SAVINGS_ID, TRANSACTION_ID, this.command))
                .thenReturn(this.commandProcessingResult);

        final CommandProcessingResult result = new DynamicDepositTransactionAdjustmentCommandHandler(this.writePlatformService)
                .processCommand(this.command);

        assertThat(result).isSameAs(this.commandProcessingResult);
        verify(this.writePlatformService).adjustSavingsTransaction(SAVINGS_ID, TRANSACTION_ID, this.command);
    }
}
