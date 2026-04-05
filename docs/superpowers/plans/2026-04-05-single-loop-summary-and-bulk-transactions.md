# Single-Loop Summary & Bulk Transactions Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add single-pass summary recalculation and a bulk deposit/withdrawal API endpoint to the Advancly custom savings module.

**Architecture:** Feature 1 adds a `calculateAndUpdateSummaryInSinglePass()` method to `SavingsAccountTransactionHelper` that replaces the 12-pass `updateSummary()` call in the insert path. Feature 2 adds a full command-infrastructure-backed bulk transactions endpoint: API resource → CommandWrapperBuilder → command handler → service method, all in the custom module except the builder method which must be in core.

**Tech Stack:** Java 17, Spring Boot 3.2.6, JAX-RS, JPA/EclipseLink, JUnit 5, Mockito, AssertJ, Gson

---

## File Structure

### Feature 1: Single-Loop Summary

| File | Action | Responsibility |
|------|--------|----------------|
| `custom/advancly/savings/service/src/main/java/com/advancly/fineract/portfolio/savings/helper/SavingsAccountTransactionHelper.java` | Modify | Add `calculateAndUpdateSummaryInSinglePass()` method |
| `custom/advancly/savings/service/src/main/java/com/advancly/fineract/portfolio/savings/service/AdvanclySavingsAccountDomainService.java` | Modify | Replace `updateSummary()` call with single-pass method in insert path |
| `custom/advancly/savings/service/src/test/java/com/advancly/fineract/portfolio/savings/helper/SavingsAccountTransactionHelperTest.java` | Modify | Add tests for single-pass summary |

### Feature 2: Bulk Transactions

| File | Action | Responsibility |
|------|--------|----------------|
| `fineract-core/src/main/java/org/apache/fineract/commands/service/CommandWrapperBuilder.java` | Modify | Add `savingsAccountBulkTransaction()` method |
| `custom/advancly/savings/service/src/main/java/com/advancly/fineract/portfolio/savings/data/BulkTransactionDataValidator.java` | Create | Validate bulk transaction JSON payload |
| `custom/advancly/savings/service/src/main/java/com/advancly/fineract/portfolio/savings/handler/BulkTransactionSavingsAccountCommandHandler.java` | Create | `@CommandType(entity = "SAVINGSACCOUNT", action = "BULKTRANSACTION")` handler |
| `custom/advancly/savings/service/src/main/java/com/advancly/fineract/portfolio/savings/api/AdvanclySavingsAccountTransactionsApiResource.java` | Create | JAX-RS endpoint at `/v1/savingsaccounts/{savingsId}/bulk-transactions` |
| `custom/advancly/savings/service/src/main/java/com/advancly/fineract/portfolio/savings/service/AdvanclySavingsAccountWritePlatformService.java` | Modify | Add `bulkTransaction()` method |
| `fineract-savings/src/main/java/org/apache/fineract/portfolio/savings/service/SavingsAccountWritePlatformService.java` | Modify | Add `default bulkTransaction()` method |
| `custom/advancly/savings/service/src/main/java/com/advancly/fineract/portfolio/savings/service/SavingsAccountWritePlatformServiceDelegate.java` | Modify | Add `bulkTransaction()` override |
| `custom/advancly/savings/starter/src/main/java/com/advancly/fineract/portfolio/savings/starter/CoreSavingsWritePlatformServiceDelegateImpl.java` | Modify | Add `bulkTransaction()` delegate |
| `custom/advancly/savings/service/src/test/java/com/advancly/fineract/portfolio/savings/data/BulkTransactionDataValidatorTest.java` | Create | Validator tests |
| `custom/advancly/savings/service/src/test/java/com/advancly/fineract/portfolio/savings/service/AdvanclySavingsAccountWritePlatformServiceBulkTest.java` | Create | Bulk transaction service tests |

---

## Task 1: Single-Pass Summary — Failing Tests

**Files:**
- Test: `custom/advancly/savings/service/src/test/java/com/advancly/fineract/portfolio/savings/helper/SavingsAccountTransactionHelperTest.java`

- [ ] **Step 1: Write failing tests for `calculateAndUpdateSummaryInSinglePass`**

Add these test methods at the end of `SavingsAccountTransactionHelperTest`:

```java
@Test
void testCalculateAndUpdateSummaryInSinglePass_depositsAndWithdrawals() {
    SavingsAccountSummary summary = new SavingsAccountSummaryTestBuilder().build();
    SavingsAccount account = new SavingsAccountTestBuilder().withSummary(summary).build();

    List<SavingsAccountTransaction> transactions = new ArrayList<>();
    transactions.add(new SavingsAccountTransactionTestBuilder().withId(1L).withType(SavingsAccountTransactionType.DEPOSIT)
            .withDate(LocalDate.of(2025, 7, 1)).withAmount(BigDecimal.valueOf(1000)).build());
    transactions.add(new SavingsAccountTransactionTestBuilder().withId(2L).withType(SavingsAccountTransactionType.WITHDRAWAL)
            .withDate(LocalDate.of(2025, 7, 2)).withAmount(BigDecimal.valueOf(300)).build());
    transactions.add(new SavingsAccountTransactionTestBuilder().withId(3L).withType(SavingsAccountTransactionType.DEPOSIT)
            .withDate(LocalDate.of(2025, 7, 3)).withAmount(BigDecimal.valueOf(500)).build());

    helper.calculateAndUpdateSummaryInSinglePass(account, transactions, currency);

    assertThat(account.getSummary().getTotalDeposits()).isEqualByComparingTo(BigDecimal.valueOf(1500));
    assertThat(account.getSummary().getTotalWithdrawals()).isEqualByComparingTo(BigDecimal.valueOf(300));
    assertThat(account.getSummary().getAccountBalance()).isEqualByComparingTo(BigDecimal.valueOf(1200));
}

@Test
void testCalculateAndUpdateSummaryInSinglePass_skipsReversedTransactions() {
    SavingsAccountSummary summary = new SavingsAccountSummaryTestBuilder().build();
    SavingsAccount account = new SavingsAccountTestBuilder().withSummary(summary).build();

    List<SavingsAccountTransaction> transactions = new ArrayList<>();
    transactions.add(new SavingsAccountTransactionTestBuilder().withId(1L).withType(SavingsAccountTransactionType.DEPOSIT)
            .withDate(LocalDate.of(2025, 7, 1)).withAmount(BigDecimal.valueOf(1000)).build());
    transactions.add(new SavingsAccountTransactionTestBuilder().withId(2L).withType(SavingsAccountTransactionType.DEPOSIT)
            .withDate(LocalDate.of(2025, 7, 2)).withAmount(BigDecimal.valueOf(500)).reversed().build());

    helper.calculateAndUpdateSummaryInSinglePass(account, transactions, currency);

    assertThat(account.getSummary().getTotalDeposits()).isEqualByComparingTo(BigDecimal.valueOf(1000));
    assertThat(account.getSummary().getAccountBalance()).isEqualByComparingTo(BigDecimal.valueOf(1000));
}

@Test
void testCalculateAndUpdateSummaryInSinglePass_emptyTransactions() {
    SavingsAccountSummary summary = new SavingsAccountSummaryTestBuilder().build();
    SavingsAccount account = new SavingsAccountTestBuilder().withSummary(summary).build();

    helper.calculateAndUpdateSummaryInSinglePass(account, new ArrayList<>(), currency);

    assertThat(account.getSummary().getTotalDeposits()).isNull();
    assertThat(account.getSummary().getTotalWithdrawals()).isNull();
    assertThat(account.getSummary().getAccountBalance()).isEqualByComparingTo(BigDecimal.ZERO);
}
```

