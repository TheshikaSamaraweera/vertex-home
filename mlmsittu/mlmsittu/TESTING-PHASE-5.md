# Manual Test Guide — Phase 5

**Sales and payments.** The full path is *order → reserve → slip → verify → fulfil → invoice*, and
almost every check in this phase is about a control refusing to do something.

That is the thing to keep in mind while testing: **most of the passes below look like failures.** A
403, a 409, an order that will not move — those are the phase working. If everything you click
succeeds, something is broken.

---

## 1. Setup

Nothing new to install. Start the backend and the frontend as before:

```powershell
# Terminal 1
cd E:\MLM-Sittu\mlmsittu\mlmsittu
.\gradlew bootRun --args="--spring.profiles.active=seed"
```

```powershell
# Terminal 2
cd E:\MLM-Sittu\mlmsittu\frontend
npm run dev
```

Then open <http://localhost:5173>.

### New seed data

**A second finance officer.** `finance2@mlmsittu.local`, same password, same TOTP secret. It exists
for one reason: P5-07 says *"Officer A records a payment, tries to verify it → 403. Officer B
succeeds."* With one account in the role there is nobody for the four-eyes rule to hand over to, so
that check could not be run at all.

**Four customers**, `CUS-001` to `CUS-004`.

**No seeded orders.** Every check below is about what happens as an order *moves*, and a pre-made
order sitting in some middle state would only make it harder to tell a seeded row from one you
created.

| Account | Role | What they can do here |
|---|---|---|
| `finance@mlmsittu.local` | `FINANCE_OFFICER` | customers, orders, record payments, verify |
| `finance2@mlmsittu.local` | `FINANCE_OFFICER` | the same — used as the second pair of eyes |
| `inventory@mlmsittu.local` | `INVENTORY_CLERK` | fulfil |
| `support@mlmsittu.local` | `SUPPORT_AGENT` | read customers, payments and invoices |
| `super@mlmsittu.local` | `SUPER_ADMIN` | all of it |

Password for all: `Password123!` · TOTP secret: `JBSWY3DPEHPK3PXPJBSWY3DPEHPK3PXP`

Get a code without a phone:

```powershell
.\gradlew totp -Psecret=JBSWY3DPEHPK3PXPJBSWY3DPEHPK3PXP
```

### Before you start — write down the starting stock

Sign in as anyone and open **Inventory → Stock**. Note the figures for `SLV-001`. On a freshly
seeded database it is **100 on hand, 0 reserved**. Every stock check below is relative to that.

---

## 2. Customers (P5-01)

Sign in as **`finance@mlmsittu.local`**. Open **Sales → Customers**.

1. **Create.** *New customer* → code `CUS-100`, name `Test Buyer`, city `Colombo`. Save.
2. **Edit.** Open it again, change the phone, save. The **code field is disabled** — other records
   point at it.
3. **Search.** Type `Test` in the search box. The filter runs in the database, not the browser.
4. **Deactivate.** Deactivate `CUS-100`. It disappears from the list. Click *Show deactivated* —
   it comes back, greyed. It will not appear in the customer picker when you place an order.
5. **Duplicate code.** Create another customer with code `CUS-100`. → **`DUPLICATE_CUSTOMER_CODE`**.

**Now check the role gate.** Sign out, sign in as **`support@mlmsittu.local`**. Customers is still
in the menu and the list still loads — support exists to answer questions about customers — but
the **New customer / Edit / Deactivate buttons are gone**. That is UX only; the server refuses too,
which the next section proves.

---

## 3. Placing an order (P5-02)

Back as **`finance@mlmsittu.local`**. Open **Sales → Orders and payments** → *New order*.

1. Customer `CUS-001`.
2. Line 1: item **`SLV-001`**, quantity **10**. The unit price fills in from the catalogue.
3. Line 2: set **`SET-A`** (Morning Essentials), quantity **2**.
4. Discount `100`. Watch the total at the bottom update.
5. *Place order and reserve stock*.

**What to check afterwards:**

| Where | Expected |
|---|---|
| Orders table | status **awaiting payment** (amber) |
| *Open* → the detail | Subtotal, − discount, total. Lines read `SLV-001 · …` and `SET-A · …` |
| Inventory → Stock, `SLV-001` | **reserved went up by 14** — 10 loose, plus 2 per set × 2 sets |
| Inventory → Stock, `SLV-001` | **on hand did not change.** Reserved stock is still on the shelf |
| Inventory → Reservations | one active reservation, reference type `sales_order` |

That last pair is the point of P5-02: the set was **expanded into its components** for the
reservation, because components are what actually leave a shelf — but the order line still says
`SET-A`, because that is what the customer bought and what the invoice has to say.

### The refusal

