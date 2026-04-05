# Single-Loop Summary Update & Bulk Transactions API — Design Spec

## Overview

Two features for the custom Advancly savings module:

1. **Single-loop summary update** — Replace the 12-pass summary recalculation with a single-pass implementation
2. **Bulk transactions API** — New endpoint accepting mixed deposits and withdrawals in a single atomic request, routed through Fineract's command infrastructure for maker-checker support

---

## Feature 1: Single-Loop Summary Update

### Problem

`SavingsAccountSummary.updateSummary()` delegates to `SavingsAccountTransactionSummaryWrapper`, which has 11 separate methods each iterating the full transaction list. Combined with `updateRunningBalanceAndPivotDate()`, this totals 12 passes over the same list.

### Solution

New method in the custom module's `SavingsAccountTransactionHelper`:

```
calculateAndUpdateSummaryInSinglePass(SavingsAccount account, List<SavingsAccountTransaction> transactions, MonetaryCurrency currency)
```

**Behavior:**
- Single iteration over the transaction list
- Accumulates all 11 totals (deposits, withdrawals, interest posted, withdrawal fees, annual fees, fees charged, fees waived, penalties charged, penalties waived, overdraft interest, withhold tax) into local `Money` variables using existing type-check methods (`isDeposit()`, `isWithdrawal()`, `isInterestPosting()`, etc.)
- Computes running balance and last interest posting date in the same pass (replacing `updateRunningBalanceAndPivotDate()`)
- Sets all values on `SavingsAccountSummary` at the end via existing setters

**Usage:**
- Called from `AdvanclySavingsAccountDomainService` for the **insert path** (where full recalculation is needed)
- The **append path** continues using the existing incremental `updateSummaryWithPivotConfig()` since it is already O(1) for single transactions

---

## Feature 2: Bulk Transactions API

### Endpoint

```
POST /v1/savingsaccounts/{savingsId}/bulk-transactions
```

A dedicated path under the custom module's API resource (`AdvanclySavingsAccountTransactionsApiResource`), avoiding JAX-RS path conflicts with the core `SavingsAccountTransactionsApiResource` at `/v1/savingsaccounts/{savingsId}/transactions`.

### Request Payload

```json
{
  "dateFormat": "dd MMMM yyyy",
  "locale": "en",
  "transactions": [
    {
      "type": "deposit",
      "transactionDate": "05 April 2026",
      "transactionAmount": 5000,
      "paymentTypeId": 1,
      "receiptNumber": "REC-001",
      "accountNumber": "...",
      "note": "optional"
    },
    {
      "type": "withdrawal",
      "transactionDate": "05 April 2026",
      "transactionAmount": 2000,
      "paymentTypeId": 2,
      "receiptNumber": "REC-002"
    }
  ]
}
```

**Field rules:**
- `type`: Required. Only `deposit` or `withdrawal` allowed.
- `receiptNumber`: Required. Must be unique within the batch.
- `transactionDate`, `transactionAmount`, `paymentTypeId`: Required per item.
- `accountNumber`, `checkNumber`, `routingCode`, `bankNumber`, `note`: Optional per item.
- `dateFormat`, `locale`: Shared at top level.

### Response

```json
{
  "officeId": 1,
  "clientId": 1,
  "savingsId": 1,
  "changes": {
    "transactionIds": {
      "REC-001": 101,
      "REC-002": 102,
      "REC-003": 103
    }
  }
}
```

The `transactionIds` map uses receipt number as key and transaction ID as value, so callers can correlate results without relying on array position.

### Command Infrastructure

| Component | Detail |
|-----------|--------|
| `CommandWrapperBuilder` | New method `savingsAccountBulkTransaction(savingsId)` — action `"BULKTRANSACTION"`, entity `"SAVINGSACCOUNT"` |
| Command Handler | `@CommandType(entity = "SAVINGSACCOUNT", action = "BULKTRANSACTION")` — calls `writePlatformService.bulkTransaction(savingsId, command)` |
| Interface | New `bulkTransaction(Long savingsId, JsonCommand command)` on `SavingsAccountWritePlatformService` with default implementation throwing `UnsupportedOperationException` |
| API Resource | New `AdvanclySavingsAccountTransactionsApiResource` at `/v1/savingsaccounts/{savingsId}/bulk-transactions` in the custom module. Dedicated path avoids JAX-RS conflicts with core's transactions resource. |

### Service Logic — `AdvanclySavingsAccountWritePlatformService.bulkTransaction()`

1. Authenticate user, validate payload via `BulkTransactionDataValidator`
2. Parse `transactions` array from `JsonCommand`
3. Determine path: find earliest `transactionDate` in batch, compare to last existing transaction date -> append or insert
4. Assemble account once via `AdvanclySavingsAccountAssembler`
5. Validate account blocks (`validateForAccountBlock`, credit/debit blocks as needed)
6. Loop through transactions in order:
   - Create `PaymentDetail` per item
   - If `deposit`: call `handleDepositOptimized()`, update running balance
   - If `withdrawal`: validate balance, call `handleWithdrawalOptimized()`, update running balance
   - Store receipt number -> transaction ID mapping
7. Handle notes per transaction if provided
8. Return `CommandProcessingResult` with `transactionIds` map in changes

### Validation — `BulkTransactionDataValidator`

- `transactions` array is present and non-empty
- Each item has required fields: `type`, `transactionDate`, `transactionAmount`, `paymentTypeId`, `receiptNumber`
- `type` is only `deposit` or `withdrawal`
- `receiptNumber` is unique within the batch
- `transactionAmount` is positive
- Standard date format validation

### Atomicity

Single `@Transactional` on `bulkTransaction()`. Any failure (insufficient balance, validation error, etc.) rolls back all transactions in the batch.

### Maker-Checker

One approval/rejection covers the entire batch. Individual transactions within the batch are not independently approvable — this is intentional since the batch represents a single business action.
