# Testing the receiving workflow

The change: **arriving** and **being put on a shelf** are now two separate things.

Before, one "Receive" button did both, so the system reported stock on a shelf while it was still
in a box by the door. Now a delivery is signed for at the door, sits in a waiting list, and only
raises stock when somebody says which store it went into.

```
draft ──send──▶ sent ──confirm received──▶ arrived ──add to stores──▶ received
  │                │                          │                          │
  │  editable      │  frozen                  │  stock UNCHANGED         │  stock raised
  └── Purchase orders screen ─────────────────┴──── Receiving screen ────┘
```

---

## 0. Before you start

```powershell
cd E:\MLM-Sittu\mlmsittu\mlmsittu
.\gradlew bootRun

# in a second terminal
cd E:\MLM-Sittu\mlmsittu\frontend
npm run dev
```

Sign in at http://localhost:5173 as `super@mlmsittu.local` / `Password123!`.
TOTP code: `.\gradlew totp -Psecret=JBSWY3DPEHPK3PXPJBSWY3DPEHPK3PXP`

Two new screens are in the sidebar:

| Where | Screen | What it is |
|---|---|---|
| Inventory | **Stores** | the physical places stock sits |
| Procurement | **Receiving** | signed-for deliveries, and everything already put away |

---

## 1. Make a second store

Assigning a delivery to a store is meaningless when there is only one, and until now there was no
way to add another. **Inventory → Stores → New store.**

| Field | Value |
|---|---|
| Code | `KANDY` |
| Name | `Kandy branch store` |
| Location | `Peradeniya Road, Kandy` |

**Check:**
- The new store appears in the list, marked *active*.
- `MAIN` carries a **default** badge and has **no Deactivate button at all**. The server refuses
  it (`DEFAULT_STORE_REQUIRED`), so offering a button that always fails would be worse than
  offering none.
- Edit `KANDY` — the **Code is disabled**. Stock rows and receipts point at it.

---

## 2. Give a supplier an email address

**Procurement → Suppliers.** Edit one and put a real-looking address in **Contact person → Email**
(e.g. `chandima@example.test`). Leave a second supplier with no address at all — you will use it
in step 4.

The named contact's address wins over the company one. That is the person who chases the order;
the company address is usually accounts.

---

## 3. Raise a draft, then edit it

**Procurement → Purchase orders → New purchase order.**

Add one line, save as draft. Then press **Edit** on the same row.

**Check:**
- The item picker is a searchable dropdown, not a plain list.
- Change the quantity on the line that is already there and save. It should work — re-quoting an
  item the order already has is the normal edit, and it used to fail with a database error.
- The **supplier field is disabled** on an edit. Changing who an order is going to is a new order.

---

## 4. Send it

Press **Send**. A dialog appears *before* anything happens, showing the exact address the order
will go to and whether that is the named contact or the company.

**Check both cases:**

| Supplier | What should happen |
|---|---|
| has an email | dialog shows the address; **Send order** works; status becomes `sent` |
| has none | dialog shows a red warning; **Send order** is disabled |

Then look at the **backend terminal**. The order is printed in full:

```
┌── EMAIL (not actually sent — no SMTP provider configured) ───────
│ To      : chandima@example.test
│ Subject : Purchase order PO-000012
├──────────────────────────────────────────────────────────────────
Dear Chandima,

Please supply the following.

Purchase order : PO-000012

Code            Item                       Qty         Unit         Total
--------------------------------------------------------------------------
SKU-0007        Ceylon black tea 500g       10        95.00        950.00
--------------------------------------------------------------------------
                                    Order total        950.00
```

Item **names** as well as SKUs, deliberately — our SKU means nothing on the supplier's side.

**Then check the order row:** the Progress column now reads *Sent to chandima@example.test*, and
the **Edit** button is gone. Try `PUT /purchase-orders/{id}/lines` by hand if you like: it is
refused with `PURCHASE_ORDER_NOT_EDITABLE`.

> **To send for real**, see §7 below. No code change is needed.

---

## 5. Sign for the delivery

The row now shows **Confirm received**. Any signed-in role sees it — whoever is at the door when
the lorry comes signs for it, not just the inventory clerk.

**Check:**
- The dialog asks you to type **your own name**, and shows what it should be.
- Type something else → the button stays disabled, and if you force it through the API you get
  `ATTESTED_NAME_MISMATCH`.
- Type your name in the **wrong case**, with spaces around it → accepted. Capitals should not
  defeat somebody typing their own name.
- Confirm. Status becomes **arrived** (amber — it is work outstanding).

**Now the important check.** Go to **Inventory → Stock** and find the item.

> **The quantity has not moved.** Nothing about signing for a delivery changes a stock figure.
> That is the entire point of the step. If this number went up, the change has failed.

---

## 6. Put it into stores

**Procurement → Receiving.** Two tabs:

### Tab 1 — Received orders

Your order is here, with a count badge on the tab. The columns say who signed for it and when, and
how much is still to store.

Press **Add to stores**:

| Control | What it does |
|---|---|
| *Store for this delivery* | the fallback for every line |
| *Storing now* (per line) | how many actually made it onto a shelf |
| *Into store* (per line) | overrides the fallback for that line only |

**Test the split.** On a two-line order, leave one line as *Same as delivery* and send the other
to `KANDY`. After saving, **Inventory → Stock** should show each item in its own store — not both
in one. A single delivery routinely splits across two stores, and forcing one was why people used
to book two receipts for one lorry.

