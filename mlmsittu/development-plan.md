    
# Development Plan — Distribution & Inventory Platform

**Stack:** Java 21 · Spring Boot 3.3 · PostgreSQL 16 · React 19 PWA
**Companion to:** `architecture-handoff-spring.md` (v2.0), `system-flows.html` (F-01–F-14)
**Version:** 2.0 — local-first revision · 6 August 2026

---

## How to use this document

Work is broken into **10 phases** and **82 tickets**. Every ticket has:

- **Build** — what to implement
- **Verify** — a manual test script anyone can run, with the expected result

A phase is not complete until every ticket passes verification **and** the phase gate passes. Gates are cumulative — later gates re-run earlier checks, because that's where regressions surface.

**Ticket ID format:** `P{phase}-{number}`. Reference in commits: `P3-04: add lock ordering to reservation`.

---

## Strategy: local first

Phases 0–7 run entirely on developer machines. No deployment, no CI, no Redis, no Docker image of the app. Build and verify all business logic locally, then add infrastructure once the functionality is proven.

| Phase | Focus | Environment |
|---|---|---|
| 0 | Local foundation | Laptop |
| 1 | Identity & RBAC | Laptop |
| 2 | Catalogue & stock ledger | Laptop |
| 3 | Item sets & reservation | Laptop |
| 4 | Registration & verification (KYC) | Laptop |
| 5 | Sales & payments | Laptop |
| 6 | Reporting & visualisation | Laptop |
| 7 | Scale-up: Redis, pagination, limits | Laptop |
| 8 | Containerisation & CI/CD | Staging |
| 9 | Hardening & launch | Staging → Production |

### What must be right from day one

Three things are structural. Deferring them means rewriting working code later:

1. **Schema decisions** — `ltree` columns, GIST indexes, the partial unique index on `nic_hash`, the unique index on `payment.bank_ref`
2. **Lock ordering in reservation** (§4.5) — retrofitting means re-testing every concurrent path
3. **API envelope shape** — every list endpoint returns `{ "data": [...], "nextCursor": null }` from the first commit, before pagination exists. Real pagination lands in Phase 7 with **zero contract change**

### Terminology

**KYC** = *Know Your Customer* — the finance-industry term for verifying someone's identity before letting them transact. Here it's the NIC image and bank slip an admin reviews manually. Use "KYC" in code and docs (your developers will find far more material searching it); use **"Registration Verification"** in the user interface.

---

## Local environment

**Infrastructure in Docker** — Postgres and MinIO only. Same versions as production; a native install creates version drift.

```yaml
# docker-compose.local.yml
services:
  postgres:
    image: postgres:16
    ports: ["5432:5432"]
    environment:
      POSTGRES_PASSWORD: local
      POSTGRES_DB: platform
  minio:
    image: minio/minio
    ports: ["9000:9000", "9001:9001"]
    command: server /data --console-address ":9001"
```

**Application runs natively** — `./gradlew bootRun`. Fast restarts, working debugger, hot reload via DevTools. It gets containerised in Phase 8.

**No Redis until Phase 7.** Sessions live in memory. Consequence: one app instance only, and sessions drop on restart. Acceptable locally.

### Seed data

`db/seed/dev-seed.sql` must create:

- 6 admin users, one per role (§8.1)
- 3 distributors at different tree depths
- 20 items across 4 categories
- 3 item sets, where **Set A and Set B deliberately share a component item** — your permanent contention fixture
- 1 supplier, 1 location
- Stock: 100 units of the shared component, 50 of everything else

Reset: `./gradlew flywayClean flywayMigrate seedDev`. Run it daily.

---

## Where to start, and why

Not the login screen. Start with the **stock ledger** (Phase 2).

1. **It's the correctness core.** Every other module reads or writes stock. If it's wrong, everything above it is wrong, and you find out late.
2. **It needs no UI.** Verify the whole phase with HTTP calls and SQL.
3. **It contains the hardest problem.** Overlapping item sets with pessimistic locking is where production bugs live. Build it while the codebase is small enough to refactor freely.

Phase 1 does auth only because stock movements need an actor to attribute to.

---

# Phase 0 — Local Foundation

**Duration:** 4 days

### P0-01 · Gradle module skeleton
**Build:** Multi-module project per §1.3 — nine modules, empty but wired.
**Verify:** `./gradlew projects` lists all nine. Add a class in `catalogue` importing `inventory` internals; `./gradlew test` → **ArchUnit fails the build**. Remove it, confirm green.

