# Manual Test Guide — Phase 6

**Reporting and visualisation.** Gate 6 is mostly one instruction: *don't believe the screen, check
it against the database.* So this guide gives you the SQL to run beside each report.

The one genuinely security-shaped item is the CSV export (P6-07). Do that one even if you skip
others.

---

## 1. Setup

### Seed data you will need

The default seed has four customers and no order history — a treemap of four rectangles proves
nothing, and P6-03 asks you to load five hundred customers and note the time. So there is a
fixture seeder, **off by default**:

```powershell
cd E:\MLM-Sittu\mlmsittu\mlmsittu
.\gradlew bootRun --args="--spring.profiles.active=seed --mlmsittu.seed.report-customers=500"
```

That creates 500 customers and roughly a thousand fulfilled orders spread over the past year, with
a deliberately long-tailed distribution so the treemap has something to show. It is idempotent —
running it again leaves the existing fixtures alone.

> **These orders bypass the stock ledger, deliberately.** They are written as fulfilled sales with
> no reservation, no stock movement and no invoice. Going through the real path would need a year of
> purchases first, and inventing stock movements would corrupt the one table this system treats as
> evidence. The consequence: **seeded sales history does not affect stock.** Reconcile still reports
> zero corrections. The stock report is untouched by them; the sales and customer reports are fully
> populated. See `ReportingFixtureSeeder`'s class comment.

**To remove the fixtures afterwards:**

```powershell
& "C:\Program Files\PostgreSQL\18\bin\psql.exe" -h localhost -U postgres -d mlmsitty -c `
  "DELETE FROM sales_order_line WHERE sales_order_id IN (SELECT so.id FROM sales_order so JOIN customer c ON c.id=so.customer_id WHERE c.code LIKE 'RPT-%'); DELETE FROM sales_order WHERE customer_id IN (SELECT id FROM customer WHERE code LIKE 'RPT-%'); DELETE FROM customer WHERE code LIKE 'RPT-%';"
```

Frontend as usual: `npm run dev` in `E:\MLM-Sittu\mlmsittu\frontend`.

### Who can see what

| Report | Who |
|---|---|
| Stock position | anyone signed in — no personal data, and a clerk needs it |
| Sales | `finance_officer`, `support_agent`, `super_admin` |
| Customer analytics | the same three |
| Distributor detail | `kyc_reviewer`, `support_agent`, `super_admin` |

Sign in as `finance@mlmsittu.local` for most of this.

---

## 2. Stock report (P6-01)

**Reports → Stock and sales.**

The plan says to cross-check three items. Do that:

```powershell
& "C:\Program Files\PostgreSQL\18\bin\psql.exe" -h localhost -U postgres -d mlmsitty -c `
"SELECT i.sku, sl.on_hand, sl.reserved, sl.on_hand - sl.reserved AS available,
        i.reorder_level, sl.on_hand * i.unit_cost AS stock_value
 FROM stock_level sl JOIN item i ON i.id = sl.item_id
 WHERE i.sku IN ('SLV-001','SLV-002','SLV-020') ORDER BY i.sku;"
```

> **Expected:** every column matches the screen exactly, including the value.

**Then check the summary row is not a separate query.** The five figures across the top are summed
from the rows shown, so filtering must move both together:

1. Set **Show → Below reorder level only**. The row count drops and so does the stock value.
2. Compare against:

```sql
SELECT count(*), sum(sl.on_hand * i.unit_cost)
FROM stock_level sl JOIN item i ON i.id = sl.item_id
WHERE i.is_active AND i.reorder_level > 0 AND sl.on_hand - sl.reserved <= i.reorder_level;
```

**One thing worth understanding:** "below reorder" compares **available**, not on hand, and an item
with reorder level 0 is never flagged. That is the same rule the reorder alert scan uses — two
definitions of "low stock" is how a report and an alert end up contradicting each other on one
screen. Test it: place a sales order that reserves most of an item's stock, then reload. The item
becomes low without a single unit leaving the building.

---

## 3. Sales report (P6-02)

Same page, second card. Set the range to **Last year**.

