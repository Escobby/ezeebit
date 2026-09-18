# Ezeebit Wallet Service (Take-Home)

Java 17 + Spring Boot 3.3 + MySQL 8, implementing Tasks 1, 2 and 3 from the brief: holding merchant balances across currencies, converting between currencies, and withdrawing funds out of the platform.

See **SOLUTION.md** for the design write-up, trade-offs, and how Tasks 4-6 would slot into the same architecture.

---

## Prerequisites

- **Java 17**
- **Maven 3.9+** (or local `mvn` CLI)
- **MySQL 8** running locally (or via Docker)

---

## 1. Start MySQL

Run the container and wait for MySQL to complete initialization before executing the app:

```bash
docker run --name ezeebit-mysql -e MYSQL_ROOT_PASSWORD=root \
  -e MYSQL_DATABASE=ezeebit_wallet \
  -e MYSQL_USER=ezeebit -e MYSQL_PASSWORD=ezeebit \
  -p 3306:3306 -d mysql:8.0 && \
until docker exec ezeebit-mysql mysqladmin ping -h 127.0.0.1 -u ezeebit -pezeebit --silent; do
  echo "Waiting for MySQL initialization..."
  sleep 2
done
```

*(Or point `src/main/resources/application.yml` at an existing MySQL instance - just update the `spring.datasource` block.)*

---

## 2. Run the App

If port 8080 is occupied by a previously running process, terminate it first:

```bash
kill -9 $(lsof -t -i:8080)
```

Start the service:

```bash
# Using local Maven CLI
mvn spring-boot:run

# Or using the Maven Wrapper (grant execution permissions first if needed)
chmod +x mvnw && ./mvnw spring-boot:run
```

Flyway runs automatically on startup and creates the schema plus seed data:

- **Supported Currencies:** `ZAR`, `NGN`, `KES`, `USDT`, `USDC` *(Note: Requests with non-seeded currencies like `USD` will return `404 NOT_FOUND`)*
- **Reserved Merchant:** `SYSTEM` (id `0`) used internally for double-entry clearing accounts (see `SOLUTION.md`).
- **Demo Merchants:** `1` ("iStore Cape Town") and `2` ("Sunbet Online").

The app listens on `http://localhost:8080`.

---

## 3. Run the Tests

```bash
mvn test
```

Unit tests cover the money-safety logic in isolation (Mockito, no DB required): `AccountService`, `TransactionService`'s idempotency claim/finalize lifecycle, and the three tasks' executors, including duplicate-request and insufficient-funds failure paths.

---

## 4. Try It Out

### Deposit ZAR into Merchant 1
```bash
curl -i -X POST http://localhost:8080/merchants/1/deposits \
  -H 'Content-Type: application/json' \
  -d '{"currency":"ZAR","amountMinor":100000,"idempotencyKey":"dep-1"}'
```

### Test Idempotency Replay (Retry same request)
```bash
curl -i -X POST http://localhost:8080/merchants/1/deposits \
  -H 'Content-Type: application/json' \
  -d '{"currency":"ZAR","amountMinor":100000,"idempotencyKey":"dep-1"}'
```
Returns the existing transaction payload (`HTTP 201`) without throwing session/hibernate errors or duplicating ledger entries.

### Check Balances
```bash
curl -i http://localhost:8080/merchants/1/balances
```

### Test Concurrent Account Provisioning (Merchant 2 with ZAR)
```bash
seq 2 | xargs -n 1 -P 2 -I {} curl -i -X POST http://localhost:8080/merchants/2/deposits \
  -H "Content-Type: application/json" \
  -d '{"currency":"ZAR","amountMinor":50000,"idempotencyKey":"dep-zar-concurrent-{}"}'
```
Verifies `lockOrCreateAccount` handles concurrent creation under parallel threads without deadlocks or lock wait timeouts.

### Full Audit Trail for One Currency
```bash
curl -i http://localhost:8080/merchants/1/balances/ZAR/statement
```

### Convert ZAR to USDT (Quote, then execute)
```bash
QUOTE=$(curl -s -X POST http://localhost:8080/merchants/1/quotes \
  -H 'Content-Type: application/json' \
  -d '{"fromCurrency":"ZAR","toCurrency":"USDT","fromAmountMinor":50000}')
echo $QUOTE

QUOTE_ID=$(echo $QUOTE | grep -o '"quoteId":"[^"]*"' | cut -d'"' -f4)

curl -i -X POST http://localhost:8080/merchants/1/conversions \
  -H 'Content-Type: application/json' \
  -d "{\"quoteId\":\"$QUOTE_ID\",\"idempotencyKey\":\"conv-1\"}"
```

### Withdraw Funds
*(Async - watch the balance/status settle a couple seconds later)*
```bash
curl -i -X POST http://localhost:8080/merchants/1/withdrawals \
  -H 'Content-Type: application/json' \
  -d '{"currency":"ZAR","amountMinor":10000,"destination":"acc-123","idempotencyKey":"wd-1"}'
```
The response comes back `PENDING` immediately (funds are already reserved). `MockPayoutRailClient` resolves it a couple of seconds later - poll balances or the transaction to see it settle to `COMPLETED` (or `FAILED`, ~10% of the time, simulating an unreliable rail - the reserved funds are then credited back automatically).

---

## Project Layout

```
domain/                  JPA entities (Account, MoneyTransaction, LedgerEntry, ConversionQuote, Withdrawal, ...)
repository/              Spring Data repositories, including the pessimistic-lock queries
service/                 Business logic - see SOLUTION.md for the design
service/exchangerate,
service/payout           Mocked "already exists" dependencies
web/                     REST controllers + DTOs + error handling
resources/db/migration   Flyway schema + seed data
```