### P0-02 · Docker Compose for infrastructure
**Verify:** `docker compose up -d`. Connect with `psql`. Open the MinIO console at `localhost:9001`, create a bucket.

### P0-03 · Flyway and extensions
**Build:** `V1__extensions.sql` enabling `ltree` and `pgcrypto`.
**Verify:** `SELECT extname FROM pg_extension;` — both present. Drop the database, re-migrate, confirm identical result.

### P0-04 · Application skeleton
**Verify:** `./gradlew bootRun`; `curl localhost:8080/actuator/health` → `{"status":"UP"}`.

### P0-05 · API response envelope
**Build:** A `PagedResponse<T>` record returning `{data, nextCursor}`. Every list endpoint uses it from now on.
**Verify:** Add a throwaway list endpoint. Response has both fields, `nextCursor: null`. **This shape is frozen — Phase 7 fills in the cursor without changing it.**

### P0-06 · Global error handling
**Build:** RFC 9457 `ProblemDetail` handlers (§9).
**Verify:** Trigger a 404 and a validation failure. Each has `type`, `title`, `status`, and a machine-readable `code`. **No Java class names or stack traces in any body.**

### P0-07 · Seed script
**Verify:** Fresh migrate + seed. `SELECT count(*) FROM item;` → 20. Set A and Set B share exactly one `item_id`.

> ### 🚦 Gate 0
> - [ ] `./gradlew build` green from a clean clone
> - [ ] ArchUnit rejects a cross-module violation
> - [ ] Seed runs twice consecutively without error
> - [ ] List endpoints return the frozen envelope shape
> - [ ] Every developer has the stack running locally

---

# Phase 1 — Identity, RBAC, Audit

**Duration:** 2 weeks

### P1-01 · User schema
**Build:** `app_user`, `role`, `user_role`.
**Verify:** Join query returns six users with distinct roles.

### P1-02 · Password hashing
**Build:** Argon2id per §7.3 (requires `org.bouncycastle:bcprov-jdk18on`). If you'd rather avoid the dependency, `BCryptPasswordEncoder(12)` is acceptable — **decide now, not later**, since changing it means every user resets their password.
**Verify:** `SELECT password_hash FROM app_user LIMIT 1;` — starts with `$argon2id$` (or `$2a$` for bcrypt). Confirm not plaintext.

### P1-03 · Session authentication — in-memory
**Verify:** Login → 200 with `Set-Cookie`; `HttpOnly` and `SameSite` set. Logout, reuse the old cookie → 401. **Restart the app; sessions drop** — expected until Phase 7.

### P1-04 · Six roles and `@PreAuthorize`
**Verify:** As `inventory_clerk`, call a finance endpoint → 403. As `finance_officer`, same call → not 403. Repeat for all six roles.

### P1-05 · Admin TOTP 2FA
**Verify:** Enrol with Google Authenticator. Password alone → challenge. Wrong code → rejected. Correct code → success. Expired code → rejected.

### P1-06 · Audit log
**Build:** `audit_log` with monthly partitions, AOP aspect (§8.2).
**Verify:** Change a user's role. Newest row has actor, action, entity, before/after JSON, IP. Then `DELETE FROM audit_log` as the **application** DB role → must fail on permissions.

### P1-07 · Login throttling — in-memory
**Build:** Simple in-memory counter. Redis version lands in Phase 7.
**Verify:** 11 rapid login attempts → the 11th returns 429.

> ### 🚦 Gate 1
> - [ ] All six roles verified against protected endpoints
> - [ ] Audit log append-only at the DB permission level
> - [ ] No stack traces in any error response
> - [ ] Admin cannot log in without TOTP

---

# Phase 2 — Catalogue, Stock Ledger, Procurement

**Duration:** 4 weeks · **The correctness core**

### P2-01 · Category and item schema
**Verify:** Create an item. Duplicate SKU → 409 `DUPLICATE_SKU`.

### P2-02 · Item CRUD
**Verify:** Create 30 items, list them. All 30 returned with `nextCursor: null`. Deactivate one — excluded from default listing, still fetchable by ID.

### P2-03 · Location schema
**Verify:** `SELECT * FROM location WHERE is_default = true;` → exactly one row.