Place another order for `SLV-001`, quantity **500**. → **`INSUFFICIENT_STOCK`**, and the error
carries the shortfall. Check Stock again: **nothing was reserved**. A reservation is all or nothing.

---

## 4. Idempotency (P5-03)

This is the one check that needs a tool other than the browser, because a browser will not resend
a request for you. Use the Swagger UI at <http://localhost:8080/swagger-ui.html>, or PowerShell.

Sign in first through the UI so the session cookie exists, then, in the browser console on
<http://localhost:5173>:

```js
const body = { customerId: "<paste a customer id>", lines: [{ itemId: "<paste SLV-001 id>", quantity: 2 }] };
const send = () => fetch('/api/v1/sales-orders', {
  method: 'POST',
  headers: { 'Content-Type': 'application/json', 'Idempotency-Key': 'abc-123' },
  body: JSON.stringify(body),
}).then(r => r.json());

await send();   // → SO-0000xx
await send();   // → the SAME SO-0000xx
```

Then refresh the Orders page and check Stock.

| Check | Expected |
|---|---|
| Two calls, same key | the **same order number** both times |
| Orders list | **one** new order, not two |
| Stock | reserved went up **once** (2, not 4) |
| Change `quantity` to 9, keep key `abc-123` | **`IDEMPOTENCY_KEY_REUSED`** — a different request under a used key is a caller bug, not a retry |
| Change the key to `abc-124`, quantity back to 2 | a **second** order is created. A new key means a genuine second order |

> The UI generates one key when the New order form opens, not per click — a key regenerated on
> every submit would make a double-click two orders, which is the exact failure this prevents.

---

## 5. Recording a payment (P5-04, P5-05)

Pick an order that is **awaiting payment** → *Record payment*.

1. Amount defaults to the order total. Leave it.
2. Bank reference: **`TXN99887`**. (Type it as if you read it off the slip.)
3. Slip image: any JPEG, PNG or PDF from your machine.
4. *Record payment*.

The order moves to **payment review**, and the payment appears under *Payments awaiting
verification*.

**Now the control that matters.** Take a second order that is awaiting payment, record a payment on
it, and use **the same reference `TXN99887`**.

> **Expected: `DUPLICATE_BANK_REFERENCE`, HTTP 409.**

Read the error message. It says the reference has already been recorded — and **nothing else**. It
does not name the first order or its number. That is deliberate: a valid slip must not become a way
of enumerating other people's orders. Check the second order afterwards: still **awaiting payment**,
still no payment against it.

**Other things worth trying:**

- Upload a `.txt` renamed to `.jpg` → **`UNSUPPORTED_FILE_TYPE`**. The file is sniffed by its bytes,
  not its name.
- Record a payment on an order that is already **paid** → **`ORDER_NOT_AWAITING_PAYMENT`**.

---

## 6. Verifying — the four-eyes rule (P5-06, P5-07)

Still signed in as **`finance@mlmsittu.local`**, the officer who recorded the payment.

1. In *Payments awaiting verification*, the row shows **you** as the recorder.
2. Click **View slip.** The image opens. Behind that click the server authorised the view and wrote
   it to `document_access_log` *before* sending a byte, then issued a token that lasts 60 seconds
   and works once.
3. Click **Verify**.

> **Expected: `SELF_VERIFICATION_FORBIDDEN`, HTTP 403.**

The button is deliberately not hidden. Seeing the refusal is how you know the control exists.

Now sign out and sign in as **`finance2@mlmsittu.local`**. Same queue, same payment, click
**Verify**.

> **Expected: verified.** The order moves to **paid**.

**And the role gate:** sign in as `inventory@mlmsittu.local`. The *Payments awaiting verification*
card is not shown, and hitting the endpoint directly returns **403** — not 409, because the request
is fine and the payment is verifiable; it is *this person* who may not do it.

### Check the database refuses it too

The rule is in the schema as well as the service, because a control that lives in one place lives in
no place:

```powershell
& "C:\Program Files\PostgreSQL\18\bin\psql.exe" -h localhost -U postgres -d mlmsitty -c `
  "UPDATE payment SET verified_by = recorded_by WHERE status = 'verified';"
