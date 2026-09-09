# Banking Transaction Processor

Deposits, withdrawals and transfers over accounts in Postgres, with an append-only per-account
ledger and a REST API.

## 1. Understanding the problem

A **banking transaction processor**: a service that holds account balances and moves money between
them, where every movement is recorded and the recorded history explains the balance.

Read plainly, the brief asks for six behaviours — open accounts, deposit, withdraw, transfer,
reject invalid operations, and expose balance and history over an API. 

## 2. Scope

### In scope

| Area | Covered |
|---|---|
| Accounts | Open with an initial balance; read the balance |
| Operations | Deposit, withdraw, transfer between two accounts |
| Validation | Positive, non-zero, two-decimal amounts; per-transaction ceiling; zero balance floor |
| Ledger | Append-only, per account, ordered, timestamped, including the opening entry |
| Query | Balance by id; paginated transaction history per account |
| Concurrency | Correct under concurrent operations on one account, and on two accounts in opposite directions |
| Persistence | Survives a process restart |

### Out of scope, by decision

Each of these is a deliberate cut, not an oversight. 

| Not building | Why not                                                                                                                      |
|---|------------------------------------------------------------------------------------------------------------------------------|
| **Idempotency keys** | The one real gap rather than a scope choice. It can be handled at Checking level or API Gateway level                        |
| Authentication / authorisation | No caller identity in the brief, so no ownership rule to enforce.                                                            |
| Multi-currency | Makes every amount a (value, currency) pair and gives transfers an FX rate source and a rounding policy — a second exercise. |
| Closing accounts | Nothing in the brief needs it, and it puts a lifecycle state in front of every operation.                                    |
| Interest, fees, scheduled or reversed transactions | Not asked for. A reversal is a new compensating entry, never an edit.                                                        |
| AML, fraud screening, rate limiting | Real requirements in this domain, none of them in this brief.                                                                |
| Keyset pagination, metrics, tracing | Covered in Part 6 with what each would take.                                                                                 |

### Assumptions

1. **Single currency.** Amounts are bare decimals.
2. **No caller identity.** Any request may operate on any account.
3. **Accounts are opened and never closed.**
4. **One logical service** — though the correctness argument has to survive more than one instance
   of it.
5. **A per-transaction ceiling exists** as an operational sanity limit. It caps a single movement,
   not what an account may hold.

## 3. Functional requirements

Six, each stated as a behaviour a test can fail. Not an itemised checklist of every rule — the
individual rules are the edge-case table in §7, and a requirement list that repeats them is a
longer document saying the same thing twice.

| | Requirement | Satisfied when |
|---|---|---|
| **F1** | **Accounts** exist with a unique id and a balance | Opened with an initial balance, and read back by id |
| **F2** | **Deposits and withdrawals** move one balance by exactly the amount | New balance = old ± amount, never off by a fraction |
| **F3** | **Transfers** move an amount between two accounts, atomically | Both balances change or neither does |
| **F4** | **Invalid operations are rejected and change nothing** | Overdraft, zero, negative, over-precise, over the ceiling, self-transfer — balance unchanged, no ledger entry, and a status that says which kind of wrong it was |
| **F5** | **Every balance change appends a ledger entry**, and entries are never altered | One entry per account affected, recording type, amount, resulting balance and time; replaying from zero reconstructs the balance |
| **F6** | **Balance and history are queryable** over an API | Balance by id; history per account, ordered and paginated |

## 4. Non-functional requirements

Six. The first is the exercise; the rest are what it takes for the first one to mean anything.

| | Requirement | How it is met |
|---|---|---|
| **N1** | **Correct under concurrency.** Concurrent operations on one account never lose an update and never overdraw, and opposing transfers never deadlock | One transaction per operation, reading the account row `FOR UPDATE`; transfers take both locks in one global order |
| **N2** | **The guarantee outlives one process.** It must not rest on a single JVM, and state survives a restart | The lock and the data both live in the database, not in application memory |
| **N3** | **Atomic and auditable.** No partial transfer is ever observable, and a balance always agrees with its history | Both accounts validated before either changes; the ledger append is in the same transaction as the balance change |
| **N4** | **Exact arithmetic.** No representation error, ever | `BigDecimal` at scale 2 end to end, `numeric(19,2)` in the column, no `double` anywhere |
| **N5** | **Failures are diagnosable.** A client branches on a code, not on prose, and a bug still looks like a bug | A stable `errorCode` per rule; an unexpected exception is a 500 with a stack trace rather than a tidy body |
| **N6** | **The claim is demonstrable, not asserted** — and the code reads as the argument it makes | Tests run against a real Postgres, and both concurrency guarantees are checked by deliberately breaking them (Part 5). No layer or wrapper without a caller that needs it |