### P2-04 · Stock ledger service
**Build:** Append-only `stock_movement` + `stock_level` projection. All writes through `StockLedgerService`.
**Verify:**
1. +50 → `on_hand = 50`
2. −20 → `on_hand = 30`
3. `SELECT sum(qty_delta) ...` → 30, matching the projection
4. −40 → rejected, `on_hand` still 30
5. Add an ArchUnit rule banning `stock_level` writes elsewhere; violate it deliberately; **confirm the build fails**

### P2-05 · Ledger replay reconciliation
**Verify:** Corrupt it deliberately — `UPDATE stock_level SET on_hand = 999`. Run reconciliation. Value returns to the ledger sum. **This proves the ledger is genuinely the source of truth.**

### P2-06 · Supplier CRUD
**Verify:** Deactivated supplier cannot be selected on a new purchase order.

### P2-07 · Purchase orders
**Verify:** Create with three lines → `draft`. Send → `sent`. Edit a line after sending → rejected.

### P2-08 · Goods receipt, full and partial
**Verify:**
1. PO line for 100. Receive 60 → line open, remaining 40, stock +60
2. Receive 40 → line closes, stock +100 total
3. Receive 10 more against the closed line → rejected
4. Two `stock_movement` rows, both referencing the receipt

### P2-09 · Reorder detection
**Verify:** Reorder level 20, stock 15, run job → alert raised. Stock 25, re-run → alert clears.

### P2-10 · Stock adjustment with reason
**Verify:** Adjust −5 with reason `damage`. Reason and actor recorded. Adjustment without a reason → 400.

> ### 🚦 Gate 2 — the most important gate in the project
> - [ ] Ledger sum equals projection for **every** seeded item
> - [ ] Reconciliation repairs deliberate corruption
> - [ ] Partial receipt across two deliveries totals correctly
> - [ ] Stock cannot go negative by any API path you can find
> - [ ] ArchUnit blocks direct `stock_level` writes
>
> **Do not proceed with any box unchecked.** Everything downstream assumes this is correct.

---

# Phase 3 — Item Sets and Reservation

**Duration:** 3 weeks · **Highest bug risk**

### P3-01 · Item set schema
**Verify:** Create a set with three components. The same item can join a second set.

### P3-02 · Availability calculation
**Verify:** Set needs 2× A and 1× B. Stock A=10, B=3 → availability **3** (B-limited, not A). Set A=1 → 0. Confirm computed on read, not stored.

### P3-03 · Contention flagging
**Verify:** Query the two seeded sets sharing a component. Both return a figure **and** a contention flag.

### P3-04 · Reservation with pessimistic locking
**Build:** `ReservationService` per §4.5 — components sorted by `itemId` **before** any lock.
**Verify:** Code-review the `.sorted()` call explicitly. Then:
1. Reserve 2 sets → `reserved` increments on every component
2. `on_hand` **unchanged** — reservation must not decrement
3. Over-reserve → 409 `INSUFFICIENT_STOCK` with `itemId` and `shortfall`
4. Failed reservation leaves **no partial state** — check every component

### P3-05 · Concurrency test — deadlock
**Verify:** Run 100 times: `./gradlew test --tests ReservationConcurrencyTest -Prepeat=100`. **Zero deadlocks.** Then temporarily remove `.sorted()` and re-run — **confirm the test fails**. This proves the test catches the real bug. Restore the sort.

### P3-06 · Concurrency test — oversell
**Verify:** 10 threads reserve the last unit. Exactly **one** succeeds, nine get 409. `on_hand` never negative. Repeat 20 times.

### P3-07 · Reservation release
**Verify:** Reserve, cancel. `reserved` returns to its prior value on every component; availability recovers.

### P3-08 · Reservation expiry
**Verify:** Create a reservation, backdate it in SQL, run the job. Confirm release plus an audit entry. A fresh reservation is untouched.

> ### 🚦 Gate 3
> - [ ] Deadlock test: 100 consecutive runs, zero failures
> - [ ] Removing the sort makes the test fail (test validity proven)
> - [ ] Oversell test: one winner in ten, twenty times
> - [ ] Failed reservations leave zero partial state
> - [ ] **Re-run Gate 2** — ledger still correct

---

# Phase 4 — Registration and Verification (KYC)

**Duration:** 4 weeks · *Parallel stream possible after Phase 1*

### P4-01 · Business ID generator
**Verify:** Generate 1,000 IDs. All unique, all pass `isValid()`, none contains `I`, `L`, `O`, `U`. Alter one character → invalid. Swap two adjacent characters → invalid.