You also need to add the missing import at the top of the test file if not already present:

```java
import org.apache.fineract.portfolio.savings.SavingsAccountTransactionType;
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `cd /Users/eboka.kevin/Documents/work/adv_mifos/fineract-adv && ./gradlew :custom:advancly:savings:service:test --tests "com.advancly.fineract.portfolio.savings.helper.SavingsAccountTransactionHelperTest" -x spotlessCheck`

Expected: FAIL — `calculateAndUpdateSummaryInSinglePass` method does not exist.

---

## Task 2: Single-Pass Summary — Implementation

**Files:**
- Modify: `custom/advancly/savings/service/src/main/java/com/advancly/fineract/portfolio/savings/helper/SavingsAccountTransactionHelper.java`

- [ ] **Step 1: Add `calculateAndUpdateSummaryInSinglePass` method**

Add this method to `SavingsAccountTransactionHelper` after the existing `recalculateDailyBalancesFromDate` method (after line 98):

```java
/**
 * O(n) single-pass — Calculate all 11 summary totals, running balance, and last interest posting date in one
 * iteration. Replaces the 12-pass updateSummary() + updateRunningBalanceAndPivotDate() for the insert path.
 */
public void calculateAndUpdateSummaryInSinglePass(SavingsAccount account, List<SavingsAccountTransaction> transactions,
        MonetaryCurrency currency) {
    Money totalDeposits = Money.zero(currency);
    Money totalWithdrawals = Money.zero(currency);
    Money totalInterestPosted = Money.zero(currency);
    Money totalWithdrawalFees = Money.zero(currency);
    Money totalAnnualFees = Money.zero(currency);
    Money totalFeeCharge = Money.zero(currency);
    Money totalFeeChargesWaived = Money.zero(currency);
    Money totalPenaltyCharge = Money.zero(currency);
    Money totalPenaltyChargesWaived = Money.zero(currency);
    Money totalOverdraftInterest = Money.zero(currency);
    Money totalWithholdTax = Money.zero(currency);
    LocalDate lastInterestPostingDate = null;

    for (SavingsAccountTransaction txn : transactions) {
        if (txn.isReversalTransaction()) {
            continue;
        }
        Money amount = txn.getAmount(currency);

        if ((txn.isDepositAndNotReversed() || txn.isDividendPayoutAndNotReversed())) {
            totalDeposits = totalDeposits.plus(amount);
        }
        if (txn.isWithdrawal() && txn.isNotReversed()) {
            totalWithdrawals = totalWithdrawals.plus(amount);
        }
        if (txn.isInterestPostingAndNotReversed() && txn.isNotReversed()) {
            totalInterestPosted = totalInterestPosted.plus(amount);
            lastInterestPostingDate = txn.getTransactionDate();
        }
        if (txn.isWithdrawalFeeAndNotReversed() && txn.isNotReversed()) {
            totalWithdrawalFees = totalWithdrawalFees.plus(amount);
        }
        if (txn.isAnnualFeeAndNotReversed() && txn.isNotReversed()) {
            totalAnnualFees = totalAnnualFees.plus(amount);
        }
        if (txn.isFeeChargeAndNotReversed()) {
            totalFeeCharge = totalFeeCharge.plus(amount);
        }
        if (txn.isWaiveFeeChargeAndNotReversed()) {
            totalFeeChargesWaived = totalFeeChargesWaived.plus(amount);
        }
        if (txn.isPenaltyChargeAndNotReversed()) {
            totalPenaltyCharge = totalPenaltyCharge.plus(amount);
        }
        if (txn.isWaivePenaltyChargeAndNotReversed()) {
            totalPenaltyChargesWaived = totalPenaltyChargesWaived.plus(amount);
        }
        if (txn.isOverdraftInterestAndNotReversed()) {
            totalOverdraftInterest = totalOverdraftInterest.plus(amount);
        }
        if (txn.isWithHoldTaxAndNotReversed()) {
            totalWithholdTax = totalWithholdTax.plus(amount);
        }
    }

    SavingsAccountSummary summary = account.getSummary();
    summary.setTotalDeposits(totalDeposits.getAmountDefaultedToNullIfZero());
    summary.setTotalWithdrawals(totalWithdrawals.getAmountDefaultedToNullIfZero());
    summary.setTotalInterestPosted(totalInterestPosted.getAmountDefaultedToNullIfZero());
    summary.setTotalWithdrawalFees(totalWithdrawalFees.getAmountDefaultedToNullIfZero());
    summary.setTotalAnnualFees(totalAnnualFees.getAmountDefaultedToNullIfZero());
    summary.setTotalFeeCharge(totalFeeCharge.getAmountDefaultedToNullIfZero());
    summary.setTotalPenaltyCharge(totalPenaltyCharge.getAmountDefaultedToNullIfZero());
    summary.setTotalOverdraftInterestDerived(totalOverdraftInterest.getAmountDefaultedToNullIfZero());
    summary.setTotalWithholdTax(totalWithholdTax.getAmountDefaultedToNullIfZero());
    if (lastInterestPostingDate != null) {
        summary.setInterestPostedTillDate(lastInterestPostingDate);
    }

    // Account balance = deposits + interest - withdrawals - fees - penalties - overdraft interest - withhold tax
    BigDecimal accountBalance = totalDeposits.plus(totalInterestPosted).minus(totalWithdrawals).minus(totalWithdrawalFees)
            .minus(totalAnnualFees).minus(totalFeeCharge).minus(totalPenaltyCharge).minus(totalOverdraftInterest)
            .minus(totalWithholdTax).getAmount();
    summary.setAccountBalance(accountBalance);
}
```

Add these imports at the top of the file:

```java
import java.time.LocalDate;
import org.apache.fineract.portfolio.savings.domain.SavingsAccountSummary;
```

**Note:** The `SavingsAccountSummary` class does not have public setters for all fields. We need to check which setters exist and add missing ones. From the file we read, the following setters exist:
- `setAccountBalance(BigDecimal)` — exists (line 278)
- `setInterestPostedTillDate(LocalDate)` — exists (line 290)
- `setRunningBalanceOnPivotDate(BigDecimal)` — exists (line 298)

The following setters do NOT exist and need to be added:
- `setTotalDeposits`, `setTotalWithdrawals`, `setTotalInterestPosted`, `setTotalWithdrawalFees`, `setTotalAnnualFees`, `setTotalFeeCharge`, `setTotalPenaltyCharge`, `setTotalOverdraftInterestDerived`, `setTotalWithholdTax`

However, the `SavingsAccountSummary` is in core (`fineract-savings`). Since the fields are `private` (not `final`), and the `updateSummary()` method writes to them directly, we have two options:
1. Add setters in core (minimal change)
2. Use `ReflectionTestUtils` (hacky for production code)

Option 1 is cleaner. Add setters to `SavingsAccountSummary`.

- [ ] **Step 2: Add missing setters to `SavingsAccountSummary`**

Modify: `fineract-savings/src/main/java/org/apache/fineract/portfolio/savings/domain/SavingsAccountSummary.java`

Add these setters after line 338 (after `getTotalWithholdTax()`):

```java
public void setTotalDeposits(BigDecimal totalDeposits) {
    this.totalDeposits = totalDeposits;
}

