# Savings Account Transaction Optimization — Design Spec

**Date:** 2026-04-04  
**Status:** Approved  
**Branch:** `feature/savings-optimization-custom-module`

## Problem

Savings account deposits and withdrawals become slower as transactions accumulate. Every deposit/withdrawal triggers:

1. Loading ALL transactions from the account's history into memory
2. `recalculateDailyBalances()` — iterates all loaded transactions to recompute running balances
3. `calculateInterestUsing()` — builds posting periods, calculates interest for each period
4. `updateSummary()` — 11 separate full-list iterations to compute totals (deposits, withdrawals, fees, etc.)
5. `validateAccountBalanceDoesNotBecomeNegative()` — another full iteration for withdrawals

An initial optimization was implemented directly in core Fineract (commits `235866db1` through `f6ea37f2f` on the `savings_transaction_deposit_and_withdrawal_optimization` branch). This introduced a pivot-based loading strategy and an `assembleFromOptimized` path, but it modified core classes directly.

## Goals

1. Move all optimization logic into a Fineract custom module (`custom/advancly/savings/`)
2. Revert core Fineract to stock (clean separation)
3. Introduce an O(1) append path for the common case (transaction date >= last existing transaction)
4. Maintain correctness for backdated transactions respecting interest posting boundaries
5. Toggle via configuration property

## Approach

**Approach B: Service Layer Override + Helper/Delegate Classes**

Override `SavingsAccountWritePlatformService`, `SavingsAccountDomainService`, and `SavingsAccountAssembler` in the custom module. For entity-level calculation logic (running balances, balance validation), create a helper class that operates on the entity from outside rather than subclassing it.

### Why Not Other Approaches

- **Approach A (Service Only):** Can't override entity methods like `calculateInterestUsing`, `validateAccountBalanceDoesNotBecomeNegative` without duplication or subclassing
- **Approach C (Entity Subclass):** JPA entity inheritance is fragile — Hibernate doesn't handle transient subclasses well, and existing queries return `SavingsAccount`

## Architecture

### Module Structure

```
custom/advancly/savings/
├── service/                              — core logic
│   ├── build.gradle
│   ├── dependencies.gradle
│   └── src/
│       ├── main/java/com/advancly/fineract/portfolio/savings/
│       │   ├── domain/
│       │   │   ├── AdvanclySavingsAccountAssembler.java
│       │   │   └── AdvanclySavingsAccountTransactionRepository.java
│       │   ├── helper/
│       │   │   └── SavingsAccountTransactionHelper.java
│       │   └── service/
│       │       ├── AdvanclySavingsAccountDomainService.java
│       │       ├── AdvanclySavingsAccountWritePlatformService.java
│       │       └── AdvanclySavingsSchedularInterestPoster.java
│       └── test/java/com/advancly/fineract/portfolio/savings/
│           ├── helper/
│           │   └── SavingsAccountTransactionHelperTest.java
│           ├── domain/
│           │   └── AdvanclySavingsAccountAssemblerTest.java
│           ├── service/
│           │   ├── AdvanclySavingsAccountDomainServiceTest.java
│           │   ├── AdvanclySavingsAccountWritePlatformServiceTest.java
│           │   └── AdvanclySavingsSchedularInterestPosterTest.java
│           └── testutil/
│               ├── SavingsAccountTestBuilder.java
│               ├── SavingsAccountTransactionTestBuilder.java
│               └── SavingsAccountSummaryTestBuilder.java
├── starter/                              — Spring auto-configuration
│   ├── build.gradle
│   ├── dependencies.gradle
│   └── src/main/
│       ├── java/com/advancly/fineract/portfolio/savings/starter/
│       │   └── AdvanclySavingsAutoConfiguration.java
│       └── resources/META-INF/spring/
│           └── org.springframework.boot.autoconfigure.AutoConfiguration.imports
```

### Bean Override Strategy

| Bean | Core Class | Custom Replacement | Override Mechanism |
|------|-----------|-------------------|-------------------|
| `SavingsAccountWritePlatformService` | `...JpaRepositoryImpl` | `AdvanclySavingsAccountWritePlatformService` | Already `@ConditionalOnMissingBean` in `SavingsConfiguration` |
| `SavingsAccountDomainService` | `SavingsAccountDomainServiceJpa` | `AdvanclySavingsAccountDomainService` | Needs core change: move to `SavingsConfiguration` with `@ConditionalOnMissingBean` |
| `SavingsAccountAssembler` | `SavingsAccountAssembler` | `AdvanclySavingsAccountAssembler` | Needs core change: move to `SavingsConfiguration` with `@ConditionalOnMissingBean` |
| `SavingsSchedularInterestPoster` | `SavingsSchedularInterestPoster` | `AdvanclySavingsSchedularInterestPoster` | Needs core change: move to `SavingsConfiguration` with `@ConditionalOnMissingBean` |