### P4-02 · Distributor schema with ltree
**Verify:** `\d distributor` — GIST and BTREE indexes present, `path` is type `ltree`.

### P4-03 · jOOQ hierarchy repository
**Verify:** Seed three levels. `descendantsOf(root, 2)` returns levels 1–2 only. `ancestorsOf(leaf)` returns root-first order. `EXPLAIN ANALYZE` the descendant query — **GIST index used, not a sequential scan**.

### P4-04 · Referral width cap
**Verify:** 4 children succeed, 5th → 409 `REFERRER_AT_CAPACITY`. Change config to 6 **without restart** → 5th now succeeds. Revert to 4; existing records untouched.

### P4-05 · Account registration and email verification
**Verify:** Login blocked pre-verification. Link works once; reuse → rejected. Backdate past 24 h → rejected.

### ~~P4-06 · SMS OTP~~ — **cut 28 Aug 2026**
The client decided against taking on an SMS provider. Mobile numbers are collected as contact
detail and are not verified. `NotificationSender` carries no `sendSms`; there is no half-built
seam left behind, because an unimplemented method is a promise the system does not intend to keep.

### P4-07 · NIC hashing and encryption
**Verify:** `SELECT nic_encrypted, nic_hash ...` — both binary, **neither readable**. `nic_last4` matches. Change the pepper → hash changes for the same input.

### P4-08 · Document upload
**Verify:**
1. Valid JPEG → accepted
2. `payload.exe` renamed to `.jpg` → **rejected on magic bytes**
3. Photo with GPS EXIF → download stored file, **EXIF gone**
4. 11 MB → rejected

### P4-09 · Presigned document access
**Verify:** URL loads as `kyc_reviewer`. After 90 seconds → denied. Direct bucket access without signature → denied. An access-log row was written.

### P4-10 · Registration submission
**Verify:** Malformed referrer → rejected client-side. Valid shape but nonexistent → 404. At-capacity → 409. Valid → 202.

### P4-11 · Review queue with claim locking
**Verify:** Two browsers, two reviewers. A claims a record; B sees it locked and **cannot** approve. Release; B can claim.

### P4-12 · Self-review prevention
**Verify:** Admin submits, then tries to review it → 403 `SELF_REVIEW_FORBIDDEN`. A different reviewer succeeds.

### P4-13 · Approval transaction
**Verify:** Approve → `business_id`, `path`, `status='active'`, `approved_at` all set in one query. Then force a mid-transaction failure → **nothing persisted**, no orphaned Business ID.

### P4-14 · Deletion and NIC re-registration
**Verify:**
1. Register with NIC `199012345678`
2. Second registration, same NIC → rejected
3. Delete the first account
4. Register again, same NIC → **succeeds**
5. Old record still present with `deleted_at` set
6. Old Business ID **not** reissued

> ### 🚦 Gate 4
> - [ ] Two reviewers cannot act on one record
> - [ ] Self-review blocked
> - [ ] Renamed executable rejected
> - [ ] EXIF stripped from stored images
> - [ ] Presigned URL dead after 60 seconds
> - [ ] NIC re-registration cycle works end to end

---

# Phase 5 — Sales and Payments

**Duration:** 3 weeks

### P5-01 · Customer schema and CRUD
**Verify:** Create, edit, full detail view loads.

### P5-02 · Sales order creation
**Verify:** Order with one item and one set. Reservation fires for all components. Total matches line prices.

### P5-03 · Idempotency keys
**Verify:** Submit with key `abc-123` → created. Identical request, same key → same order returned, **no second order**. New key → second order created.

### P5-04 · Payment slip upload
**Verify:** Status `pending`, image stored privately.

### P5-05 · Duplicate slip prevention
**Verify:** Slip with ref `TXN99887`. Second order, same ref → 409 `DUPLICATE_BANK_REFERENCE`. The error doesn't leak which order used it first.

### P5-06 · Payment verification
**Verify:** As `finance_officer`, view pending, open the slip, verify → `verified`. As `inventory_clerk` → 403.

### P5-07 · Duty separation on payments
**Verify:** Officer A records a payment, tries to verify it → 403. Officer B succeeds.

### P5-08 · Fulfilment and stock decrement
**Verify:** After verification, fulfil. `reserved` **and** `on_hand` both decrease by the same amount. A negative-delta `stock_movement` row exists.

