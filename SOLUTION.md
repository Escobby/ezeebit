# Solution

Tasks chosen: **1 (hold balances), 2 (convert), 3 (withdraw)**. They form a natural progression—you can't convert or withdraw money you can't safely hold—and together they exercise everything required for currency safety, correctness under duplicate or concurrent requests, and a full audit trail.

## Core model

Two ideas run through all three tasks:

**1. Double-entry, immutable ledger.** `Account` (one row per merchant per currency) holds a cached `balanceMinor`, but the *source of truth* is `LedgerEntry`—an append-only table of debits and credits. Nothing is ever updated or deleted there. "How did this balance reach its current value" is always answerable by reading `ledger_entries` for that account, in order. Money is never mixed across currencies: every entry belongs to exactly one `Account`, which belongs to exactly one currency, and amounts are integer minor units (cents / smallest stablecoin unit)—never floating point.

**2. Idempotency via a unique constraint, not a flag.** Every write operation (`DepositService`, `ConversionService`, `WithdrawalService`) takes an `idempotencyKey` and calls `TransactionService.claim()` first. That inserts a `MoneyTransaction` row and relies on a DB-level `UNIQUE(merchant_id, idempotency_key)` constraint: if the dashboard fires the same request twice over a flaky connection, the second insert fails, and the second caller is handed back the *first* request's outcome instead of re-executing anything. This is race-safe by construction—it doesn't depend on a check-then-act sequence in application code, which is exactly the kind of thing that breaks under real concurrency.

Balance mutations are additionally serialized with `SELECT ... FOR UPDATE` (`AccountRepository.findForUpdate`) inside a single local transaction, so two requests touching the *same* account (e.g. two different idempotency keys, or a deposit racing a withdrawal) can't interleave their read-check-write.

**A Spring gotcha worth flagging explicitly:** each of the three `*Service` classes has a matching `*Executor` bean that actually does the transactional work (`DepositExecutor`, `ConversionExecutor`, `WithdrawalExecutor`), rather than the `Service` calling a `@Transactional` method on itself. Spring's `@Transactional` is proxy-based; a class calling its own method (`this.execute(...)`) bypasses the proxy and the annotation is silently ignored. Splitting into a second bean and calling through it is what actually gets you a new transaction boundary. Same reasoning applies to `AccountProvisioner`: it's split out from `AccountService` because catching a failed-insert exception and continuing to use the *same* Hibernate persistence context is unsafe—a flush failure leaves that session unusable—so the "try to create, it's fine if I lose the race" logic needs its own dedicated, isolated transaction.

## Task 1 - holding balances

`DepositService` → `DepositExecutor`: claim idempotency, lock-or-create the target `Account`, credit it, write the `LedgerEntry`, mark the transaction `COMPLETED`. If anything throws, the transaction is marked `FAILED` (still fully auditable—we know a deposit was attempted and why it didn't land).

`GET /merchants/{id}/balances` and `GET /merchants/{id}/balances/{ccy}/statement` expose the current balance and its full ledger history.

## Task 2 - converting currencies

**Quote-then-execute.** `POST /quotes` calls the exchange-rate feed once and locks that price into a short-lived `ConversionQuote` row (TTL default 20s). `POST /conversions` spends a quote id: it re-validates the quote (not expired, not already used, belongs to this merchant) under a row lock, and only *then* checks the merchant's balance and moves the money. This is what stops the platform ending up on the wrong side of a moving market—Ezeebit's price exposure on any single quote is bounded to the TTL window, and the merchant always converts at a price they actually saw.

**Crossing two currencies without breaking double-entry.** A conversion can't be a single balanced ledger posting, because it spans two different currencies' books. The design uses a reserved `SYSTEM` merchant (id `0`, seeded in `V2__seed_reference_data.sql`) with one clearing account per currency, and posts two independent, single-currency balanced entries:

debit  merchant / FROM_CCY   ->  credit SYSTEM / FROM_CCY
debit  SYSTEM   / TO_CCY     ->  credit merchant / TO_CCY

The `SYSTEM` accounts are the one exception to "balances never go negative" (enforced both in `AccountService.debit` and via a DB `CHECK` constraint)—that negative number *is* the platform's real, short-term FX exposure between the two legs.

**Deadlock avoidance.** A conversion locks four accounts (merchant × 2 currencies, system × 2 currencies). Two concurrent conversions between the same currency pair (even in opposite directions) could deadlock if they lock in different orders. `ConversionExecutor` always locks in a fixed, currency-alphabetical order (e.g. `USDT` before `ZAR`).

**Rounding.** Converted amounts are floored (`RoundingMode.DOWN`) to the target currency's minor unit, so the platform never rounds in the merchant's favour by accident.

## Task 3 - withdrawals

Two-phase, because the payout rail is asynchronous:

1. **`initiateWithdrawal`** (`WithdrawalExecutor.reserveFunds`, under the idempotency claim): lock the merchant's account, check they hold the funds, debit immediately, credit a `SYSTEM` "withdrawal suspense" account, write a `PENDING` `Withdrawal` row. Only *after* that transaction commits does the code call the external payout rail—never from inside the DB transaction, so a slow rail call can't hold a lock open.
2. **`handlePayoutResult`** (`WithdrawalExecutor.applyPayoutResult`): the rail's callback. Locks the `Withdrawal` row and checks it's still `PENDING`/`PROCESSING`—if it's already terminal, the call is a no-op, making duplicate notifications safe. On success, the suspense entry is cleared. On failure, funds are returned to the merchant via a **brand new `WITHDRAWAL_REVERSAL` transaction**—reversals are never done by editing original ledger entries.

## How Tasks 4-6 would fit

- **Task 4 (incoming crypto payment).** Slots in as another producer into the same `Account`/`LedgerEntry` model, fed by a blockchain confirmation webhook using the transaction hash as the idempotency key.
- **Task 5 (auto-settle to local currency).** A rule evaluated immediately after a deposit posts: take a percentage of newly credited stablecoins and run them directly through the `ConversionService` pipeline.
- **Task 6 (route payouts across countries/partners).** `WithdrawalExecutor` already separates reserving funds from executing payouts; a `PayoutRouter` sits between them to handle partner selection and retries before triggering a reversal.

## Trade-offs and what I'd do with more time

- **AI for boilerplate code.** AI was used to generate repetitive boilerplate code (like DTOs, basic repository methods, and controllers), allowing time to be focused on core transaction logic, concurrency control, and double-entry correctness.
- **Integration tests.** Add a `@SpringBootTest` + Testcontainers MySQL suite to verify actual SQL execution, Flyway migrations, and multi-threaded lock behavior under real database concurrency.
- **SYSTEM clearing accounts.** Connect clearing accounts to actual treasury balances and split FX clearing from withdrawal suspense accounts.
- **Observability & pagination.** Add pagination to statement endpoints, apply spread margins to conversion quotes, and add metrics tracking quote expiration and reversal rates.