```

> **Expected:** `ERROR: new row for relation "payment" violates check constraint
> "chk_payment_four_eyes"`.

---

## 7. Fulfilment (P5-08)

**Write down `SLV-001`'s on hand and reserved before you click.**

Sign in as **`inventory@mlmsittu.local`** — releasing goods is the warehouse's call, not finance's.
Finance decided the money was real; inventory says the stock physically left. Try *Fulfil* as
`finance@` first if you like: **403**.

Find the **paid** order and click **Fulfil**.

| Check | Expected |
|---|---|
| Order status | **fulfilled** |
| Stock, `SLV-001` | **on hand went DOWN** by the ordered quantity |
| Stock, `SLV-001` | **reserved went down by the same amount** |
| Inventory → Stock → movements for `SLV-001` | a new row, **negative delta**, type `FULFILMENT` |
| Inventory → Reservations | that reservation is now **consumed** — not released |
| Order detail | an **invoice** panel appears, `INV-0000xx` |

Both figures moving together in one step is the whole design. Releasing the reservation and then
posting a negative movement would leave a window where the goods look available to anyone reserving
at that instant — and would trip the ledger's own rule that on hand may never fall below reserved.

**Then check the books still agree.** Inventory → Stock → *Reconcile*. It should report **0
corrections**: the projection matches the ledger it is derived from.

**And the order of operations:** take an order that is *awaiting payment* and try to fulfil it →
**`ORDER_NOT_PAID`**. Nothing leaves the building on an unverified payment.

---

## 8. Rejection (P5-09)

Place a fresh order for `SLV-001`, quantity **8**. Note that reserved went up by 8.

1. As `finance@`, record a payment on it with any new reference.
2. As `finance2@`, click **Reject**, reason `Amount does not match the slip`.

| Check | Expected |
|---|---|
| Order status | **payment rejected** — *not* cancelled |
| Stock | **reserved back down by 8**, available recovered |
| Order detail | "holding nothing" |
| Payments on the order | the rejected one, with your reason |

**Now the buyer tries again.** As `finance@`, click *Record payment* on the same order with a new
reference and a new slip.

| Check | Expected |
|---|---|
| Order status | back to **payment review** |
| Stock | **reserved back up to 8** — the order took its stock again |

That re-reservation can legitimately fail if somebody else bought the stock in the meantime, and it
will say **`INSUFFICIENT_STOCK`**. That is the honest outcome: better a refusal now than a promise
that cannot be met at the loading bay.

Verify it as `finance2@` and fulfil it as `inventory@` to close the loop.

---

## 9. Invoice numbers (P5-10)

Fulfil **three** orders. Then open the **Invoices** card at the bottom of the Sales page.

> **Expected:** `INV-000001`, `INV-000002`, `INV-000003`. Consecutive. No gaps.

A gap in an invoice register is indistinguishable from a deleted sale, and an auditor will treat it
as one. That is why invoices do **not** use a Postgres sequence: `nextval()` deliberately does not
roll back, so a failed transaction burns a number forever. Purchase orders use a sequence precisely
because nobody audits their numbering.

### The forced-restart check

The plan asks you to kill the app mid-generation and confirm no number is duplicated or lost.

```powershell
& "C:\Program Files\PostgreSQL\18\bin\psql.exe" -h localhost -U postgres -d mlmsitty -c `
  "SELECT next_value FROM document_counter WHERE name = 'invoice';"
```

Note the number. Stop the backend with **Ctrl+C**, start it again, fulfil another order:

> **Expected:** the new invoice carries exactly that number. The counter is a locked row inside the
> fulfilment transaction, so a crash rolls it back together with the invoice — the number is reused
> by the next attempt rather than lost.

The same is checked automatically, including the crash case, by
`SalesFlowTest.aRolledBackInvoiceDoesNotLeaveAGap`.

---

## 10. Gate 5

| Condition | Where |
|---|---|
| Full path: order → reserve → slip → verify → fulfil → stock decremented | §3, §5, §6, §7 |
| Duplicate bank reference blocked | §5 |
| Idempotency prevents double orders | §4 |
| Rejection releases stock cleanly | §8 |
| Invoice numbers gap-free after forced restart | §9 |
| **Re-run Gates 2 and 3** | `TESTING-PHASE-2.md`, `TESTING-PHASE-3.md` |

The whole set also runs automatically:

```powershell
.\gradlew test --tests SalesFlowTest
```

Fifteen tests, all of Gate 5 among them. They exercise the service layer; the role gating in §2, §6
and §7 is declarative (`@PreAuthorize`) and is checked by hand above, because that is where a wrong
annotation actually bites.

---

## What Phase 5 does *not* do

- **No payment gateway.** Bank transfer with a photographed slip and a human decision, exactly as
  architecture §5 specifies.
- **No partial payments.** One verified payment settles an order. Split payments were not asked for
  and adding them silently would change what "paid" means.
- **No returns.** `StockMovementType.RETURN` exists and nothing writes it yet. A fulfilled order
  cannot be cancelled — the guide above tells you to record a return instead, and that is Phase 6
  or later work.
- **No tax lines.** The schema carries subtotal, discount and total. Nothing in the requirements
  mentions VAT, and inventing a tax model would be worse than leaving the hook visible.
