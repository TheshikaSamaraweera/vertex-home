# Manual Test Guide — Phase 2

**Catalogue, stock ledger, procurement.** The development plan calls this the correctness core and
Gate 2 the most important gate in the project. Everything downstream — reservation, sales,
fulfilment — assumes this is right.

Everything below has been run and passes on this machine. Roughly 30 minutes by hand, 5 with the
Postman collection.

---

## 1. What changed since Phase 1

**Four new migrations** (V4–V7) plus **one regression fix** (V8 — read §6, it matters).

| Table | Purpose |
|---|---|
| `category`, `item` | The catalogue |
| `location` | Multi-location from day one, one seeded default |
| `stock_movement` | **The append-only ledger. Source of truth.** |
| `stock_level` | A projection, rebuildable from the ledger |
| `supplier`, `purchase_order`, `purchase_order_line` | Procurement |
| `goods_receipt`, `goods_receipt_line` | Deliveries, full or partial |
| `reorder_alert` | Raised below threshold, cleared above |

**Seed data now includes** 4 categories, 20 items (`SLV-001` … `SLV-020`), 2 suppliers (one
deliberately deactivated), and opening stock — 100 units of `SLV-001`, 50 of everything else.

`SLV-001` is the designated **shared component**: Phase 3 puts it in two overlapping item sets on
purpose, as the permanent contention fixture the plan asks for.

### Start it

```powershell
.\gradlew bootRun --args="--spring.profiles.active=seed"
```

The seeders are idempotent — items that exist are skipped, and stock is only posted for items that
have no position yet, so running the seed profile daily will not quietly inflate your inventory.

---

## 2. The one design decision worth understanding

**Nothing writes `stock_level` except `StockLedgerService`.**

Every change — an adjustment, a goods receipt, the seeder's opening balances — builds a
`StockPosting` and hands it to the ledger. The ledger appends the movement and updates the
projection in one transaction, under a row lock, in a deterministic order.

That is enforced three ways, deliberately overlapping:

| Level | Mechanism | Catches |
|---|---|---|
| Build | ArchUnit `StockLedgerSealTest` | A developer reaching for `StockLevelRepository` |
| Service | Balance check under `SELECT … FOR UPDATE` | Concurrent overdraw |
| Database | `CHECK (on_hand >= 0)`, `CHECK (reserved <= on_hand)` | Anything that gets past both |

The lock ordering is worth a note. `postAll` sorts rows by `(itemId, locationId)` before locking
anything. Phase 2 does not strictly need it — but a multi-line goods receipt has exactly the same
shape as the Phase 3 reservation that does, and the plan is explicit that lock ordering must not
be retrofitted. It is here from the first commit.

---

## 3. Gate 2 — the checks that must pass

Log in as `inventory@mlmsittu.local` unless stated otherwise. Password `Password123!`, TOTP secret
`JBSWY3DPEHPK3PXPJBSWY3DPEHPK3PXP`, code via `.\gradlew totp -Psecret=…`.

### 3.1 · P2-01 Duplicate SKU

```
POST /api/v1/items
{ "sku": "slv-001", "name": "Duplicate attempt", "unitCost": 10.00, "sellingPrice": 20.00, "reorderLevel": 5 }
```

**Expect `409 DUPLICATE_SKU`.** Note the lower case — SKUs are normalised to upper case on the way
in, so `slv-001` and `SLV-001` are one item. They get quoted over the phone and typed off printed
labels; treating them as two items is a data-quality problem you find months later.

### 3.2 · P2-02 Listing and deactivation

```
GET  /api/v1/items                          → 20 items, "nextCursor": null
POST /api/v1/items/{id}/deactivate
GET  /api/v1/items                          → 19 items, the deactivated one gone
GET  /api/v1/items?includeInactive=true     → 20 again
GET  /api/v1/items/{id}                     → still resolves by id
```

An item is never deleted. Every stock movement and order line that ever touched it points at it;
deleting would either orphan those or cascade away history the business has to keep.

### 3.3 · P2-03 Exactly one default location

```sql
SELECT * FROM location WHERE is_default = true;
```

**Expect exactly one row.** Then try to create a second default:

```sql
INSERT INTO location (code, name, is_default) VALUES ('X', 'X', true);
```

> ⛔ **Must fail** — `duplicate key value violates unique constraint "idx_location_single_default"`.
> A partial unique index makes "at most one default" a database fact rather than a convention.

### 3.4 · P2-04 Ledger arithmetic

