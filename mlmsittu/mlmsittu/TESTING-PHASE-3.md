# Manual Test Guide — Phase 3

**Item sets and reservation.** The development plan flags this as the highest bug risk in the
project, and architecture §4.5 calls lock ordering "the single most important paragraph in the
document."

Most of Phase 3 is verified by automated tests rather than by clicking — deadlocks and overselling
are races, and you cannot find a race by hand. What you should do by hand is the part that proves
the tests are honest.

---

## 1. One-time setup — a new prerequisite

**Tests now need their own database.** They deliberately exhaust stock and repeat a hundred times;
running that against `mlmsitty` would destroy your seed data on every build.

```powershell
cd E:\MLM-Sittu\mlmsittu\mlmsittu
& "C:\Program Files\PostgreSQL\18\bin\psql.exe" -h localhost -U postgres -d postgres -f db\bootstrap\02-test-database.sql
```

Password: `postgres`. Run it once. Flyway migrates `mlmsitty_test` automatically from then on.

> Testcontainers would normally do this, but it needs Docker, which isn't installed. Phase 8 swaps
> to Testcontainers in CI.

Then, as usual:

```powershell
.\gradlew bootRun --args="--spring.profiles.active=seed"
```

**New seed data:** three item sets. `SET-A` and `SET-B` **deliberately share `SLV-001`** — the
permanent contention fixture the plan asks for. `SET-C` overlaps with nothing, so there is always a
clean control.

| Set | Components | Shares? |
|---|---|---|
| `SET-A` Morning Essentials | `SLV-001` ×2, `SLV-006` ×1, `SLV-009` ×1 | **yes — `SLV-001`** |
| `SET-B` Tea Lover Pack | `SLV-001` ×3, `SLV-002` ×1 | **yes — `SLV-001`** |
| `SET-C` Home Care Bundle | `SLV-011`, `SLV-012`, `SLV-013` | no |

---

## 2. The check that matters most — prove the deadlock test is real

**Do this one by hand. Everything else in this phase rests on it.**

A concurrency test that passes because the bug is hard to trigger is worse than no test, because it
tells you the danger is handled when it isn't. So make the bug happen on purpose.

Open `ReservationService.java`, find `lockOrder`, and delete `.sorted()`:

```java
private List<UUID> lockOrder(Map<UUID, Integer> required) {
    return required.keySet().stream().toList();   // .sorted() removed
}
```

```powershell
.\gradlew test --tests "*ReservationConcurrencyTest*"
```

> ⛔ **Must fail**, with:
> ```
> P3-05 · opposite request ordering never deadlocks FAILED
>   [deadlocks across 30 rounds — if this is non-zero, lockOrder() is not sorting before locking]
> ```

**Put `.sorted()` back**, re-run, confirm green. I ran this exact sequence during development and
it behaved as described.

Then run it the way the plan asks:

```powershell
.\gradlew test --tests "*ReservationConcurrencyTest*" -Prepeat=100
```

> ✅ **Expect:** all five tests pass, zero deadlocks across 100 rounds.

### Why the test can fail

`expandToComponents` returns a `LinkedHashMap`, so component order follows the **caller's** request
order. The test then fires eight threads at once, half asking for `[A, B]` and half for `[B, A]`.
Without the sort, one set of threads locks A then B while the other locks B then A — the textbook
deadlock. With it, everyone locks in item-id order and one simply waits.

That `LinkedHashMap` is deliberate. A `HashMap` might have happened to produce a consistent order
and masked the bug, and the test would have passed for the wrong reason.

---

## 3. What the automated tests cover

```powershell
.\gradlew test
```

| Test | Ticket | What it proves |
|---|---|---|
| the scarcest component decides | P3-02 | 2×A and 1×B, stock A=10 B=3 → **3**, not 5 |
| one scarce component means zero sets | P3-02 | `floor(1/2) = 0` — a set you can't complete isn't "nearly available" |
| availability reflects reservations | P3-02 | on_hand 10, reserved 6 → 2 sets, not 5 |
| overlapping sets both report a figure **and** a flag | P3-03 | both say 10 when only 10 total can ship |
| reserving a set touches every component | P3-04 | 2 sets × 2 per set = 4 reserved; **on_hand unchanged** |
| a rejected reservation leaves no component touched | P3-04 | the component that succeeded is rolled back too |
| opposite request ordering never deadlocks | P3-05 | see §2 |
| ten threads race for the last unit | P3-06 | exactly one winner, nine 409s, 20+ rounds |
| release restores availability | P3-07 | reserved returns to zero on every component |
| a reservation cannot be released twice | P3-07 | a double release would credit the same units twice |

