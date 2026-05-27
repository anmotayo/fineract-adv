# Optimized Account Transfers - Design Spec

**Date:** 2026-05-27
**Status:** Draft for review

## Problem

The Advancly savings custom module currently optimizes direct savings deposits, direct withdrawals, and the custom bulk transaction endpoint. Native Fineract account transfers still call `SavingsAccountDomainService.handleWithdrawal(...)` and `handleDeposit(...)`, and the Advancly implementation delegates those methods to the core `SavingsAccountDomainServiceJpa`.

That means account transfers do not use the O(1) append path, even when the savings posting is a simple forward-dated transfer posting.

The optimized append path must be used when the new transaction date is the same day as, or later than, the last non-accrual transaction date on the savings account. Accrual transactions must not make a normal posting look backdated.

## Goals

1. Add optimized savings posting support to native Fineract account transfers.
2. Preserve native account transfer records, command handlers, audit behavior, and loan-side behavior.
3. Use the optimized path when `transactionDate >= lastNonAccrualTransactionDate`.
4. Exclude accrual transactions from the last-transaction-date decision.
5. Apply the same same-day-or-after path-selection rule to direct deposit, direct withdrawal, bulk savings transactions, and account transfers.
6. Keep the optimization behind `advancly.savings.optimization.enabled`.

## Non-Goals

1. Do not change loan repayment, loan refund, or loan disbursement behavior except where a savings-side posting is paired with it.
2. Do not optimize loan-to-loan transfers.
3. Do not change the public account transfer API contract.
4. Do not add schema changes.
5. Do not route account transfers through the custom bulk transaction endpoint.

## Recommended Approach

Add an Advancly implementation of `AccountTransfersWritePlatformService` and register it from the custom module. Fineract already creates the default account transfer service with `@ConditionalOnMissingBean(AccountTransfersWritePlatformService.class)`, so the Advancly bean cleanly replaces the core bean when the custom module is enabled.

Avoid subclassing `AccountTransfersWritePlatformServiceImpl`. It is an orchestration service with several transfer types and many collaborators. A composition-based replacement is clearer: implement only the savings-touching transfer paths with optimized savings posting, and preserve or delegate behavior for the rest.

## Architecture

### New Shared Savings Posting Component

Create a focused component named `AdvanclyOptimizedSavingsPostingService` in the custom savings module.

Responsibilities:

- Decide whether a savings posting is append-path eligible.
- Assemble the savings account through `AdvanclySavingsAccountAssembler`.
- Call `AdvanclySavingsAccountDomainService.handleDepositOptimized(...)` or `handleWithdrawalOptimized(...)`.
- Keep direct savings write service, bulk transactions, and account transfers on the same path-selection rule.

The path-selection method should be explicit:

```java
boolean isAppendPath(Long savingsId, LocalDate transactionDate) {
    Optional<LocalDate> lastTxnDate = transactionRepository.findLastNonAccrualTransactionDate(savingsId);
    return lastTxnDate.isEmpty() || !transactionDate.isBefore(lastTxnDate.get());
}
```

The repository query already excludes accrual transaction type `10`; rename or wrap it so call sites read as `findLastNonAccrualTransactionDate(...)`.

### Account Transfer Override

Create an Advancly `AccountTransfersWritePlatformService` bean. It will be active only when `advancly.savings.optimization.enabled=true`.

Place the implementation in `com.advancly.fineract.portfolio.account.service.AdvanclyAccountTransfersWritePlatformService` and register it explicitly with a `@Bean` method in `AdvanclySavingsAutoConfiguration`. This keeps the package aligned with the account transfer domain while avoiding a wider component scan.

Optimized behavior by transfer type:

| Transfer Type | Savings Side | Optimized Behavior |
|---------------|--------------|--------------------|
| Savings to savings | source withdrawal and destination deposit | Check append eligibility independently for each savings account, then post optimized withdrawal and optimized deposit |
| Savings to loan | source withdrawal | Post optimized savings withdrawal, keep loan repayment logic unchanged |
| Loan to savings | destination deposit | Keep loan refund/disbursement logic unchanged, post optimized savings deposit |
| Loan to loan | none | Preserve existing behavior, no savings optimization needed |

The service must continue to create `AccountTransferDetails` through `AccountTransferAssembler` and save it through `AccountTransferDetailRepository`.

### Direct Savings Path Alignment

Update the existing direct optimized paths so they use the shared same-day-or-after append decision:

- `AdvanclySavingsAccountWritePlatformService.deposit(...)`
- `AdvanclySavingsAccountWritePlatformService.withdrawal(...)`
- `AdvanclySavingsAccountWritePlatformService.bulkTransaction(...)`
- `AdvanclySavingsAccountDomainService.handleDepositOptimized(...)`
- `AdvanclySavingsAccountDomainService.handleWithdrawalOptimized(...)`

The direct write service should choose append vs insert using the shared helper. The domain service should either trust the already-assembled account shape or use the same helper internally as a defensive check. The important invariant is that same-day postings continue to take the O(1) append branch.

## Transfer Flow Details

### Savings To Savings

1. Validate the account transfer command as core does today.
2. Parse transfer date, amount, account types, and account IDs.
3. Assemble both savings accounts for a lightweight currency check before posting.
4. If currencies differ, fail before creating either savings transaction.
5. Create the optimized withdrawal from the source savings account.
6. Create the optimized deposit into the destination savings account.
7. Create and persist `AccountTransferDetails` linking both savings transactions.
8. Return the same style of `CommandProcessingResult` as the core implementation.

Each savings account chooses append or insert path independently. One side can be append-path eligible while the other side uses insert path.

### Savings To Loan

1. Validate and parse as core does today.
2. Create the optimized source savings withdrawal.
3. Create the loan repayment, charge payment, or down payment using existing loan domain services.
4. Create and persist `AccountTransferDetails`.

Loan behavior, external ID handling, holiday validation flags, and charge-payment behavior remain unchanged.

### Loan To Savings

1. Validate and parse as core does today.
2. Create the loan refund or disbursement using existing loan domain services.
3. Create the optimized destination savings deposit.
4. Create and persist `AccountTransferDetails`.
5. Preserve GSIM parent balance update behavior for destination savings accounts with GSIM.

## Path Selection Rule

For every optimized savings posting:

```text
lastDate = max(dateOf) for non-reversed, non-reversal, non-accrual savings transactions

if lastDate is absent:
    use append path
else if transactionDate is after lastDate:
    use append path
else if transactionDate is equal to lastDate:
    use append path
else:
    use insert path
```

Accrual transaction type `10` is excluded from `lastDate`. Interest and overdraft transactions remain included unless they are accruals, because they affect the transaction timeline.

Same-day postings use append path.

## Error Handling And Atomicity

Account transfer methods remain transactional. If any side fails, the entire transfer rolls back.

Important failure behavior:

- Currency mismatch fails before savings transactions are created.
- Source savings insufficient balance fails before destination postings are created.
- Loan-side failures roll back the already-created savings posting because the method is transactional.
- Account transfer detail persistence failures roll back all created postings.

## Testing

### Unit Tests

Add tests for the shared savings posting component:

- Empty last non-accrual date uses append path.
- Transaction date after last non-accrual date uses append path.
- Transaction date equal to last non-accrual date uses append path.
- Transaction date before last non-accrual date uses insert path.
- Accrual transactions are excluded by the repository method or repository wrapper contract.

Add tests for direct savings behavior:

- Direct deposit on same date as last non-accrual transaction assembles append path.
- Direct withdrawal on same date as last non-accrual transaction assembles append path.
- Bulk transaction where earliest item is same date as last non-accrual transaction assembles append path.

Add tests for account transfer behavior:

- Savings-to-savings calls optimized withdrawal for source and optimized deposit for destination.
- Savings-to-savings evaluates path selection separately for source and destination.
- Savings-to-savings currency mismatch fails before optimized postings are called.
- Savings-to-loan calls optimized savings withdrawal and existing loan repayment logic.
- Loan-to-savings calls existing loan refund/disbursement logic and optimized savings deposit.
- Loan-to-savings GSIM parent balance update is preserved.
- Loan-to-loan behavior is unchanged or delegated.

### Wiring Test

Add a Spring context test or focused bean registration test proving that, when `advancly.savings.optimization.enabled=true`, the active `AccountTransfersWritePlatformService` is the Advancly implementation and the core default is not registered.

### Regression Test Command

Use the custom module tests as the first verification target:

```bash
./gradlew :custom:advancly:savings:service:test -x spotlessCheck
```

Then run the provider account transfer tests if available, or the smallest provider test slice covering `AccountTransfersWritePlatformServiceImpl` behavior.

## Risks

The main risk is behavioral drift from core `AccountTransfersWritePlatformServiceImpl`. To control it, copy only the orchestration that is required for the savings-touching paths, keep the same collaborators and assembler calls, and add tests for the observable transfer results.

The second risk is inconsistent path selection between direct savings operations and transfers. The shared savings posting component exists specifically to prevent that.