**Test a part delivery.** Enter fewer than the outstanding quantity.

- Stock rises by exactly what you entered.
- The order becomes **partially_received** and **stays on the tab** with the remainder.
- Store the rest → status **received**, and it leaves the tab.

**Test over-receipt.** Type more than outstanding. The box turns red and the button disables; via
the API it is refused with `OVER_RECEIPT` and the actual numbers.

### Tab 2 — Added to stores

Everything that has been put away, newest first. One line per item showing
**quantity → item → store**, because the store is a per-line fact and rolling it up to the receipt
would undo the whole feature.

---

## 7. Manual entry — goods with no order

**Receiving → Add stock manually.** For a delivery that turned up with no purchase order behind it.

| Field | Required? |
|---|---|
| Supplier | **yes** |
| Store | **yes** |
| Item (searchable) | **yes** — a real catalogue item, not free text |
| Quantity | **yes** |
| Per-line store | no — falls back to the one above |
| Note | no |

**Check:**
- Stock rises immediately.
- The entry appears in **Added to stores** tagged **manual**, alongside the ordered ones.
- Add the **same item twice into the same store** in one form → it becomes **one line**. Add it
  into two different stores → **two lines**.
- Pick a deactivated supplier or item → refused.

Stock that appears from nowhere, in no particular place, is exactly what the ledger exists to make
impossible — hence the two mandatory fields.

---

## 8. Turning on real email

Nothing in the code needs to change. Open
`mlmsittu\src\main\resources\application.properties` and uncomment the mail block:

```properties
spring.mail.host=smtp.gmail.com
spring.mail.port=587
spring.mail.username=orders@yourcompany.lk
spring.mail.password=${MAIL_PASSWORD}
spring.mail.properties.mail.smtp.auth=true
spring.mail.properties.mail.smtp.starttls.enable=true
notifications.mail.from=orders@yourcompany.lk
```

Restart. With `spring.mail.host` set, the SMTP sender takes over and orders reach suppliers; with
it absent, everything goes to the log exactly as above.

> **Never put a real password in that file.** Use an app-specific password and supply it through
> the `MAIL_PASSWORD` environment variable, so it is not in the source tree.

A failed send **fails the whole action** — the order will not be marked as sent if the email did
not go. That is deliberate: an order that looks sent but went nowhere means procurement waits, the
warehouse waits, and nothing on any screen looks wrong.

---

## 8b. Stock is now counted per item, not per shelf

This part changed because of what you saw after opening `K1`: the same item appearing twice on the
stock screen, one of the halves flagged **reorder** even though there was plenty in total.

### On the Stock screen

**One row per item**, totalled across every store. The `reorder` badge is judged on that total.

- Find an item you have split across two stores — `SLV-001`, `SLV-003` and `SLV-010` all are.
  Each should now be **a single row**, showing the combined quantity and status **ok**.
- Press **View stores** on that row. You get the breakdown: which store, how many, how many
  reserved, how many available.
- **Adjust** now lives inside that breakdown, next to a named store. It was moved deliberately —
  an adjustment has to say which shelf it corrects, and offering it beside a total would mean
  guessing.
- Store names in the breakdown are links straight through to the store page.

### Reorder alerts

The red banner at the top counts **items**, not item-and-store pairs.

- Open a brand new store. Run **Run reorder scan**. **No new alerts should appear** — an empty
  store is not a shortage. This is the thing that was wrong.
- Take one item genuinely below its level. Scan → exactly one alert, with the combined figure.
- Receive some into *any* store and scan again → it clears.

### History

Press **History** on a stock row. The **Detail** column now reads something like:

```
GRN-000013 · Received on PO-000009 from Ceylon Supplies
```

instead of `goods_receipt 2adbf12b`. There is also a **Store** column, since the history now spans
every store the item sits in.

> Rows recorded **before** this change have no note and still show the old reference. That is
> expected — the note is written when the stock moves, not worked out afterwards, because the
> ledger outlives the documents it points at.

### On the Stores screen

Every store has a **View store** button. The page shows the store's details and everything
currently held there — SKU, item, on hand, reserved, available here, and the item's reorder level
for reference.

Each row has its own **History** button, and this is the part worth testing carefully: it shows
that item's movements **in this store only**.

- Take an item you hold in two stores. Open its history from one store, then from the other. The
  two lists should be **different**, and neither should contain the other's rows.
- The banner at the top of the modal compares the movements shown against this store's on-hand
  figure. They must match — these movements are exactly what produced that number.
- Compare against **History** on the Stock screen, which shows every movement everywhere. Both are
  honest readings of the same ledger; the store one answers "why does this shelf hold this much".

---

## 9. What to watch for

| Symptom | What it means |
|---|---|
| Stock rises when you press **Confirm received** | the split has failed — this is the one thing that must not happen |
| A line lands in the wrong store | the per-line override is not reaching the server |
| `PURCHASE_ORDER_NOT_RECEIVABLE` on the Receiving screen | correct behaviour if nobody confirmed arrival first |
| An order vanishes from tab 1 while still short | it should stay until every line is complete |
| Manual entry accepts a typed item name | it must not — items come from the catalogue |
| The same SKU appears twice on the stock screen | the per-item rollup has failed |
| A new empty store raises reorder alerts | the threshold is being judged per store again |
| History still shows `goods_receipt <uuid>` on a **new** receipt | the note is not being written at post time |