### P5-09 · Rejection releases reservation
**Verify:** Reject a payment → reservation released, availability recovered, buyer can resubmit.

### P5-10 · Invoice generation
**Verify:** Three invoices, sequential, **no gaps**. Kill the app mid-generation, restart → no duplicate number.

> ### 🚦 Gate 5
> - [ ] Full path: order → reserve → slip → verify → fulfil → stock decremented
> - [ ] Duplicate bank reference blocked
> - [ ] Idempotency prevents double orders
> - [ ] Rejection releases stock cleanly
> - [ ] Invoice numbers gap-free after forced restart
> - [ ] **Re-run Gates 2 and 3**

---

# Phase 6 — Reporting and Visualisation

**Duration:** 3 weeks

### P6-01 · Stock reports
**Verify:** Cross-check three items against direct SQL. Exact match.

### P6-02 · Sales reports with date filters
**Verify:** Known range → count and total match manual calculation. Empty range → clean empty state, not an error.

### P6-03 · Customer table view
**Verify:** Load 500 customers. Sorting and filtering work. **Note the load time** — you'll compare after Phase 7 pagination.

### P6-04 · Customer treemap
**Verify:** Pick the largest and smallest rectangles; the area ratio matches the data ratio. Table toggle works. Below 768 px → **table becomes default**.

### P6-05 · Referral hierarchy view
**Verify:** Distributor with a 4-level downline. Only 2 levels render initially. Network tab: expanding a node fires **exactly one** request. Full tree never fetched at once.

### P6-06 · Distributor detail view
**Verify:** Parent and direct children shown, readable ltree path displayed, document access logged on view.

### P6-07 · Data export
**Verify:** CSV opens in Excel. No encoding corruption on Sinhala names. A field starting with `=` does **not** execute as a formula.

> ### 🚦 Gate 6
> - [ ] Every report cross-checked against direct SQL
> - [ ] Treemap areas verified proportional
> - [ ] Hierarchy lazy-loads — confirmed in network tab
> - [ ] CSV export safe against formula injection

---

# Phase 7 — Scale-Up

**Duration:** 2 weeks · **Functionality is complete; now make it hold up**

> **Built 28 Aug 2026 with PostgreSQL in place of Redis.** The client runs Postgres natively and
> there is no supported Redis build for their machine, so sessions and rate limits live in the
> database that is already there. Gate 7's observable requirements are unchanged and all met:
> sessions survive a restart, a cookie from one instance is accepted by another, and limits hold
> across both. Swapping to Redis later is a dependency and two properties — nothing above the
> store knows which one is underneath.
>
> Note for Boot 4: the artifact is `spring-boot-starter-session-jdbc`. The bare
> `spring-session-jdbc` puts the classes on the classpath and wires none of them, because Boot 4
> moved autoconfiguration out of `spring-boot-autoconfigure` into per-technology modules.

### P7-01 · Session migration — **done**
**Built:** `spring-boot-starter-session-jdbc`; schema in Flyway V20, never created by Spring.
**Verify:** Log in. `redis-cli KEYS 'spring:session:*'` → session present. **Restart the app; confirm you are still logged in** — this failed in Phase 1, which is the point. Log out → key gone.

### P7-02 · Two-instance session sharing
**Verify:** Run two instances on different ports. Log in on 8080, send the cookie to 8081 → **accepted**. This is what Redis bought you.

### P7-03 · Cursor pagination
**Build:** Fill in `nextCursor`. **The envelope shape does not change** (P0-05).
**Verify:** 30 items, `limit=10` → 10 plus a cursor. Follow twice → 30 distinct items, **no duplicates, no gaps**. Then insert a new item mid-pagination and continue — confirm no duplicate appears (the failure mode offset pagination has).

### P7-04 · Frontend pagination — no contract change
**Verify:** `git diff` the frontend API layer. Response *parsing* unchanged; only cursor-passing added. **If you had to rewrite list screens, P0-05 wasn't enforced.**

### P7-05 · Shared rate limiting — **done**
**Verify:** 11 logins in a minute → 429. Confirm the limit holds **across both instances** — 6 requests to 8080 and 6 to 8081 should trip it.

### P7-06 · Transactional outbox — **done**
**Built:** `outbox_message` (V20), `@Scheduled` poller with `FOR UPDATE SKIP LOCKED` (§8.3).
Business code still calls `NotificationSender` and did not change; the implementation behind it
now writes a row instead of talking to a mail server inside a transaction.
**Verify:** Trigger an approval — the outbox row commits with the business change. Stop the poller, create three events, restart → all three dispatch. Run two instances → **no event processes twice**.