Create a scratch item, then adjust it. Grab `itemId` from the create response.

```
POST /api/v1/items
{ "sku": "TEST-900", "name": "Ledger Test Item", "unitCost": 100.00, "sellingPrice": 150.00, "reorderLevel": 20 }
```

| Step | Request | Expect |
|---|---|---|
| 1 | `POST /api/v1/stock/adjustments` `{itemId, qtyDelta: 50, reason: "opening count"}` | `onHand: 50` |
| 2 | same with `qtyDelta: -20, reason: "damage"` | `onHand: 30` |
| 3 | SQL below | ledger sum `30`, matching |
| 4 | same with `qtyDelta: -40` | ⛔ `409 INSUFFICIENT_STOCK`, `onHand` still `30` |

```sql
SELECT i.sku, sl.on_hand AS projection, sum(sm.qty_delta) AS ledger_sum
FROM item i JOIN stock_level sl ON sl.item_id = i.id JOIN stock_movement sm ON sm.item_id = i.id
WHERE i.sku = 'TEST-900' GROUP BY i.sku, sl.on_hand;
```

The 409 body carries `itemId`, `required`, `available` and `shortfall` as structured fields, per
architecture §9 — a client can say "short by 10" without parsing English.

### 3.5 · P2-04 step 5 — prove the ArchUnit rule can fail

Create `src\main\java\com\democode\mlmsittu\inventory\internal\service\SealProbe.java`:

```java
package com.democode.mlmsittu.inventory.internal.service;

import com.democode.mlmsittu.inventory.internal.stock.StockLevel;

class SealProbe {
    Class<?> reachPastTheLedger() {
        return StockLevel.class;
    }
}
```

```powershell
.\gradlew test
```

> ⛔ **Must fail:** `Stock is written only by StockLedgerService. Post a StockPosting instead of
> reaching for StockLevel`. Delete the file, re-run, confirm green.

### 3.6 · P2-05 Reconciliation repairs deliberate corruption

```sql
UPDATE stock_level SET on_hand = 999
WHERE item_id = (SELECT id FROM item WHERE sku = 'SLV-001');
```

Then, **as `super@mlmsittu.local`**:

```
POST /api/v1/stock/reconcile
```

**Expect** a report naming one discrepancy, `999 → 200`, and the row restored. As
`inventory@mlmsittu.local` the same call must return **403** — it is a repair tool that silently
rewrites stock figures, not something a clerk should fire by accident.

Two things reconciliation deliberately does **not** do:

- **It writes no movement.** A reconciliation movement would change the very sum it is comparing
  against. Only the projection is corrected. (`StockMovementType.RECONCILIATION` exists for the
  opposite case — a physical count proving the *ledger* wrong.)
- **It does not touch `reserved`.** Reservations are live state with no ledger representation.
  Only `on_hand` is derivable.

### 3.7 · P2-06 Deactivated supplier

`SUP-002` is seeded deactivated. Try to order from it as `procurement@mlmsittu.local`:

```
POST /api/v1/purchase-orders
{ "supplierId": "<SUP-002 id>", "lines": [ { "itemId": "<any>", "quantity": 10 } ] }
```

> ⛔ **Must fail** — `409 SUPPLIER_INACTIVE`. Orders already placed with that supplier stay valid
> and can still be received against. A supplier you stopped buying from is not one who never
> existed.

### 3.8 · P2-07 Purchase order lifecycle

As `procurement@mlmsittu.local`:

```
POST /api/v1/purchase-orders
{ "supplierId": "<SUP-001>", "lines": [
    { "itemId": "<SLV-001>", "quantity": 100 },
    { "itemId": "<SLV-002>", "quantity": 30 },
    { "itemId": "<SLV-003>", "quantity": 20 } ] }
```

**Expect** `status: "draft"`, `poNumber: "PO-000001"`, a computed `total`.

```
POST /api/v1/purchase-orders/{id}/send        → status "sent"
PUT  /api/v1/purchase-orders/{id}/lines       → ⛔ 409 PURCHASE_ORDER_NOT_EDITABLE
```

The supplier is working from the document we issued. Changing our copy afterwards means the two
disagree and every later receipt is measured against a quantity nobody agreed to.

### 3.9 · P2-08 Partial then full receipt

As `inventory@mlmsittu.local` — note the **role switch**, this is separation of duties, not an
oversight. Procurement orders; the warehouse books what physically arrived.

`SLV-001` starts at 100 (seeded).