### Configuration

Enabled via property:
```properties
advancly.savings.optimization.enabled=true
```

When absent or `false`, core Fineract beans are used unchanged.

## Transaction Processing Paths

### Path Selection

```
transactionDate >= lastTransactionDate?
  ├─ YES → Append path (O(1))
  └─ NO  → Insert path (O(k))
             ├─ Case A: transactionDate >= lastInterestPostingDate
             │    → Partial recalculation, no interest re-posting
             ├─ Case B: transactionDate < lastInterestPostingDate
             │          AND backdatedTxnsAllowedTill = true
             │    → Constrained by pivotDate - relaxingDays
             │    → Reverse and re-post interest for affected periods
             └─ Case C: transactionDate < lastInterestPostingDate
                        AND backdatedTxnsAllowedTill = false
                  → Full recalculation from transactionDate forward
```

### O(1) Append Path

Transaction goes at the end of the timeline. No existing running balances affected.

1. **Load nothing** from transaction history — query last transaction's `running_balance_derived` via single indexed query
2. **Running balance** = last running balance +/- amount
3. **Summary update** = incremental: add/subtract from existing totals using `updateSummaryWithPivotConfig` switch pattern
4. **Balance validation** (withdrawals) = check `summary.accountBalance - amount >= minRequiredBalance`
5. **Interest recalculation** = skip for zero-interest accounts. For interest-bearing, only if `isBeforeLastPostingPeriod` is true

### O(k) Insert Path

Transaction lands in the middle of the timeline.

**Case A: After last interest posting (safe zone)**
1. Load transactions from `transactionDate` onward
2. Get running balance just before `transactionDate` via single query
3. Recalculate running balances from that point — O(k)
4. Incremental summary update — O(1)
5. Balance validation from that point — O(k)
6. Interest recalculation only for current unposted period

**Case B: Before interest posting, with pivot enabled**
1. Validate `transactionDate >= pivotDate - relaxingDays` (else reject)
2. Load from `pivotDate - relaxingDays`
3. Recalculate running balances — O(k)
4. Reverse and re-post interest for affected periods via `postInterest()`
5. Incremental summary update

**Case C: Before interest posting, no pivot guard**
1. Load from `transactionDate` forward
2. Full `calculateInterestUsing` + `postInterest` from that point
3. This is the slowest case but unavoidable — interest correctness requires full recalculation

## Custom Classes

### SavingsAccountTransactionHelper

Replaces entity-level calculation methods. Operates on `SavingsAccount` from outside.

Methods:
- `recalculateDailyBalancesFromDate(account, fromDate, openingBalance)` — O(k) running balance recalculation
- `validateBalanceDoesNotBecomeNegative(account, fromDate, openingBalance, transactionAmount, onHoldTransactions)` — O(k) balance validation
- `validateBalanceForAppendPath(account, transactionAmount)` — O(1) balance check
- `updateSummaryIncremental(account, transaction)` — wraps `updateSummaryWithPivotConfig` switch logic
- `isBeforeLastPostingPeriod(account, transactionDate)` — uses pre-loaded interest/overdraft transactions
- `getLastTransactionDate(account)` — for path decision
- `setRunningBalanceForAppendPath(account, transaction, lastRunningBalance)` — sets running balance without iterating

### AdvanclySavingsAccountAssembler

Absorbs optimized loading logic from commits `81f400d15`, `197ada46c`, `4df9d7fad`, `cda6ccdf2`, `2add3a801`.

Key methods:
- `assembleForAppendPath(savingsId)` — loads NO transactions, just account + summary + last running balance
- `assembleForInsertPath(savingsId, transactionDate)` — loads transactions from date onward + running balance before
- `loadTransactionsForStartInterestCalculationDate(account)` — migrated from core
- Full `assembleFrom` and `loadTransactionsToSavingsAccount` for non-optimized paths (interest posting jobs, etc.)

### AdvanclySavingsAccountDomainService

Overrides `handleDeposit()` and `handleWithdrawal()` with path selection logic. Delegates calculations to `SavingsAccountTransactionHelper`. Falls back to full `calculateInterestUsing` + `postInterest` for Cases B and C.

### AdvanclySavingsAccountWritePlatformService