### P7-07 · Read replica routing — **built, dormant**
Routing `DataSource` conditional on `spring.datasource.replica.url`; absent means one database and
no routing. Stock availability is pinned to the primary with `@ReadFromPrimary` regardless.
**Verify:** Run a report; confirm it executed on the replica. Then confirm **stock availability still queries the primary** — replication lag causes overselling.

### P7-08 · Query performance pass — **done**
Run against 53,000 items and 203,000 movements in the test database, because a development
database of 300 rows makes every plan look fine. One real finding, fixed in V21 — see below.
**Verify:** `EXPLAIN ANALYZE` the ten most-used queries. Any sequential scan on a table over 10,000 rows needs an index or a documented reason.

> ### 🚦 Gate 7
> - [ ] Sessions survive restart and work across two instances
> - [ ] Pagination added with zero frontend contract change
> - [ ] Rate limits enforced across instances
> - [ ] Outbox events never double-process
> - [ ] No unexplained sequential scans on large tables
> - [ ] **Re-run Gates 2, 3, and 5**

---

# Phase 8 — Containerisation and Deployment

**Duration:** 2 weeks

> **Scoped down on 29 Aug 2026 at the client's request: Docker on a single EC2 instance, deployed
> by hand.** No CI pipeline, no separate staging environment, no blue-green. P8-01, P8-02 and
> P8-03 are built and documented in `DEPLOYMENT.md`; P8-04 through P8-09 are deliberately not
> done, and what that costs is written down at the end of that file rather than left implicit.
>
> Two substitutions carried over from earlier phases: compose has no Redis (Phase 7 put sessions
> and rate limits in PostgreSQL) and no MinIO (documents are on a mounted volume).
>
> **The images have not been built.** There is no Docker on the development machine, so the
> Dockerfiles and compose file are written and reasoned about but unproven. What *was* verified is
> everything the container depends on: the jar builds, the prod profile runs on environment
> variables alone, it fails fast on a missing or wrong one, API docs are off, and the application
> refuses to start with development NIC keys.

### P8-01 · Application Dockerfile
**Build:** Multi-stage build, `eclipse-temurin:21-jre-alpine`, JVM flags per §10.2.
**Verify:** `docker build` succeeds. Run the container against local Postgres → health endpoint UP. Image size under 400 MB.

### P8-02 · Full-stack compose
**Verify:** `docker compose up` brings up app, Postgres, Redis, MinIO. **Run the entire Phase 5 order-to-fulfilment path inside containers.**

### P8-03 · Externalised configuration
**Verify:** `docker history` and `grep` the image for the DB password → **must not appear**. Start with a wrong password → fails fast with a clear error.

### P8-04 · CI pipeline
**Build:** GitHub Actions — Spotless, ArchUnit, unit tests, Testcontainers integration tests, build image.
**Verify:** PR with a formatting violation → CI fails on Spotless. Fix → green. PR breaking a module boundary → **CI fails on ArchUnit**.

### P8-05 · OpenAPI client generation
**Verify:** Change a DTO field without regenerating → **CI fails on drift**. Regenerate → green.

### P8-06 · Staging environment
**Verify:** `https://staging.yourdomain.lk/actuator/health` reachable from outside your network with a valid certificate.

### P8-07 · Flyway migrations in the pipeline
**Verify:** Deploy a migration to staging — it runs **before** the app starts. Deploy a deliberately failing migration → the deploy aborts and the previous version stays up.

### P8-08 · Blue-green deployment
**Verify:** Deploy with traffic flowing → zero dropped requests. Then deploy a deliberately broken build → health checks fail, **automatic rollback fires**. Confirm `readinessProbe.initialDelaySeconds` is ≥20 s so a healthy deploy isn't falsely rolled back (Spring takes 8–15 s to start).

### P8-09 · Frontend deployment
**Verify:** Static build on Cloudflare, `/api/*` routed to Spring. Same-origin — **no CORS errors in the console**. PWA installs on a phone.

> ### 🚦 Gate 8
> - [ ] Full order path works inside containers
> - [ ] No secrets in the image
> - [ ] CI fails on formatting, boundaries, and API drift
> - [ ] Rollback demonstrated on a broken deploy
> - [ ] Failed migration aborts deploy safely
> - [ ] PWA installs on a real phone