public void setTotalWithdrawals(BigDecimal totalWithdrawals) {
    this.totalWithdrawals = totalWithdrawals;
}

public void setTotalInterestPosted(BigDecimal totalInterestPosted) {
    this.totalInterestPosted = totalInterestPosted;
}

public void setTotalWithdrawalFees(BigDecimal totalWithdrawalFees) {
    this.totalWithdrawalFees = totalWithdrawalFees;
}

public void setTotalAnnualFees(BigDecimal totalAnnualFees) {
    this.totalAnnualFees = totalAnnualFees;
}

public void setTotalFeeCharge(BigDecimal totalFeeCharge) {
    this.totalFeeCharge = totalFeeCharge;
}

public void setTotalPenaltyCharge(BigDecimal totalPenaltyCharge) {
    this.totalPenaltyCharge = totalPenaltyCharge;
}

public void setTotalOverdraftInterestDerived(BigDecimal totalOverdraftInterestDerived) {
    this.totalOverdraftInterestDerived = totalOverdraftInterestDerived;
}

public void setTotalWithholdTax(BigDecimal totalWithholdTax) {
    this.totalWithholdTax = totalWithholdTax;
}
```

- [ ] **Step 3: Run tests to verify they pass**

Run: `cd /Users/eboka.kevin/Documents/work/adv_mifos/fineract-adv && ./gradlew :custom:advancly:savings:service:test --tests "com.advancly.fineract.portfolio.savings.helper.SavingsAccountTransactionHelperTest" -x spotlessCheck`

Expected: ALL PASS

- [ ] **Step 4: Wire single-pass into the insert path**

Modify: `custom/advancly/savings/service/src/main/java/com/advancly/fineract/portfolio/savings/service/AdvanclySavingsAccountDomainService.java`

Replace the `handleInsertPathForWithdrawal` method's summary update line. Change line 183:

```java
// OLD:
account.getSummary().updateSummary(currency, savingsAccountTransactionSummaryWrapper, sortedTxns);
```

to:

```java
// NEW:
transactionHelper.calculateAndUpdateSummaryInSinglePass(account, sortedTxns, currency);
```

- [ ] **Step 5: Run all existing tests**

Run: `cd /Users/eboka.kevin/Documents/work/adv_mifos/fineract-adv && ./gradlew :custom:advancly:savings:service:test -x spotlessCheck`

Expected: ALL PASS

- [ ] **Step 6: Format and commit**

```bash
cd /Users/eboka.kevin/Documents/work/adv_mifos/fineract-adv
./gradlew spotlessApply
git add custom/advancly/savings/service/src/main/java/com/advancly/fineract/portfolio/savings/helper/SavingsAccountTransactionHelper.java \
        custom/advancly/savings/service/src/main/java/com/advancly/fineract/portfolio/savings/service/AdvanclySavingsAccountDomainService.java \
        custom/advancly/savings/service/src/test/java/com/advancly/fineract/portfolio/savings/helper/SavingsAccountTransactionHelperTest.java \
        fineract-savings/src/main/java/org/apache/fineract/portfolio/savings/domain/SavingsAccountSummary.java
git commit -m "feat: replace 12-pass summary update with single-pass calculation in insert path"
```

---

## Task 3: Bulk Transaction — Interface & CommandWrapperBuilder

**Files:**
- Modify: `fineract-savings/src/main/java/org/apache/fineract/portfolio/savings/service/SavingsAccountWritePlatformService.java`
- Modify: `fineract-core/src/main/java/org/apache/fineract/commands/service/CommandWrapperBuilder.java`

- [ ] **Step 1: Add default `bulkTransaction` method to interface**

Modify: `fineract-savings/src/main/java/org/apache/fineract/portfolio/savings/service/SavingsAccountWritePlatformService.java`

Add before the closing brace of the interface (before line 121):

```java
default CommandProcessingResult bulkTransaction(Long savingsId, JsonCommand command) {
    throw new UnsupportedOperationException("Bulk transaction not supported in this implementation");
}
```

- [ ] **Step 2: Add `savingsAccountBulkTransaction` to CommandWrapperBuilder**

Modify: `fineract-core/src/main/java/org/apache/fineract/commands/service/CommandWrapperBuilder.java`

Add after the `savingsAccountWithdrawal` method (after line 1580):

```java
public CommandWrapperBuilder savingsAccountBulkTransaction(final Long accountId) {
    this.actionName = "BULKTRANSACTION";
    this.entityName = "SAVINGSACCOUNT";
    this.savingsId = accountId;
    this.entityId = null;
    this.href = "/savingsaccounts/" + accountId + "/bulk-transactions";
    return this;
}
```

- [ ] **Step 3: Verify compilation**

Run: `cd /Users/eboka.kevin/Documents/work/adv_mifos/fineract-adv && ./gradlew compileJava -x test -x spotlessCheck`

Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Format and commit**

```bash
cd /Users/eboka.kevin/Documents/work/adv_mifos/fineract-adv
./gradlew spotlessApply
git add fineract-savings/src/main/java/org/apache/fineract/portfolio/savings/service/SavingsAccountWritePlatformService.java \
        fineract-core/src/main/java/org/apache/fineract/commands/service/CommandWrapperBuilder.java