---

## 4. By hand — the parts worth seeing

Log in as `inventory@mlmsittu.local`.

### 4.1 · P3-03 Contention, and why the flag exists

```
GET /api/v1/item-sets/availability
```

**Expect** something like:

```
SET-C   Home Care Bundle     available=50   contended=false
SET-A   Morning Essentials   available=50   contended=true
SET-B   Tea Lover Pack       available=66   contended=true
```

**Look at that carefully.** `SET-A` says 50 and `SET-B` says 66. They share `SLV-001`, of which
there are 200. Fifty of `SET-A` would consume 100, leaving only enough for 33 of `SET-B`.

**Both figures are individually true and jointly impossible.** That is not a bug — it is why
architecture §4.2 calls availability *advisory* and why `contended` is on every response. Only a
reservation settles it.

### 4.2 · P3-04 Reserve a set

```
POST /api/v1/reservations
{ "lines": [ { "setId": "<SET-A id>", "quantity": 2 } ], "referenceType": "manual" }
```

Then `GET /api/v1/stock`:

| SKU | on hand | reserved | available |
|---|---|---|---|
| `SLV-001` | **200 — unchanged** | 4 | 196 |
| `SLV-006` | **50 — unchanged** | 2 | 48 |
| `SLV-009` | **50 — unchanged** | 2 | 48 |

Two points:

- **`on_hand` does not move.** The goods are still on the shelf. They only leave on fulfilment,
  which is Phase 5.
- Every component was reserved, multiplied by the number of sets.

Now re-check `SET-B`: it drops from 66 to **65**, because `SLV-001` available fell to 196 and
`floor(196/3) = 65`. Reserving one set moved the other set's number — contention made visible.

### 4.3 · P3-04 Over-reserve

```
POST /api/v1/reservations
{ "lines": [ { "setId": "<SET-A id>", "quantity": 9999 } ] }
```

> ⛔ **Must fail** — `409 INSUFFICIENT_STOCK` carrying `itemId`, `required`, `available` and
> `shortfall`.

### 4.4 · P3-07 Release

```
POST /api/v1/reservations/{id}/release?reason=test
```

**Expect** `status: "released"`, and `SLV-001` back to `reserved: 0`, `available: 200`.

Release it again:

> ⛔ **Must fail** — `409 RESERVATION_NOT_ACTIVE` with `reservationStatus: "released"`. A second
> release would credit the same units twice and create stock out of nothing.

### 4.5 · P3-08 Expiry

Create two reservations with a TTL, then backdate one:

```
POST /api/v1/reservations
{ "lines": [ { "setId": "<SET-C id>", "quantity": 3 } ], "ttlMinutes": 60 }
```

```sql
UPDATE reservation SET expires_at = now() - interval '2 hours' WHERE id = '<the first one>';
```

```
POST /api/v1/reservations/expire-scan
```

**Expect** `{"candidates": 1, "expired": 1, "skipped": 0}`, the backdated one now `expired`, **the
fresh one still `active`**, and its stock still held. Then check the audit trail:

```sql
SELECT action, entity_id, after FROM audit_log
WHERE action LIKE 'RESERVATION%' ORDER BY created_at DESC LIMIT 3;
```

**Expect** a `RESERVATION_EXPIRED` row with `{"reason": "expired", "status": "expired"}`.

---

## 5. Gate 3

- [ ] Deadlock test: 100 consecutive rounds, zero failures (§2)
- [ ] **Removing the sort makes the test fail** — test validity proven (§2)
- [ ] Oversell test: one winner in ten, twenty-plus rounds
- [ ] Failed reservations leave zero partial state
- [ ] **Re-run Gate 2** — below

### Cumulative re-runs