| Step | Request | Expect |
|---|---|---|
| 1 | receive 60 against the 100-line | line open, `outstanding: 40`, stock **160** |
| 2 | receive 40 | line `closed: true`, `outstanding: 0`, stock **200** |
| 3 | receive 10 more | ⛔ `409 OVER_RECEIPT` with `ordered`, `alreadyReceived`, `outstanding`, `attempted` |
| 4 | `GET /api/v1/stock/movements?itemId=<SLV-001>` | two `RECEIPT` rows, each with its own `referenceId` |

```
POST /api/v1/goods-receipts
{ "purchaseOrderId": "<id>", "lines": [ { "purchaseOrderLineId": "<line id>", "quantity": 60 } ] }
```

The order stays `partially_received` until **every** line is closed — receiving all of line 1 while
lines 2 and 3 are outstanding is not a fully received order. Receive the rest and it flips to
`received` with `closedAt` set.

Over-receipt is blocked twice: the service returns the numbers, and
`chk_not_over_received` on the table refuses it regardless.

### 3.10 · P2-09 Reorder alerts

```
POST /api/v1/stock/reorder-scan       → {"alertsRaised": 0, ...}
```

`SLV-020` has reorder level 8 and 50 in stock. Drop it below:

```
POST /api/v1/stock/adjustments
{ "itemId": "<SLV-020>", "qtyDelta": -45, "reason": "stock count" }

POST /api/v1/stock/reorder-scan       → {"alertsRaised": 1}
GET  /api/v1/stock/reorder-alerts     → one open alert, level 8, onHand 5
```

Scan again without changing anything — **still one alert, not two.** A partial unique index allows
one open alert per item and location; re-raising on every scan would bury procurement in
duplicates of a fact they already know.

Restock and rescan:

```
POST /api/v1/stock/adjustments  { "itemId": "<SLV-020>", "qtyDelta": 20, "reason": "restock" }
POST /api/v1/stock/reorder-scan → {"alertsCleared": 1}
GET  /api/v1/stock/reorder-alerts → empty
```

### 3.11 · P2-10 Adjustment without a reason

```
POST /api/v1/stock/adjustments
{ "itemId": "<any>", "qtyDelta": -1 }
```

> ⛔ **Must fail** — `400 VALIDATION_FAILED`, `errors: { "reason": "REASON_REQUIRED" }`.

An adjustment without a reason is an unexplained change to a financial record. Checked at the DTO
boundary *and* again in the ledger service, because the ledger has callers other than this
endpoint.

---

## 4. The Gate 2 checklist

Run these last. All five must pass.

```sql
-- 1. Ledger sum equals projection for EVERY row
SELECT count(*) AS rows_checked,
       count(*) FILTER (WHERE sl.on_hand IS DISTINCT FROM COALESCE(m.total, 0)) AS mismatches
FROM stock_level sl
LEFT JOIN (SELECT item_id, location_id, sum(qty_delta) AS total
           FROM stock_movement GROUP BY 1, 2) m
  ON m.item_id = sl.item_id AND m.location_id = sl.location_id;
-- mismatches must be 0

-- 2. Stock is never negative
SELECT count(*) AS negative_rows FROM stock_level WHERE on_hand < 0 OR reserved < 0;
-- must be 0
```

- [ ] Ledger sum equals projection for **every** row — `mismatches = 0`
- [ ] Reconciliation repaired the deliberate `999` corruption (§3.6)
- [ ] Partial receipt across two deliveries totalled correctly — 100 → 160 → 200 (§3.9)
- [ ] Stock could not go negative by any route you tried (§3.4 step 4)
- [ ] ArchUnit blocked the direct `stock_level` access, and you saw it fail (§3.5)

### Re-run Gate 1 as well — this is not a formality

Connect **as `mlmsittu_app`**, not postgres:

```sql
DELETE FROM audit_log;        -- ⛔ must fail: permission denied
DELETE FROM stock_movement;   -- ⛔ must fail: permission denied
```

Read §6 before you skip this.

---

## 5. Try to break it

The scripted checks pass. These are where you might find something I missed.

- Post a goods receipt naming a `purchaseOrderLineId` from a **different** order. Expect `404`, not a stock movement.
- Receive the same PO line twice **in one request** — `[{line, 60}, {line, 60}]` against 100 outstanding. They are summed before checking, so expect `409 OVER_RECEIPT`, not two 60s sneaking through.
- Adjust an item down while a purchase order for it is open. Should succeed — an open order is not stock.
- Send a PO with two lines for the same item. Expect `409 DUPLICATE_ORDER_LINE`.
- Create an item, deactivate it, then try to put it on a new purchase order. Expect `409 ITEM_INACTIVE`.
- As `finance_officer`, try every write endpoint in this document. All should be `403`.
- Set `qtyDelta: 0` on an adjustment. Expect `400 ZERO_QUANTITY` — a zero movement carries no information and is almost always a bug upstream.