---

# Phase 9 — Hardening and Launch

**Duration:** 2 weeks

### P9-01 · Load test
**Verify:** 200 concurrent users, 30 minutes. p95 under 500 ms, zero 5xx, no connection-pool exhaustion in logs.

### P9-02 · Sustained reservation contention
**Verify:** 50 concurrent users on overlapping sets, 10 minutes. Zero deadlocks. Afterwards, ledger sum equals projection for every item.

### P9-03 · Security testing
**Verify:** OWASP ZAP at minimum, external tester preferred. No high or critical findings. Manually check SQL injection on every search field, XSS in every text input, IDOR by changing IDs in URLs across roles.

### P9-04 · Backup restore drill
**Verify:** Restore a production-shaped backup to a fresh server. App starts, data intact. **Record elapsed time — this is your RTO.**

### P9-05 · PDPA compliance review
**Verify:** Walk §7 line by line against staging. Run a subject-access export — confirm it captures all personal data for one user. Run erasure — confirm document purge.

### P9-06 · Monitoring and alerting
**Verify:** Trigger each alert artificially — error spike, queue depth, KYC queue age, disk. Each reaches the on-call channel.

### P9-07 · UAT with real staff
**Verify:** Actual reviewers, finance officers, and inventory clerks work a full day in staging at realistic volume. Log every point of confusion. **Fix usability issues before launch, not after.**

### P9-08 · Production cutover
**Verify:** Deploy. Smoke-test the full order path. Monitoring live. Keep the rollback path warm for 48 hours.

> ### 🚦 Gate 9 — Go/No-Go
> - [ ] Load test passed at target concurrency
> - [ ] Zero high/critical security findings
> - [ ] Backup restored, RTO recorded
> - [ ] Every alert verified reaching on-call
> - [ ] UAT sign-off from each admin role
> - [ ] All five §13 open items resolved

---

## Regression checklist

Run before every production deploy — roughly 30 minutes manually:

1. Login with each of the six roles; permissions unchanged
2. Ledger sum equals projection for all seeded items
3. Reserve an overlapping set; no deadlock
4. Upload a renamed executable; rejected
5. Duplicate bank reference; 409
6. Full order path: create → reserve → pay → verify → fulfil
7. Presigned URL expires after 60 seconds
8. Audit log wrote rows for every privileged action above

Automate 2, 5, and 6 first — highest value, easiest to script.

---

## Schedule

| | Wks 1–2 | 3–6 | 7–9 | 10–13 | 14–16 | 17–19 | 20–21 | 22–23 | 24–25 |
|---|---|---|---|---|---|---|---|---|---|
| **Stream A** | P0+P1 | P2 | P3 | P5 | P5 | P6 | P7 | P8 | P9 |
| **Stream B** | P0+P1 | P1 | P4 | P4 | P6 | P6 | P7 | P8 | P9 |

**Total: 25 weeks.** Add 15% buffer → **~29 weeks** for planning.

Slightly longer than v1.0 because Phase 7 now makes the deferred infrastructure explicit instead of hiding it inside earlier phases. The total work is the same; it's just honestly scheduled.

---

## Scope note

**Amended 8 Aug 2026.** An earlier revision of this note said no ticket implements entitlement
logic derived from referral counts. That was recorded in error. **The §0.2 enrolment reward
mechanics are in scope** — four stages, one unlocked per approved referral, a maximum of four
children per referrer, and a bonus stage on completion.

Referral *relationships* are still stored and visualised by P4-02, P4-03, P4-04, P6-05 and P6-06 as
written. The reward mechanics are additional work not yet ticketed; they belong in Phase 4
alongside the hierarchy, and Phase 4's duration should be re-estimated to account for them.

Two constraints carry over from the §4.4 amendment and apply to every ticket that touches them:

1. **Stage state is stored, never recomputed from a count at decision time.** Deriving an unlock
   from `referral_summary.direct_count` is a read-then-write race; two concurrent approvals under
   one parent will both award the same stage.
2. **The width cap of 4 needs a database constraint, not only the service-layer check in §2.2.**
   The same race admits a fifth child.

Both need a concurrency test in the shape of P3-05 and P3-06 — run it many times, then remove the
guard and confirm the test fails, so the test is known to be capable of catching the real bug.

---

**End of document.**