```sql
SELECT count(*) AS orders, sum(subtotal) AS gross, sum(discount), sum(total) AS net
FROM sales_order
WHERE status = 'fulfilled'
  AND (fulfilled_at AT TIME ZONE 'Asia/Colombo')::date
      BETWEEN current_date - 365 AND current_date;
```

> **Expected:** orders, gross, discount and net all match.

**Three things to notice:**

1. **"Sales" means fulfilled.** Only orders where the goods actually left, dated by when they left.
   A paid-but-unshipped order is not counted — otherwise "sales" would be a number that can still go
   down. This is the same set the invoice register covers.
2. **Days are Sri Lankan days.** The bucketing uses `AT TIME ZONE 'Asia/Colombo'`, so "sales on the
   13th" means the same thing regardless of where the server runs.
3. **The breakdowns reconcile.** Add up the *By day* column: it equals net. The product list shows
   the top 10 but the underlying data carries every product, and the full set also equals net —
   line values are prorated by each order's discount, because the discount is recorded once on the
   order and summing raw line totals would exceed the money actually taken.

### The empty range

Set **From** and **To** both to a date with no sales — `2001-01-01` works.

> **Expected:** "No sales in that range", with zeroes above it. **Not an error.** A report that
> throws on a quiet week forces every caller to tell a real failure apart from nothing happening.

And a backwards range (**From** after **To**) → `INVALID_DATE_RANGE`. Ten years → `DATE_RANGE_TOO_WIDE`.

---

## 4. Customer table (P6-03)

**Reporting → Customer analytics → Table.**

1. **Open devtools → Network before loading.** Note the time and size of
   `/api/v1/reports/customers`. With 500 fixtures it took **about 590 ms** here. **Write your number
   down** — P7-03 compares it after cursor pagination lands.
2. **Sorting.** Click *Total value*, then again to reverse. Then *Orders*, then *Last order*.
   Customers who have never ordered show `never` and sort to one end rather than mixing into real
   dates.
3. **Filtering.** Type a city into the filter. The count line above the table updates.
4. Customers with **no orders at all are included, with zeroes**. That is deliberate — "who has
   never bought anything" is one of the more useful questions this table answers.

> Sorting and filtering run in the browser here, because the whole set is already in it. When
> Phase 7 paginates, both move to the server with the cursor.

---

## 5. Customer treemap (P6-04)

Switch to **Treemap**.

**The area check the plan asks for:**

1. Hover the **largest** rectangle. The tooltip gives its value and its share of the total.
2. Hover the **smallest**. Same.
3. Divide the two values.

> **Expected:** the ratio of the values matches the ratio of the areas. With the fixture data the
> spread is roughly **1000×**, which is visible at a glance.

You can check the whole thing sums correctly too — the treemap total must equal the sales report's
net for the same range, because both prorate line values the same way. Set both to *last year* and
compare.

**The table toggle**, which §6.4 requires:

1. Click **Table** — the same numbers, precisely readable.
2. **Narrow the window below 768 px and reload.** The **table** loads, not the chart. That is the
   architecture's rule: treemaps are unusable on a phone, so the table is the default there rather
   than a fallback you have to go looking for.
3. Widen and reload — the treemap is back.

The caption under the chart says people compare areas badly. That is not modesty; it is why the
table exists.

---

## 6. Referral hierarchy (P6-05)

**Onboarding → Referral hierarchy.** You need a distributor with a downline four levels deep to
test this properly — approve a few registrations, or use the seeded root and its two children.

**Open devtools → Network, then reload.**

| Check | Expected |
|---|---|
| On load | **two levels** render — the roots and their direct referrals |
| Requests on load | one for roots, then one per root for its children. **Not the whole tree** |
| Expand a third-level node | **exactly one** new request, for that node only |
| Collapse and re-expand | no new request — it is cached |
| Anywhere | no request that returns the full downline |

That last row is the point. A downline of a few thousand is unremarkable and would be megabytes of
JSON to draw the top three rows.

---

## 7. Distributor detail (P6-06)

Click a **Business ID** in the hierarchy.