---

## 6. A regression I introduced and fixed — please verify it

**I broke the Phase 1 audit control while building Phase 2, and the cumulative-gate rule is what
caught it.** You should know this happened.

**What went wrong.** `V1__extensions.sql` defined a helper, `grant_app_privileges()`, that does a
blanket `GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES` to the application role, and every
migration calls it at the end. V3 revoked `UPDATE`/`DELETE` on `audit_log`; V5 did the same for
`stock_movement`. But V4, V6 and V7 each called the helper again afterwards — and each call handed
the write permissions straight back.

The result: by the end of Phase 2 the application role could delete its own audit trail and its own
stock ledger. Both controls had passed their gate in Phase 1 and both were silently gone. I found
it running the Gate 2 append-only check, where a `DELETE FROM stock_movement` that should have been
refused reported `DELETE 22`.

**The fix** is `V8__fix_append_only_grants.sql`. Ordering was the wrong thing to rely on — a
migration author should not have to remember to re-revoke after calling a shared helper, because
eventually one of them will not. The helper now knows which tables are append-only and finishes by
taking write access back, including on partitions matched through `pg_inherits`. The restriction is
re-asserted every time privileges are touched. Adding an append-only table in future means adding
its name to one array.

**Verify the fix yourself:**

```sql
SELECT table_name, string_agg(privilege_type, ',' ORDER BY privilege_type) AS privs
FROM information_schema.role_table_grants
WHERE grantee = 'mlmsittu_app'
  AND table_name IN ('audit_log', 'audit_log_2026_08', 'stock_movement', 'stock_level')
GROUP BY table_name ORDER BY table_name;
```

**Expect:**

| table_name | privs |
|---|---|
| `audit_log` | `INSERT,SELECT` |
| `audit_log_2026_08` | `INSERT,SELECT` |
| `stock_movement` | `INSERT,SELECT` |
| `stock_level` | `DELETE,INSERT,SELECT,UPDATE` |

`stock_level` **should** be fully writable — it is a projection, and the ledger service rewrites it
during reconciliation.

**The lesson worth keeping:** the plan's rule that later gates re-run earlier checks is not
ceremony. This regression was invisible in every functional test — the application behaved
identically. Only the permission check found it.

---

## 7. Smaller notes

- **A duplicate JSON key in error bodies**, found during testing: a custom problem property named
  `status` collided with RFC 9457's own `status` field, so one body carried `"status": 409` and
  `"status": "sent"`. Fixed at the throw site, and `GlobalExceptionHandler` now prefixes any
  property colliding with a reserved member and logs a warning, so the whole class of bug cannot
  recur silently.
- **`unit_cost` on a purchase order line is copied at order time**, not read live from the
  catalogue. It is the price agreed with the supplier, and it must not shift because someone edited
  the item afterwards.
- **PO and receipt numbers come from a sequence** and can gap if a transaction rolls back. That is
  fine for these documents. **Invoice numbers in Phase 5 (P5-10) must be gap-free** and need a
  different mechanism — there is a comment on the sequence saying so.
- **The reorder scan compares `available`, not `on_hand`.** Reserved units are already promised;
  counting them as replenishment cover is how a warehouse ends up unable to fulfil an order it
  already confirmed. In Phase 2 nothing reserves yet, so the two are equal — it starts to matter in
  Phase 3.
- **`@Scheduled` reorder scanning runs hourly** in the single local instance. Architecture §10.1
  wants scheduled work on a dedicated instance so it does not fire once per replica; the property
  `jobs.scheduler.enabled` is the seam that becomes a profile in Phase 8.

---

## 8. When this passes

Tell me and I will start **Phase 3 — item sets and reservation**, which the plan flags as the
highest bug risk in the project. `SLV-001` is already seeded as the shared component that will sit
in two overlapping sets, and the deterministic lock ordering reservation depends on is already in
`StockLedgerService` and tested.

Phase 3 also brings the concurrency tests: 100 consecutive deadlock runs, and the oversell test
where ten threads race for the last unit and exactly one wins.

If anything above fails, or behaves in a way that surprises you, tell me what you did and what you
saw.