*Explicitly not requirements here:* throughput and latency targets, availability, horizontal scale.
No numbers were given, and inventing a target to design against would be theatre. The design does
have a known throughput characteristic — a row lock serialises operations on one hot account — and
that belongs in Part 6 as a stated limitation, not dressed up here as a requirement met.

## 5. The data model

Two things are stored: an **account**, and the **ledger entries** that record every change to it.
An **amount of money** is a third noun, but it is never stored on its own — it is always an
attribute of one of the other two.

```
              +-----------------------------------------+
              |                 ACCOUNT                 |
              +-----------------------------------------+
              |  PK   account_id        uuid            |
              |       balance           decimal(19,2)   |
              |       opened_at         instant         |
              +--------------------+--------------------+
                                   |
                                   | 1
                                   |
                                   |  every change to an account
                                   |  is recorded by an entry
                                   |
                                   | 1..*   an account always has at
                                   |        least the entry that opened it
                                   |
              +--------------------+--------------------+
              |               LEDGER_ENTRY              |
              +-----------------------------------------+
              |  PK   entry_id          uuid            |
              |       sequence          long            |
              |  FK   account_id        uuid            |
              |       type              enum            |
              |       amount            decimal(19,2)   |
              |       balance_after     decimal(19,2)   |
              |       occurred_at       instant         |
              |       transfer_id       uuid, nullable  |
              +-----------------------------------------+
```

`type` is one of ACCOUNT_OPENED, DEPOSIT, WITHDRAWAL, TRANSFER_OUT, TRANSFER_IN. `sequence` is
what puts the entries in order, and `transfer_id` is set only on the two entries that make up a
transfer, absent on every other.

An amount is always positive and the *type* says which direction it moved. Signed amounts would
make the same fact expressible two ways — a withdrawal of 50 and a deposit of -50 — and every
reader would have to know which convention was in use.

### Relationships

| | |
|---|---|
| Account → ledger entries | One to many. Every account has at least one entry, because opening it records one. |
| Ledger entry → account | Exactly one, and it never changes. |
| The two halves of a transfer | Two entries in two different accounts, sharing a transfer id. |

**A transfer is not a third table.** It produces one outgoing and one incoming entry, joined by
that shared id. Giving it a row of its own would create a second place where the same movement is
recorded, and therefore a way for the two records to disagree — which is exactly the thing a ledger
exists to prevent.

```
   account A                                  account B
   +--------------------------+               +--------------------------+
   |  type    TRANSFER_OUT    |               |  type    TRANSFER_IN     |
   |  amount        50.00     |               |  amount        50.00     |
   |  transfer_id   t-9f3c    | ------------- |  transfer_id   t-9f3c    |
   +--------------------------+       |       +--------------------------+
                                      |
                       the shared id is the only thing
                       joining the two halves together
```

**`sequence` orders the entries; the timestamp does not.** The history has to be readable in the
order the balances actually changed, and a timestamp cannot carry that: two entries can land in the
same millisecond, and a clock can move backwards. So ordering is a separate, strictly increasing
value, and `occurred_at` is for humans.

### Lifecycle

- An account moves one way only: opened, then in use. There is no closed state (§2).
- A ledger entry is **append-only**. Nothing updates one and nothing deletes one. A correction is a
  new entry that compensates, so the mistake and the fix are both visible.

### The rules that must hold

Stated as conditions, not as places in the code — *where* each one is enforced is a design decision
and belongs in Part 2, next to the code that makes it.

1. A balance is never negative.
2. Every amount is positive and has exactly two decimal places.
3. An account's balance always equals the sum of its ledger entries.
4. A balance never changes without an entry recording it, and no entry exists for a change that
   did not happen.

### How money moves

The three operations, as a reader would describe them before knowing how any of it is built.

**Deposit**

1. Check the amount is a legal amount — positive, two decimal places, within the ceiling.
2. Take an exclusive hold on the account, so nothing else can touch it until this finishes.
3. Read the balance, add the amount.
4. Record the new balance and append an entry for it.
5. Release. The new balance and its entry become visible together, or neither does.

**Withdrawal**

The same five steps, with one more between 2 and 3: if the amount is greater than the balance,
stop — nothing is written and the caller is told the funds are insufficient. Because the hold is
already taken, the balance checked is the balance changed; nothing can move in between.

**Transfer**

1. Reject it if the source and the destination are the same account — before anything is held.
2. Check the amount, as above.
3. Take an exclusive hold on **both** accounts, always in the same fixed order, whichever
   direction the money is going.