git commit -m "feat: add bulkTransaction to SavingsAccountWritePlatformService interface and CommandWrapperBuilder"
```

---

## Task 4: Bulk Transaction — Validator with Tests

**Files:**
- Create: `custom/advancly/savings/service/src/test/java/com/advancly/fineract/portfolio/savings/data/BulkTransactionDataValidatorTest.java`
- Create: `custom/advancly/savings/service/src/main/java/com/advancly/fineract/portfolio/savings/data/BulkTransactionDataValidator.java`

- [ ] **Step 1: Write failing tests for BulkTransactionDataValidator**

Create test file:

```java
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

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.apache.fineract.infrastructure.core.exception.InvalidJsonException;
import org.apache.fineract.infrastructure.core.exception.PlatformApiDataValidationException;
import org.apache.fineract.infrastructure.core.serialization.FromJsonHelper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class BulkTransactionDataValidatorTest {

    private BulkTransactionDataValidator validator;

    @BeforeEach
    void setUp() {
        validator = new BulkTransactionDataValidator(new FromJsonHelper());
    }

    @Test
    void testValidPayload() {
        String json = buildValidPayload();
        assertThatCode(() -> validator.validate(json)).doesNotThrowAnyException();
    }

    @Test
    void testEmptyTransactionsArray() {
        JsonObject payload = new JsonObject();
        payload.addProperty("dateFormat", "dd MMMM yyyy");
        payload.addProperty("locale", "en");
        payload.add("transactions", new JsonArray());

        assertThatThrownBy(() -> validator.validate(payload.toString()))
                .isInstanceOf(PlatformApiDataValidationException.class);
    }

    @Test
    void testMissingTransactionsArray() {
        JsonObject payload = new JsonObject();
        payload.addProperty("dateFormat", "dd MMMM yyyy");
        payload.addProperty("locale", "en");

        assertThatThrownBy(() -> validator.validate(payload.toString()))
                .isInstanceOf(PlatformApiDataValidationException.class);
    }

    @Test
    void testInvalidType() {
        JsonObject payload = new JsonObject();
        payload.addProperty("dateFormat", "dd MMMM yyyy");
        payload.addProperty("locale", "en");
        JsonArray txns = new JsonArray();
        JsonObject txn = new JsonObject();
        txn.addProperty("type", "transfer");
        txn.addProperty("transactionDate", "05 April 2026");
        txn.addProperty("transactionAmount", 1000);
        txn.addProperty("paymentTypeId", 1);
        txn.addProperty("receiptNumber", "REC-001");
        txns.add(txn);
        payload.add("transactions", txns);

        assertThatThrownBy(() -> validator.validate(payload.toString()))
                .isInstanceOf(PlatformApiDataValidationException.class);
    }

    @Test
    void testDuplicateReceiptNumbers() {
        JsonObject payload = new JsonObject();
        payload.addProperty("dateFormat", "dd MMMM yyyy");
        payload.addProperty("locale", "en");
        JsonArray txns = new JsonArray();
        txns.add(buildTransaction("deposit", "REC-001", 1000));
        txns.add(buildTransaction("withdrawal", "REC-001", 500));
        payload.add("transactions", txns);

        assertThatThrownBy(() -> validator.validate(payload.toString()))
                .isInstanceOf(PlatformApiDataValidationException.class);
    }

    @Test
    void testMissingReceiptNumber() {
        JsonObject payload = new JsonObject();
        payload.addProperty("dateFormat", "dd MMMM yyyy");
        payload.addProperty("locale", "en");
        JsonArray txns = new JsonArray();
        JsonObject txn = new JsonObject();
        txn.addProperty("type", "deposit");
        txn.addProperty("transactionDate", "05 April 2026");
        txn.addProperty("transactionAmount", 1000);
        txn.addProperty("paymentTypeId", 1);
        // No receiptNumber
        txns.add(txn);
        payload.add("transactions", txns);

        assertThatThrownBy(() -> validator.validate(payload.toString()))
                .isInstanceOf(PlatformApiDataValidationException.class);
    }

    @Test
    void testMissingTransactionAmount() {
        JsonObject payload = new JsonObject();
        payload.addProperty("dateFormat", "dd MMMM yyyy");
        payload.addProperty("locale", "en");
        JsonArray txns = new JsonArray();
        JsonObject txn = new JsonObject();
        txn.addProperty("type", "deposit");
        txn.addProperty("transactionDate", "05 April 2026");
        txn.addProperty("paymentTypeId", 1);
        txn.addProperty("receiptNumber", "REC-001");
        // No transactionAmount
        txns.add(txn);
        payload.add("transactions", txns);

        assertThatThrownBy(() -> validator.validate(payload.toString()))
                .isInstanceOf(PlatformApiDataValidationException.class);
    }

    @Test
    void testNullJson() {
        assertThatThrownBy(() -> validator.validate(null))
                .isInstanceOf(InvalidJsonException.class);
    }

    private String buildValidPayload() {
        JsonObject payload = new JsonObject();
        payload.addProperty("dateFormat", "dd MMMM yyyy");
        payload.addProperty("locale", "en");
        JsonArray txns = new JsonArray();
        txns.add(buildTransaction("deposit", "REC-001", 5000));
        txns.add(buildTransaction("withdrawal", "REC-002", 2000));
        payload.add("transactions", txns);
        return payload.toString();
    }

    private JsonObject buildTransaction(String type, String receiptNumber, int amount) {
        JsonObject txn = new JsonObject();
        txn.addProperty("type", type);
        txn.addProperty("transactionDate", "05 April 2026");
        txn.addProperty("transactionAmount", amount);
        txn.addProperty("paymentTypeId", 1);
        txn.addProperty("receiptNumber", receiptNumber);
        return txn;
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `cd /Users/eboka.kevin/Documents/work/adv_mifos/fineract-adv && ./gradlew :custom:advancly:savings:service:test --tests "com.advancly.fineract.portfolio.savings.data.BulkTransactionDataValidatorTest" -x spotlessCheck`

Expected: FAIL — `BulkTransactionDataValidator` class does not exist.

- [ ] **Step 3: Implement BulkTransactionDataValidator**

Create: `custom/advancly/savings/service/src/main/java/com/advancly/fineract/portfolio/savings/data/BulkTransactionDataValidator.java`

```java
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

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.apache.fineract.infrastructure.core.data.ApiParameterError;
import org.apache.fineract.infrastructure.core.data.DataValidatorBuilder;
import org.apache.fineract.infrastructure.core.exception.InvalidJsonException;
import org.apache.fineract.infrastructure.core.exception.PlatformApiDataValidationException;
import org.apache.fineract.infrastructure.core.serialization.FromJsonHelper;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class BulkTransactionDataValidator {

    private static final String RESOURCE_NAME = "savingsAccountBulkTransaction";
    private static final Set<String> VALID_TYPES = Set.of("deposit", "withdrawal");

    private final FromJsonHelper fromApiJsonHelper;

    public void validate(final String json) {
        if (json == null || json.isBlank()) {
            throw new InvalidJsonException();
        }

        final JsonElement element = fromApiJsonHelper.parse(json);
        final List<ApiParameterError> dataValidationErrors = new ArrayList<>();
        final DataValidatorBuilder baseDataValidator = new DataValidatorBuilder(dataValidationErrors).resource(RESOURCE_NAME);

        final JsonArray transactions = fromApiJsonHelper.extractJsonArrayNamed("transactions", element);
        baseDataValidator.reset().parameter("transactions").value(transactions).notNull();

        if (transactions == null || transactions.isEmpty()) {
            baseDataValidator.reset().parameter("transactions").value(transactions == null ? null : transactions.size()).notNull()
                    .integerGreaterThanZero();
            throwExceptionIfValidationWarningsExist(dataValidationErrors);
            return;
        }

        final Set<String> seenReceiptNumbers = new HashSet<>();

        for (int i = 0; i < transactions.size(); i++) {
            final JsonObject txn = transactions.get(i).getAsJsonObject();
            final String prefix = "transactions[" + i + "].";

            // type
            final String type = fromApiJsonHelper.extractStringNamed("type", txn);
            baseDataValidator.reset().parameter(prefix + "type").value(type).notBlank();
            if (type != null && !VALID_TYPES.contains(type)) {
                baseDataValidator.reset().parameter(prefix + "type").value(type)
                        .isOneOfTheseStringValues("deposit", "withdrawal");
            }

            // transactionDate
            final String transactionDate = fromApiJsonHelper.extractStringNamed("transactionDate", txn);
            baseDataValidator.reset().parameter(prefix + "transactionDate").value(transactionDate).notBlank();

            // transactionAmount
            final BigDecimal transactionAmount = fromApiJsonHelper.extractBigDecimalWithLocaleNamed("transactionAmount", txn);
            baseDataValidator.reset().parameter(prefix + "transactionAmount").value(transactionAmount).notNull().positiveAmount();

            // paymentTypeId
            final Long paymentTypeId = fromApiJsonHelper.extractLongNamed("paymentTypeId", txn);
            baseDataValidator.reset().parameter(prefix + "paymentTypeId").value(paymentTypeId).notNull().longGreaterThanZero();

            // receiptNumber — required and unique
            final String receiptNumber = fromApiJsonHelper.extractStringNamed("receiptNumber", txn);
            baseDataValidator.reset().parameter(prefix + "receiptNumber").value(receiptNumber).notBlank();
            if (receiptNumber != null && !receiptNumber.isBlank()) {
                if (!seenReceiptNumbers.add(receiptNumber)) {
                    baseDataValidator.reset().parameter(prefix + "receiptNumber").value(receiptNumber)
                            .failWithCode("duplicate.receipt.number");
                }
            }
        }

        throwExceptionIfValidationWarningsExist(dataValidationErrors);
    }

    private void throwExceptionIfValidationWarningsExist(final List<ApiParameterError> dataValidationErrors) {
        if (!dataValidationErrors.isEmpty()) {
            throw new PlatformApiDataValidationException(dataValidationErrors);
        }
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `cd /Users/eboka.kevin/Documents/work/adv_mifos/fineract-adv && ./gradlew :custom:advancly:savings:service:test --tests "com.advancly.fineract.portfolio.savings.data.BulkTransactionDataValidatorTest" -x spotlessCheck`

Expected: ALL PASS

- [ ] **Step 5: Format and commit**

```bash
cd /Users/eboka.kevin/Documents/work/adv_mifos/fineract-adv
./gradlew spotlessApply
git add custom/advancly/savings/service/src/main/java/com/advancly/fineract/portfolio/savings/data/BulkTransactionDataValidator.java \
        custom/advancly/savings/service/src/test/java/com/advancly/fineract/portfolio/savings/data/BulkTransactionDataValidatorTest.java
git commit -m "feat: add BulkTransactionDataValidator with receipt number uniqueness check"
```

---

## Task 5: Bulk Transaction — Command Handler

**Files:**
- Create: `custom/advancly/savings/service/src/main/java/com/advancly/fineract/portfolio/savings/handler/BulkTransactionSavingsAccountCommandHandler.java`

- [ ] **Step 1: Create command handler**

```java
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

import lombok.RequiredArgsConstructor;
import org.apache.fineract.commands.annotation.CommandType;
import org.apache.fineract.commands.handler.NewCommandSourceHandler;
import org.apache.fineract.infrastructure.core.api.JsonCommand;
import org.apache.fineract.infrastructure.core.data.CommandProcessingResult;
import org.apache.fineract.portfolio.savings.service.SavingsAccountWritePlatformService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@CommandType(entity = "SAVINGSACCOUNT", action = "BULKTRANSACTION")
public class BulkTransactionSavingsAccountCommandHandler implements NewCommandSourceHandler {

    private final SavingsAccountWritePlatformService writePlatformService;

    @Transactional
    @Override
    public CommandProcessingResult processCommand(final JsonCommand command) {
        return this.writePlatformService.bulkTransaction(command.getSavingsId(), command);
    }
}
```

- [ ] **Step 2: Verify compilation**

Run: `cd /Users/eboka.kevin/Documents/work/adv_mifos/fineract-adv && ./gradlew :custom:advancly:savings:service:compileJava -x spotlessCheck`

Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Format and commit**

```bash
cd /Users/eboka.kevin/Documents/work/adv_mifos/fineract-adv
./gradlew spotlessApply
git add custom/advancly/savings/service/src/main/java/com/advancly/fineract/portfolio/savings/handler/BulkTransactionSavingsAccountCommandHandler.java
git commit -m "feat: add BULKTRANSACTION command handler for savings accounts"
```

---

## Task 6: Bulk Transaction — API Resource

**Files:**
- Create: `custom/advancly/savings/service/src/main/java/com/advancly/fineract/portfolio/savings/api/AdvanclySavingsAccountTransactionsApiResource.java`

- [ ] **Step 1: Create API resource**

```java
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

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import lombok.RequiredArgsConstructor;
import org.apache.fineract.commands.domain.CommandWrapper;
import org.apache.fineract.commands.service.CommandWrapperBuilder;
import org.apache.fineract.commands.service.PortfolioCommandSourceWritePlatformService;
import org.apache.fineract.infrastructure.core.data.CommandProcessingResult;
import org.apache.fineract.infrastructure.core.serialization.DefaultToApiJsonSerializer;
import org.apache.fineract.infrastructure.security.service.PlatformSecurityContext;
import org.apache.fineract.portfolio.savings.SavingsApiConstants;
import org.springframework.stereotype.Component;

@Path("/v1/savingsaccounts/{savingsId}/bulk-transactions")
@Component
@Tag(name = "Savings Account Bulk Transactions", description = "Bulk deposit and withdrawal operations")
@RequiredArgsConstructor
public class AdvanclySavingsAccountTransactionsApiResource {

    private final PlatformSecurityContext context;
    private final PortfolioCommandSourceWritePlatformService commandsSourceWritePlatformService;
    private final DefaultToApiJsonSerializer<CommandProcessingResult> toApiJsonSerializer;

    @POST
    @Consumes({ MediaType.APPLICATION_JSON })
    @Produces({ MediaType.APPLICATION_JSON })
    @Operation(summary = "Bulk Deposit/Withdrawal", description = "Submit multiple deposits and withdrawals in a single atomic request.\n\n"
            + "Example Request:\n\n" + "POST /savingsaccounts/{savingsId}/bulk-transactions")
    public String bulkTransaction(@PathParam("savingsId") @Parameter(description = "savingsId") final Long savingsId,
            final String apiRequestBodyAsJson) {

        this.context.authenticatedUser().validateHasReadPermission(SavingsApiConstants.SAVINGS_ACCOUNT_RESOURCE_NAME);

        final CommandWrapper commandRequest = new CommandWrapperBuilder().savingsAccountBulkTransaction(savingsId)
                .withJson(apiRequestBodyAsJson).build();

        final CommandProcessingResult result = this.commandsSourceWritePlatformService.logCommandSource(commandRequest);

        return this.toApiJsonSerializer.serialize(result);
    }
}
```

- [ ] **Step 2: Verify compilation**

Run: `cd /Users/eboka.kevin/Documents/work/adv_mifos/fineract-adv && ./gradlew :custom:advancly:savings:service:compileJava -x spotlessCheck`

Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Format and commit**

```bash
cd /Users/eboka.kevin/Documents/work/adv_mifos/fineract-adv
./gradlew spotlessApply
git add custom/advancly/savings/service/src/main/java/com/advancly/fineract/portfolio/savings/api/AdvanclySavingsAccountTransactionsApiResource.java
git commit -m "feat: add JAX-RS endpoint for bulk savings transactions"
```

---

## Task 7: Bulk Transaction — Service Implementation

**Files:**
- Modify: `custom/advancly/savings/service/src/main/java/com/advancly/fineract/portfolio/savings/service/AdvanclySavingsAccountWritePlatformService.java`
- Modify: `custom/advancly/savings/service/src/main/java/com/advancly/fineract/portfolio/savings/service/SavingsAccountWritePlatformServiceDelegate.java`
- Modify: `custom/advancly/savings/starter/src/main/java/com/advancly/fineract/portfolio/savings/starter/CoreSavingsWritePlatformServiceDelegateImpl.java`

- [ ] **Step 1: Add `bulkTransaction` override to delegate interface and impl**

Modify `SavingsAccountWritePlatformServiceDelegate.java` — no changes needed since it extends `SavingsAccountWritePlatformService` which now has the default method.

Modify `CoreSavingsWritePlatformServiceDelegateImpl.java` — add delegate method. After the `bulkGSIMClose` method, add:

```java
@Override
public CommandProcessingResult bulkTransaction(Long savingsId, JsonCommand command) {
    return delegate.bulkTransaction(savingsId, command);
}
```

- [ ] **Step 2: Implement `bulkTransaction` in `AdvanclySavingsAccountWritePlatformService`**

Add these imports to `AdvanclySavingsAccountWritePlatformService.java`:

```java
import com.advancly.fineract.portfolio.savings.data.BulkTransactionDataValidator;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.HashMap;
import java.util.List;
import org.apache.fineract.infrastructure.core.serialization.FromJsonHelper;
import org.apache.fineract.portfolio.paymentdetail.PaymentDetailConstants;
import org.apache.fineract.portfolio.paymentdetail.domain.PaymentDetail;
import org.apache.fineract.portfolio.paymentdetail.domain.PaymentDetailRepository;
import org.apache.fineract.portfolio.paymenttype.domain.PaymentType;
import org.apache.fineract.portfolio.paymenttype.domain.PaymentTypeRepositoryWrapper;
```

Add these fields to the class (after existing fields):

```java
private final BulkTransactionDataValidator bulkTransactionDataValidator;
private final FromJsonHelper fromApiJsonHelper;
private final PaymentTypeRepositoryWrapper paymentTypeRepositoryWrapper;
private final PaymentDetailRepository paymentDetailRepository;
```

Add the `bulkTransaction` method before the `// === Helper methods ===` comment:

```java
@Transactional
@Override
public CommandProcessingResult bulkTransaction(final Long savingsId, final JsonCommand command) {
    context.authenticatedUser();
    bulkTransactionDataValidator.validate(command.json());

    final JsonArray transactions = fromApiJsonHelper.extractJsonArrayNamed("transactions", command.parsedJson());

    // Find earliest transaction date to determine path
    LocalDate earliestDate = null;
    for (int i = 0; i < transactions.size(); i++) {
        final JsonObject txn = transactions.get(i).getAsJsonObject();
        final String dateFormat = fromApiJsonHelper.extractStringNamed("dateFormat", command.parsedJson());
        final String locale = fromApiJsonHelper.extractStringNamed("locale", command.parsedJson());
        final LocalDate txnDate = fromApiJsonHelper.extractLocalDateNamed("transactionDate", txn, dateFormat,
                java.util.Locale.forLanguageTag(locale.replace("_", "-")));
        if (earliestDate == null || txnDate.isBefore(earliestDate)) {
            earliestDate = txnDate;
        }
    }

    Optional<LocalDate> lastTxnDate = advanclyTransactionRepository.findLastTransactionDate(savingsId);
    boolean isAppendPath = lastTxnDate.isEmpty() || !earliestDate.isBefore(lastTxnDate.get());

    AssembledSavingsAccount assembled;
    if (isAppendPath) {
        assembled = assembler.assembleForAppendPath(savingsId);
    } else {
        boolean hasInterest = checkHasInterest(savingsId);
        assembled = assembler.assembleForInsertPath(savingsId, earliestDate, hasInterest);
    }

    final SavingsAccount account = assembled.getAccount();
    account.validateForAccountBlock();

    Money lastRunningBalance = Money.of(account.getCurrency(), account.getSummary().getRunningBalanceOnPivotDate());
    final Map<String, Object> changes = new LinkedHashMap<>();
    final Map<String, Long> transactionIds = new LinkedHashMap<>();

    final String dateFormat = fromApiJsonHelper.extractStringNamed("dateFormat", command.parsedJson());
    final String locale = fromApiJsonHelper.extractStringNamed("locale", command.parsedJson());

    for (int i = 0; i < transactions.size(); i++) {
        final JsonObject txn = transactions.get(i).getAsJsonObject();
        final String type = fromApiJsonHelper.extractStringNamed("type", txn);
        final LocalDate transactionDate = fromApiJsonHelper.extractLocalDateNamed("transactionDate", txn, dateFormat,
                java.util.Locale.forLanguageTag(locale.replace("_", "-")));
        final BigDecimal transactionAmount = fromApiJsonHelper.extractBigDecimalWithLocaleNamed("transactionAmount", txn);
        final String receiptNumber = fromApiJsonHelper.extractStringNamed("receiptNumber", txn);

        // Build payment detail from the transaction item
        final PaymentDetail paymentDetail = createPaymentDetailFromJsonObject(txn);

        SavingsAccountTransaction savedTxn;
        if ("deposit".equals(type)) {
            account.validateForCreditBlock();
            savedTxn = domainService.handleDepositOptimized(account, transactionDate, transactionAmount, paymentDetail,
                    assembled.getInterestAndOverdraftTransactions(), lastRunningBalance, account.getCurrency());
        } else {
            account.validateForDebitBlock();
            savedTxn = domainService.handleWithdrawalOptimized(account, transactionDate, transactionAmount, paymentDetail, true,
                    assembled.getInterestAndOverdraftTransactions(), lastRunningBalance, account.getCurrency());
        }

        transactionIds.put(receiptNumber, savedTxn.getId());

        // Update running balance for next iteration
        lastRunningBalance = savedTxn.getRunningBalance(account.getCurrency());

        // Handle note if provided
        final String noteText = fromApiJsonHelper.extractStringNamed("note", txn);
        if (noteText != null && !noteText.isBlank()) {
            final Note note = Note.savingsTransactionNote(account, savedTxn, noteText);
            noteRepository.save(note);
        }
    }

    changes.put("transactionIds", transactionIds);

    return new CommandProcessingResultBuilder().withOfficeId(account.officeId()).withClientId(account.clientId())
            .withGroupId(account.groupId()).withSavingsId(savingsId).with(changes).build();
}

private PaymentDetail createPaymentDetailFromJsonObject(JsonObject txn) {
    final Long paymentTypeId = fromApiJsonHelper.extractLongNamed("paymentTypeId", txn);
    if (paymentTypeId == null) {
        return null;
    }
    final PaymentType paymentType = paymentTypeRepositoryWrapper.findOneWithNotFoundDetection(paymentTypeId);
    final String accountNumber = fromApiJsonHelper.extractStringNamed(PaymentDetailConstants.accountNumberParamName, txn);
    final String checkNumber = fromApiJsonHelper.extractStringNamed(PaymentDetailConstants.checkNumberParamName, txn);
    final String routingCode = fromApiJsonHelper.extractStringNamed(PaymentDetailConstants.routingCodeParamName, txn);
    final String receiptNumber = fromApiJsonHelper.extractStringNamed(PaymentDetailConstants.receiptNumberParamName, txn);
    final String bankNumber = fromApiJsonHelper.extractStringNamed(PaymentDetailConstants.bankNumberParamName, txn);
    final PaymentDetail paymentDetail = PaymentDetail.instance(paymentType, accountNumber, checkNumber, routingCode, receiptNumber,
            bankNumber);
    return paymentDetailRepository.saveAndFlush(paymentDetail);
}
```

Also add the `Note` import:

```java
import org.apache.fineract.portfolio.note.domain.Note;
```

- [ ] **Step 3: Verify compilation**

Run: `cd /Users/eboka.kevin/Documents/work/adv_mifos/fineract-adv && ./gradlew compileJava -x test -x spotlessCheck`

Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Format and commit**

```bash
cd /Users/eboka.kevin/Documents/work/adv_mifos/fineract-adv
./gradlew spotlessApply
git add custom/advancly/savings/service/src/main/java/com/advancly/fineract/portfolio/savings/service/AdvanclySavingsAccountWritePlatformService.java \
        custom/advancly/savings/starter/src/main/java/com/advancly/fineract/portfolio/savings/starter/CoreSavingsWritePlatformServiceDelegateImpl.java
git commit -m "feat: implement bulkTransaction service method with per-item payment detail and receipt-to-ID mapping"
```

---

## Task 8: Bulk Transaction — Service Tests

**Files:**
- Create: `custom/advancly/savings/service/src/test/java/com/advancly/fineract/portfolio/savings/service/AdvanclySavingsAccountWritePlatformServiceBulkTest.java`

- [ ] **Step 1: Write tests for bulkTransaction**

```java
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

import com.advancly.fineract.portfolio.savings.data.BulkTransactionDataValidator;
import com.advancly.fineract.portfolio.savings.domain.AdvanclySavingsAccountAssembler;
import com.advancly.fineract.portfolio.savings.domain.AdvanclySavingsAccountTransactionRepository;
import com.advancly.fineract.portfolio.savings.domain.AssembledSavingsAccount;
import com.advancly.fineract.portfolio.savings.testutil.MoneyHelperInitializer;
import com.advancly.fineract.portfolio.savings.testutil.SavingsAccountTestBuilder;
import com.advancly.fineract.portfolio.savings.testutil.SavingsAccountTransactionTestBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.apache.fineract.infrastructure.core.api.JsonCommand;
import org.apache.fineract.infrastructure.core.data.CommandProcessingResult;
import org.apache.fineract.infrastructure.core.serialization.FromJsonHelper;
import org.apache.fineract.infrastructure.security.service.PlatformSecurityContext;
import org.apache.fineract.organisation.monetary.domain.Money;
import org.apache.fineract.portfolio.note.domain.NoteRepository;
import org.apache.fineract.portfolio.paymentdetail.domain.PaymentDetail;
import org.apache.fineract.portfolio.paymentdetail.domain.PaymentDetailRepository;
import org.apache.fineract.portfolio.paymentdetail.service.PaymentDetailWritePlatformService;
import org.apache.fineract.portfolio.paymenttype.domain.PaymentType;
import org.apache.fineract.portfolio.paymenttype.domain.PaymentTypeRepositoryWrapper;
import org.apache.fineract.portfolio.savings.data.SavingsAccountTransactionDataValidator;
import org.apache.fineract.portfolio.savings.domain.GSIMRepositoy;
import org.apache.fineract.portfolio.savings.domain.SavingsAccount;
import org.apache.fineract.portfolio.savings.domain.SavingsAccountTransaction;
import org.apache.fineract.useradministration.domain.AppUser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AdvanclySavingsAccountWritePlatformServiceBulkTest {

    @Mock
    private PlatformSecurityContext context;
    @Mock
    private SavingsAccountTransactionDataValidator savingsAccountTransactionDataValidator;
    @Mock
    private AdvanclySavingsAccountAssembler assembler;
    @Mock
    private AdvanclySavingsAccountDomainService domainService;
    @Mock
    private AdvanclySavingsAccountTransactionRepository advanclyTransactionRepository;
    @Mock
    private PaymentDetailWritePlatformService paymentDetailWritePlatformService;
    @Mock
    private NoteRepository noteRepository;
    @Mock
    private GSIMRepositoy gsimRepository;
    @Mock
    private SavingsAccountWritePlatformServiceDelegate delegate;
    @Mock
    private PaymentTypeRepositoryWrapper paymentTypeRepositoryWrapper;
    @Mock
    private PaymentDetailRepository paymentDetailRepository;

    private AdvanclySavingsAccountWritePlatformService service;
    private FromJsonHelper fromJsonHelper;
    private BulkTransactionDataValidator bulkValidator;

    @BeforeEach
    void setUp() {
        MoneyHelperInitializer.initialize();
        fromJsonHelper = new FromJsonHelper();
        bulkValidator = new BulkTransactionDataValidator(fromJsonHelper);
        service = new AdvanclySavingsAccountWritePlatformService(context, savingsAccountTransactionDataValidator, assembler, domainService,
                advanclyTransactionRepository, paymentDetailWritePlatformService, noteRepository, gsimRepository, delegate,
                bulkValidator, fromJsonHelper, paymentTypeRepositoryWrapper, paymentDetailRepository);
    }

    @Test
    void testBulkTransaction_twoDeposits_returnsReceiptToIdMap() {
        Long savingsId = 1L;
        SavingsAccount account = new SavingsAccountTestBuilder().withId(savingsId).build();
        AssembledSavingsAccount assembled = new AssembledSavingsAccount(account, new ArrayList<>());

        when(context.authenticatedUser()).thenReturn(Mockito.mock(AppUser.class));
        when(advanclyTransactionRepository.findLastTransactionDate(savingsId)).thenReturn(Optional.empty());
        when(assembler.assembleForAppendPath(savingsId)).thenReturn(assembled);

        PaymentType paymentType = Mockito.mock(PaymentType.class);
        when(paymentTypeRepositoryWrapper.findOneWithNotFoundDetection(1L)).thenReturn(paymentType);
        PaymentDetail paymentDetail = Mockito.mock(PaymentDetail.class);
        when(paymentDetailRepository.saveAndFlush(any(PaymentDetail.class))).thenReturn(paymentDetail);

        SavingsAccountTransaction txn1 = new SavingsAccountTransactionTestBuilder().withId(101L)
                .withRunningBalance(BigDecimal.valueOf(5000)).build();
        SavingsAccountTransaction txn2 = new SavingsAccountTransactionTestBuilder().withId(102L)
                .withRunningBalance(BigDecimal.valueOf(7000)).build();

        when(domainService.handleDepositOptimized(eq(account), eq(LocalDate.of(2026, 4, 5)), eq(BigDecimal.valueOf(5000)), any(), any(),
                any(Money.class), any())).thenReturn(txn1);
        when(domainService.handleDepositOptimized(eq(account), eq(LocalDate.of(2026, 4, 5)), eq(BigDecimal.valueOf(2000)), any(), any(),
                any(Money.class), any())).thenReturn(txn2);

        String json = buildBulkPayload("deposit", "REC-001", 5000, "deposit", "REC-002", 2000);
        JsonCommand command = JsonCommand.fromExistingCommand(null, json, JsonParser.parseString(json), fromJsonHelper, null, savingsId,
                null, null, null, null, null, null, null, null, null, null);

        CommandProcessingResult result = service.bulkTransaction(savingsId, command);

        @SuppressWarnings("unchecked")
        Map<String, Long> txnIds = (Map<String, Long>) result.getChanges().get("transactionIds");
        assertThat(txnIds).containsEntry("REC-001", 101L).containsEntry("REC-002", 102L);
    }

    private String buildBulkPayload(String type1, String receipt1, int amount1, String type2, String receipt2, int amount2) {
        JsonObject payload = new JsonObject();
        payload.addProperty("dateFormat", "dd MMMM yyyy");
        payload.addProperty("locale", "en");
        JsonArray txns = new JsonArray();

        JsonObject txn1 = new JsonObject();
        txn1.addProperty("type", type1);
        txn1.addProperty("transactionDate", "05 April 2026");
        txn1.addProperty("transactionAmount", amount1);
        txn1.addProperty("paymentTypeId", 1);
        txn1.addProperty("receiptNumber", receipt1);
        txns.add(txn1);

        JsonObject txn2 = new JsonObject();
        txn2.addProperty("type", type2);
        txn2.addProperty("transactionDate", "05 April 2026");
        txn2.addProperty("transactionAmount", amount2);
        txn2.addProperty("paymentTypeId", 1);
        txn2.addProperty("receiptNumber", receipt2);
        txns.add(txn2);

        payload.add("transactions", txns);
        return payload.toString();
    }
}
```

**Note:** The `AdvanclySavingsAccountWritePlatformService` constructor needs to be updated to accept the new dependencies. Since it uses `@RequiredArgsConstructor`, adding the new `final` fields is sufficient.

- [ ] **Step 2: Run tests**

Run: `cd /Users/eboka.kevin/Documents/work/adv_mifos/fineract-adv && ./gradlew :custom:advancly:savings:service:test -x spotlessCheck`

Expected: ALL PASS (including all existing 20 tests + new tests)

- [ ] **Step 3: Format and commit**

```bash
cd /Users/eboka.kevin/Documents/work/adv_mifos/fineract-adv
./gradlew spotlessApply
git add custom/advancly/savings/service/src/test/java/com/advancly/fineract/portfolio/savings/service/AdvanclySavingsAccountWritePlatformServiceBulkTest.java
git commit -m "test: add bulk transaction service tests with receipt-to-ID mapping verification"
```

---

## Task 9: Full Build Verification

- [ ] **Step 1: Run full compile**

Run: `cd /Users/eboka.kevin/Documents/work/adv_mifos/fineract-adv && ./gradlew compileJava -x test -x spotlessCheck`

Expected: BUILD SUCCESSFUL

- [ ] **Step 2: Run all custom module tests**

Run: `cd /Users/eboka.kevin/Documents/work/adv_mifos/fineract-adv && ./gradlew :custom:advancly:savings:service:test -x spotlessCheck`

Expected: ALL PASS

- [ ] **Step 3: Run spotless**

Run: `cd /Users/eboka.kevin/Documents/work/adv_mifos/fineract-adv && ./gradlew spotlessApply`

Expected: No unstaged formatting changes remain.

- [ ] **Step 4: Verify no formatting issues**

Run: `cd /Users/eboka.kevin/Documents/work/adv_mifos/fineract-adv && ./gradlew spotlessCheck`

Expected: BUILD SUCCESSFUL