```sql
-- Gate 2: ledger sum still equals projection for every row
SELECT count(*) AS rows_checked,
       count(*) FILTER (WHERE sl.on_hand IS DISTINCT FROM COALESCE(m.total,0)) AS mismatches
FROM stock_level sl
LEFT JOIN (SELECT item_id, location_id, sum(qty_delta) AS total
           FROM stock_movement GROUP BY 1,2) m
  ON m.item_id = sl.item_id AND m.location_id = sl.location_id;

-- Gate 2 + Gate 3: no negative stock, and reserved never exceeds on_hand
SELECT count(*) FILTER (WHERE on_hand < 0 OR reserved < 0) AS negative,
       count(*) FILTER (WHERE reserved > on_hand)          AS over_reserved
FROM stock_level;

-- Gate 3: the projection's reserved total matches what live reservations claim
SELECT COALESCE(sum(sl.reserved), 0) AS reserved_in_projection,
       (SELECT COALESCE(sum(rl.quantity), 0) FROM reservation_line rl
         JOIN reservation r ON r.id = rl.reservation_id WHERE r.status = 'active')
           AS reserved_by_live_reservations
FROM stock_level sl;
-- these two must be equal
```

Then Gate 1, **as `mlmsittu_app`**:

```sql
DELETE FROM audit_log;        -- ⛔ permission denied
DELETE FROM stock_movement;   -- ⛔ permission denied
```

---

## 6. Try to break it

- Reserve with a line naming **both** `itemId` and `setId`. Expect `400 INVALID_RESERVATION_LINE`.
- Reserve with **neither**. Same error.
- Reserve an item that has never moved at that location. Expect `404 STOCK_ROW_MISSING`, not a 409 — usually it means the wrong location, and saying "insufficient" would send you hunting for stock that was never there.
- Reserve a **deactivated** set. Expect `409 ITEM_SET_INACTIVE`.
- Ask for the same set twice in one request: `[{setId: A, qty: 1}, {setId: A, qty: 1}]`. The components are merged, so it must behave exactly like `qty: 2` — not two separate checks that each pass alone.
- Create a set with the same item on two lines. Expect `409 DUPLICATE_SET_COMPONENT`.
- Reserve everything, then try a stock adjustment that would push `on_hand` below `reserved`. Expect `409 STOCK_RESERVED` — the database `CHECK (reserved <= on_hand)` backs this up.
- As `finance_officer`, try to reserve. Expect `403`.

---

## 7. Two bugs found while building this

**A mapping bug, and the misleading error that hid it.** `ItemSetLine` used a composite
`(set_id, item_id)` primary key. But the parent owns `set_id` through its `@JoinColumn`, so the
column cannot be insertable on the child — and a key column that is not insertable is written as
`NULL`, which the `NOT NULL` constraint rejected.

That part is ordinary. The problem was the error message: `ItemSetService` caught
`DataIntegrityViolationException` and reported **"A set with that code already exists"** — for a
`NOT NULL` violation on a completely different column. The message sent me looking for a duplicate
that was never there.

Fixed both ways:

- `item_set_line` now uses a surrogate id with `UNIQUE (set_id, item_id)`, matching every other
  line table in the codebase.
- `ConflictException.ifConstraintIs(...)` only claims a violation whose constraint name it
  actually recognises, and rethrows anything else. `ItemSetService`, `CategoryService` and
  `SupplierService` all use it now. **A handler that guesses which constraint failed will
  eventually guess wrong, and it does so at exactly the moment you are least able to check.**

---

## 8. Notes

- **`reserved` is not recoverable by reconciliation.** `on_hand` replays from the ledger; reservations are live state with no ledger representation. If reservation data were ever lost, the correct repair is to release everything and let orders re-reserve — not to guess.
- **Release is also sorted by item id**, for the same reason reserving is: a release and a reserve running at once must not take the same rows in opposite orders.
- **The expiry sweep expires each reservation in its own transaction**, so one bad row cannot roll back the whole sweep. It is a separate bean because calling `expire()` from inside `ReservationService` would bypass the Spring proxy and silently lose both the transaction and the audit entry.
- **Reservation lines store expanded components, not the sets requested.** Release has to give back exactly what reserve took, and after expansion the only thing taken is a quantity of a component. Which set it came from is a question for the sales order in Phase 5.

---

## 9. When this passes

Gate 3 is the last of the correctness core. Per your decision, the **frontend comes next** — a
React 19 + Vite + TypeScript admin app covering Phases 1–3 in one go, so no screen gets built
twice:

- login with TOTP, session handling
- items, categories, item sets
- stock with on hand / reserved / available, adjustments, movement history
- set availability with contention flagging
- suppliers, purchase orders, goods receipt
- reservations

That also brings the springdoc-openapi → TypeScript client generation from architecture §6.2, which
is what keeps the Java and TypeScript sides honest with each other.

If anything above fails, or surprises you, tell me what you did and what you saw.