4. Read both balances. If the source cannot fund it, stop. Nothing has changed yet.
5. Subtract from the source, add to the destination.
6. Append two entries, one to each account, sharing a transfer id.
7. Release. All four changes become visible together, or none of them do.

Step 3 is the only part of this that is not obvious. Two transfers running in opposite directions —
A to B, and B to A — that each grabbed their own source first would each end up holding what the
other is waiting for, and neither would finish. A fixed order that both callers follow is what
prevents it. Any consistent total order works.

Step 4 is what makes the transfer atomic without needing to undo anything: both accounts are
checked before either is changed, so a half-finished transfer never exists in the first place.

## 6. API definition

Resource-shaped rather than verb-shaped: a deposit *creates* a ledger entry, so it is a POST to a
sub-resource, not an RPC call in a REST costume. A transfer belongs to both accounts equally, so it
is its own top-level resource rather than nested under one of them.

| Method | Path | Body | Success | Errors |
|---|---|---|---|---|
| `POST` | `/accounts` | `{ initialBalance }` | **201** account | 400 |
| `GET` | `/accounts/{id}` | — | **200** account | 400, 404 |
| `POST` | `/accounts/{id}/deposits` | `{ amount }` | **201** entry | 400, 404 |
| `POST` | `/accounts/{id}/withdrawals` | `{ amount }` | **201** entry | 400, 404, **409** |
| `GET` | `/accounts/{id}/transactions?page=&size=` | — | **200** page of entries | 400, 404 |
| `POST` | `/transfers` | `{ fromAccountId, toAccountId, amount }` | **201** transfer | 400, 404, 409, **422** |

**Representations**

```
account   { "accountId", "balance", "openedAt" }
entry     { "entryId", "sequence", "accountId", "type", "amount",
            "balanceAfter", "occurredAt", "transferId" }
page      { "content": [entry], "page", "size", "totalElements", "totalPages" }
transfer  { "transferId", "from": entry, "to": entry }
```

**Error contract** — one shape for every failure, `application/problem+json` (RFC 7807) with a
stable code added:

```json
{ "type": "about:blank", "title": "Insufficient funds", "status": 409,
  "detail": "Account … has 40.00, requested 100.00", "errorCode": "INSUFFICIENT_FUNDS" }
```

`errorCode` is the contract a client branches on; `detail` is prose and free to change.

| Status | Meaning here | Codes |
|---|---|---|
| **400** | Malformed request, or an amount that is not a legal amount | `INVALID_AMOUNT`, `INVALID_PAGE_REQUEST`, malformed id or JSON |
| **404** | No such account | `ACCOUNT_NOT_FOUND` |
| **409** | Well-formed, but conflicts with **current state** — a later retry can succeed | `INSUFFICIENT_FUNDS` |
| **422** | Well-formed, but invalid **regardless of state** — retrying is pointless | `SELF_TRANSFER` |
| **500** | A bug. Unhandled, with a stack trace | — |

The 409/422 split is the only status decision in the table that is not obvious, and it is the one
carrying information: it tells a client whether a retry is meaningful.

**Edge cases the API is required to handle**

| Case | Result | Reason |
|---|---|---|
| Negative amount | 400 | A negative deposit is a withdrawal in disguise |
| Zero amount | 400 | An entry recording that nothing happened — a client bug more often than an intent |
| `10.999` | 400 | I cannot know whether `10.99` or `11.00` was meant, and rounding someone's money silently is worse than an error |
| `10.100` | **accepted** | The same value with a trailing zero — this is a precision rule, not a string-length rule |
| Over the per-transaction ceiling | 400 | |
| Withdrawal over the balance | 409 | Conflicts with current state |
| Unknown account | 404 | |
| Malformed account id | 400 | The id itself is the problem; 404 would send the client looking in the wrong place |
| Self-transfer | 422 | Rejected before any row is locked |
| Page size over the maximum | 400 | Rejected, not clamped — ask for 1000, silently get 100, and you page on believing you saw everything |
| Overdraft by any route | impossible | The zero floor is a rule of the account itself, so no route reaches around it |




## 7. Decision Taken Through Development Process

## Money, and why the precision rule is a `try/catch`

`Money` validates on construction, so nothing downstream re-checks an amount (Part 1 §6). The rule
I wanted was *two decimal places*, and the obvious spelling of it is wrong:

| Check | `10.999` | `10.100` | Verdict |
|---|---|---|---|
| `scale() > 2` → reject | rejected | **rejected** | Wrong. `10.100` is `10.10` carrying a trailing zero. |
| `setScale(2, UNNECESSARY)` in a `try/catch` | rejected | accepted | A precision rule, not a string-length rule. |