Overrides `deposit()` and `withdrawal()`. Uses custom assembler and optimized pivot date validation. Other methods (reverse, undo, close, charges) delegate to core implementation.

### AdvanclySavingsSchedularInterestPoster

Absorbs journal entry posting fixes for withhold tax and interest posting reversal (commit `04c3d0dd4`), and the not-reversed condition fix (commit `21911c0b1`).

## Repository Layer

### AdvanclySavingsAccountTransactionRepository

New queries for O(1) append path:
- `findLastNonReversedTransaction(savingsAccountId)` — single row, for last running balance
- `findLastTransactionDate(savingsAccountId)` — just the date, for path decision

Queries migrated from core commits:
- `findNonInterestTransactionBeforePivotDate` — `typeOf NOT IN (3, 10, 17, 18)` variant
- `findTransactionsBeforePivotDate` with `hasInterestRate` flag
- `findNonReversedInterestAndOverdraftTransactions`

Queries for O(k) insert path:
- `findTransactionsOnOrAfterDate(savingsAccountId, date)` — without PESSIMISTIC_WRITE for read scenarios
- `findRunningBalanceBeforeDate(savingsAccountId, date)` — single row opening balance

### Lock Strategy

- Append path: `PESSIMISTIC_WRITE` only on account row (via `savingsAccountRepository.save()`), no row locks on transaction reads
- Insert path: `PESSIMISTIC_WRITE` on affected transaction rows (updating running balances)

### No Schema Changes

All queries work against existing tables and columns. No Liquibase migrations needed.

## Core Changes

### Revert 10 Commits

Revert changes from commits `235866db1`, `cda6ccdf2`, `2add3a801`, `37e305766`, `4bad4b934`, `81f400d15`, `197ada46c`, `4df9d7fad`, `04c3d0dd4`, `21911c0b1` from core modules.

**Keep in core:** `f6ea37f2f` (ordering fix on `AbstractAuditableWithUTCDateTimeCustom`) — framework-level bug fix.

### Bean Registration Changes

In `SavingsConfiguration.java`, add `@ConditionalOnMissingBean` registration for:
- `SavingsAccountDomainService` → `SavingsAccountDomainServiceJpa`
- `SavingsAccountAssembler` → `SavingsAccountAssembler`
- `SavingsSchedularInterestPoster` → `SavingsSchedularInterestPoster`

Remove `@Service` from these 3 classes.

### Visibility Changes

On `SavingsAccount.java`:
- `hasInterestCalculation()` — `private` → `public`
- `hasOverdraftInterestCalculation()` — `private` → `public`

## Testing

### Unit Tests (in custom module)

**SavingsAccountTransactionHelperTest:**
- Running balance recalculation from date (with and without overdraft)
- Balance validation for append path (sufficient, insufficient, min balance, hold amount)
- Balance validation for insert path (mid-timeline negative detection)
- Incremental summary update (deposit, withdrawal, withdrawal fee, withhold tax)
- `isBeforeLastPostingPeriod` (true, false, no postings)
- Running balance setting for append path

**AdvanclySavingsAccountAssemblerTest:**
- Append path loads no transactions, sets running balance from last transaction
- Insert path loads from transaction date, sets opening balance from before-pivot query
- Relaxing days adjustment
- No pivot date falls back to all transactions
- `startInterestCalculationDate` assembly and filtering

**AdvanclySavingsAccountDomainServiceTest:**
- Deposit/withdrawal append path (zero-interest, with-interest)
- Insert path Cases A, B, C
- Withdrawal insufficient balance on append path
- Insert path causing historical negative balance
- Pivot date validation
- Journal entries and business events still posted

**AdvanclySavingsAccountWritePlatformServiceTest:**
- Custom assembler used for deposit/withdrawal
- GSIM balance updates
- Optimized pivot date validation for zero-interest

**AdvanclySavingsSchedularInterestPosterTest:**
- Withhold tax journal entries
- Interest posting reversal journal entries
- Not-reversed condition
- `startInterestCalculationDate` respected

### Test Utilities
- `SavingsAccountTestBuilder` — configurable account entities
- `SavingsAccountTransactionTestBuilder` — configurable transactions
- `SavingsAccountSummaryTestBuilder` — configurable summaries

## Dependencies

**Service module:**
- `fineract-core`, `fineract-savings`, `fineract-provider` (implementation)
- Spring Boot autoconfigure (compileOnly)
- Spring Boot test, Mockito, AssertJ (test)

**Starter module:**
- Custom service module (implementation)
- Spring Boot starter, Spring Boot starter-data-jpa (implementation)