| Check | Expected |
|---|---|
| Parent | the referrer's Business ID and name, or "Nobody — this is a root" |
| Direct children | a table of them, with each one's own referral count |
| Path | readable as `SLV-00001-6 › SLV-00002-C`, with the raw `n1.n4` shown underneath |
| Referral places | `2 / 4` |
| Documents | listed if they registered — **ids only, never the images** |

**Then the access-log check.** Sign in as `kyc@mlmsittu.local`, open a distributor who registered
through the form, and click **View NIC scan**. Then:

```sql
SELECT action, actor_id, created_at FROM document_access_log ORDER BY id DESC LIMIT 5;
```

> **Expected:** `view_authorised` then `view_served`. Logged **before** the bytes moved, so an
> aborted download still leaves a trace.

As `finance@mlmsittu.local` the detail loads but the document buttons are absent — and the endpoint
returns 403 if you call it directly. This screen cannot become a second, unlogged way to read
somebody's NIC.

---

## 8. CSV export (P6-07) — do this one

Three separate things to prove.

### 8.1 It opens in Excel

Click **Export CSV** on any report. Open the file in Excel.

> **Expected:** columns are columns. Not one column of comma-soup.

### 8.2 Sinhala is not corrupted

Create a customer with a Sinhala name — copy `නිමල් ප්‍රනාන්දු` — then export the customer list and
open it **in Excel**, not a text editor.

> **Expected:** the name renders correctly.

The file carries a UTF-8 byte-order mark. Every other tool ignores it; it is the only thing that
stops Excel on Windows guessing the system code page and producing mojibake.

### 8.3 A formula does not execute — the one that matters

1. **Customers → New customer.** Code `EVIL-1`, name exactly:

   ```
   =cmd|'/c calc'!A1
   ```

2. **Reporting → Customer analytics → Table → Export CSV.**
3. Open the file **in Excel**.

> **Expected:** the cell shows the text `=cmd|'/c calc'!A1`. Excel does **not** prompt to enable
> anything, and nothing runs.

Open the file in Notepad and you will see why: the value is written as `'=cmd|'/c calc'!A1`. The
leading apostrophe makes Excel treat the cell as text.

**Why this matters more than it looks.** That string is not a name — it is code that runs on the
machine of whoever in accounts opens the export, with their privileges. It never had to reach the
server's shell; it only had to be typed into a name field. Quoting does not help, because Excel
strips the quotes and evaluates what is inside.

Try the variants too: a name starting with `+`, `-` or `@`. All four are neutralised. Negative
*numbers* in the quantity and value columns are **not** — those are ours, not user text, and
prefixing them would break the arithmetic somebody exported the file to do.

Delete `EVIL-1` afterwards.

---

## 9. Gate 6

| Condition | Where |
|---|---|
| Every report cross-checked against direct SQL | §2, §3, §4 |
| Treemap areas verified proportional | §5 |
| Hierarchy lazy-loads — confirmed in network tab | §6 |
| CSV export safe against formula injection | §8.3 |

Automated coverage:

```powershell
.\gradlew test --tests 'com.democode.mlmsittu.reporting.*'
```

Eighteen tests. `ReportAccuracyTest` re-runs the SQL cross-check for every row on data it creates
— the query is deliberately written a second time there, because a test that reuses the code under
test only proves the code equals itself. `CsvSafetyTest` covers every formula starter, the RFC 4180
escaping, and a value that tries to break out of its own cell.

---

## What Phase 6 does *not* do

- **No pagination.** Deliberate — P6-03 wants the load time of an unpaginated five hundred rows so
  Phase 7 has something to be measured against.
- **No scheduled or emailed reports.** Nothing in the requirements asks for it, and the transactional
  outbox that would carry it is Phase 8.
- **No PDF export.** CSV covers "get this into a spreadsheet", which is what was asked for. A PDF
  invoice is a separate, later question.
- **No profit or margin figures.** Stock value uses `unit_cost`, which is admin-maintained rather
  than derived from purchase history — architecture §4.1 rules out moving-average and FIFO costing.
  Reporting a margin from a hand-typed cost would look authoritative and be wrong.