`UNNECESSARY` also documents the arithmetic: adding and subtracting scale-2 values is exact, so no
rounding ever happens, and offering a `RoundingMode` would imply a policy this domain has not
chosen. If interest or a percentage fee is added later this throws — deliberately. That is the
moment someone has to choose a rounding policy on purpose rather than inherit one.


## Why the lock is in the database

The read and the write of one balance have to be a single indivisible step, or two concurrent
operations lose one of them. Four ways to get that:

| Option | Why not |
|---|---|
| `synchronized` on the service method | Serialises every account against every other, and buys nothing the moment a second instance starts. |
| A `ReentrantLock` per account in a `ConcurrentHashMap` | Faster to write, and the concurrency argument would be entirely mine. But the guarantee is a JVM lock — with two instances it silently stops holding, which is exactly when someone is relying on it. |
| Optimistic locking: a version column and a retry loop | Right for read-heavy workloads. Every operation here writes a hot row, so it would spend its time retrying. |
| **`SELECT … FOR UPDATE` inside the transaction** | **Chosen.** Four words of SQL, scoped to one account, and it still holds with two instances. |

Cost accepted: the suite now needs Docker and runs in about 40s instead of 2s.

**The `CHECK (balance >= 0)` constraint is not a substitute for any of this.** It catches an
overdraft. It does not catch a *lost update*, because a lost update leaves a perfectly valid,
non-negative number in the row. Phase 6 measures precisely that.

## Two reads, two names

`findById` takes no lock. `requireForUpdate` locks the row until the transaction ends.

They are separate methods with different names because that is the difference a call site must not
get wrong. A single `findById(id, boolean lock)` puts the safe and the unsafe read one keystroke
apart, in a codebase where picking the wrong one produces no error and no test failure — only a
rare, silent loss of money.

There is no interface over either repository. With one implementation each, an interface would be a
file that only forwards.


## How to run it

```bash
mvn test        # needs Docker: the tests start their own Postgres via Testcontainers
mvn test -DexcludedTestGroups=concurrency    # fast loop, skips the slow proofs
```

To run the service you need a database of your own. The tests do not — they start one.

```bash
docker run --name transactions-db -e POSTGRES_DB=transactions \
  -e POSTGRES_USER=transactions -e POSTGRES_PASSWORD=transactions \
  -p 5432:5432 -d postgres:16-alpine

mvn spring-boot:run      # Flyway applies the migration on startup, http://localhost:8080

docker rm -fv transactions-db    # -v drops the data volume as well as the container
```

One container, so there is no compose file: it would be a second way to do the same thing and the
test suite would not use it.

Java 21, Maven 3.9+, Docker.

## API

The full definition, including request bodies and every error, is in Part 1 §7. This is the
summary:

| Method | Path | Purpose |
|---|---|---|
| POST | `/accounts` | Open an account with an initial balance |
| GET | `/accounts/{id}` | Current balance |
| POST | `/accounts/{id}/deposits` | Pay in |
| POST | `/accounts/{id}/withdrawals` | Take out |
| POST | `/transfers` | Move money between two accounts |
| GET | `/accounts/{id}/transactions?page=&size=` | Paginated history |

Errors are `application/problem+json` with a stable `errorCode`: 400 for malformed input, 404 for
an unknown account, 409 for insufficient funds, 422 for a self-transfer.

```bash
$A = (curl.exe -s -X POST http://localhost:8080/accounts `
  -H "Content-Type: application/json" `
  -d '{"initialBalance":"250.00"}' | ConvertFrom-Json).accountId

$B = (curl.exe -s -X POST http://localhost:8080/accounts `
  -H "Content-Type: application/json" `
  -d '{"initialBalance":"0.00"}' | ConvertFrom-Json).accountId

curl.exe -X POST http://localhost:8080/accounts/$A/deposits `
  -H "Content-Type: application/json" `
  -d '{"amount":"50.00"}'

curl.exe -X POST http://localhost:8080/accounts/$A/withdrawals `
  -H "Content-Type: application/json" `
  -d '{"amount":"25.00"}'

curl.exe -X POST http://localhost:8080/transfers `
  -H "Content-Type: application/json" `
  -d "{`"fromAccountId`":`"$A`",`"toAccountId`":`"$B`",`"amount`":`"100.00`"}"

curl.exe http://localhost:8080/accounts/$A

curl.exe "http://localhost:8080/accounts/$A/transactions?page=0&size=2"
```

