# MLM Sittu — system reference for UI work

Everything a designer or front-end developer needs to rebuild the interface: who uses it, what
every screen does, what data it shows, and which rules are not negotiable because the server
enforces them.

**Nothing here is about visual design.** No colours, no spacing, no component choices. Those are
yours. This describes what the system *is*.

Two applications share one backend and one session mechanism:

| | Staff application | Customer portal |
|---|---|---|
| URL | `/` and everything under it | `/portal/**` |
| Who | 7 staff roles | Customers (`DISTRIBUTOR` role) |
| Screens | 19 | 5 |
| Purpose | Run the business | See your own position and progress |

A customer who opens a staff URL is redirected into the portal, and vice versa. They are not two
skins over the same pages — they show different things to different people.

---

## 1 · What the business does

A Sri Lankan distribution company with two halves that meet at one point.

**Inventory.** Buy furniture from suppliers, hold it in stores, sell it. Ordinary wholesale:
items, purchase orders, goods receipts, stock levels, sales orders, payments, invoices.

**A referral network.** Customers register, are approved, and are given a **Business ID**. Each
customer is handed **five printed cards** carrying that ID. They give the cards to five people they
recruit; each of those registers quoting the ID. Five referrals completes five **stages**, and
completing all five earns the customer the **item pack** they chose at registration — real goods,
not money, taken from real stock by an administrator.

The two halves meet at the pack: a reward is stock leaving a store, so it moves the same ledger a
sale does.

---

## 2 · Business IDs — the one concept to understand first

A Business ID is not a customer number. **It is the customer's position in the referral tree**, and
you can read the whole structure out of it.

```
1                    a root customer — nobody referred them
├── 11               their first recruit
├── 12               their second
│   ├── 121          12's first recruit
│   ├── 122
│   └── … up to 125
├── 13
├── 14
│   └── 143
│       ├── 1431     143's recruits
│       └── … up to 1435
└── 15               the fifth and last
```

Rules that follow from this, and that the UI must not contradict:

- **A child's ID is the parent's ID with a seat number, 1–5, appended.** `1432` is unambiguously the
  second recruit of the third recruit of the fourth recruit of root `1`.
- **Every prefix is an ancestor.** Who is above somebody is answerable by reading the number.
  No lookup needed.
- **Exactly five seats per person.** Not configurable upward — there is no sixth digit available.
- **Length is depth.** A 4-character ID is 3 levels below a root.
- **Roots use only 0 and 6–9 after the first digit**: `1, 2 … 9, 10, 16, 17, 18, 19, 20, 26 …`
  Never `11`, because that is root 1's first seat. This is why roots and seats can never collide.
- **A seat is consumed permanently.** If customer `131` is removed, seat 1 under `13` is never
  reissued — the seat *is* the identifier, and a printed card carries it.

**Display them in a monospace font.** They are structural, people compare them by eye, and
proportional digits make `11` and `1431` hard to line up.

Accept them typed with separators — someone reading `1431` off a card may write `1-4-3-1`.

---

## 3 · Roles

Seven staff roles plus customers. Roles **imply** one another, so `SUPER_ADMIN` reaches everything:

```
SUPER_ADMIN
  └── ADMIN
        ├── KYC_REVIEWER          registration queue, view NIC documents, approve/reject
        ├── INVENTORY_CLERK       items, stock, goods receipts
        ├── PROCUREMENT_OFFICER   suppliers, purchase orders
        ├── FINANCE_OFFICER       payment verification, financial reports
        └── SUPPORT_AGENT         read-only customer data
                 ⋮
              (all five imply STAFF)

DISTRIBUTOR   — a customer. Implies nothing. Portal only.
```

**Two-factor:** the five specialist roles require TOTP and are handed an enrolment QR on first
sign-in. `ADMIN` and `SUPER_ADMIN` do **not** — turned off at the client's request.

Navigation is filtered by role. A user sees only sections containing at least one item they can
reach, so the shell must handle a nav with two entries as gracefully as one with fifteen.

---

## 4 · Signing in

**One field, not two.** An account is identified by an email address, a **phone number**, or both —
at least one. Most customers here have no email at all.

```
Email or phone number   [ you@example.lk  or  077 123 4567 ]
Password                [ ................................ ]
```

Phone numbers are normalised: `0771234567`, `771234567`, `94771234567`, `+94771234567` and any of
those with spaces or dashes are all the same number. Whatever the person types works.

- No email confirmation step. An account works the moment it is created.
- Wrong details give **one** message for both "no such account" and "wrong password" — never
  reveal which.
- Staff roles get a TOTP step after the password; admins go straight in.
- **There is no password reset.** An administrator resets it by hand. Do not design a "forgot
  password" link that goes nowhere.

---

## 5 · Staff application — 19 screens

Grouped as the navigation groups them. Route, who can reach it, and what it does.

### Overview

**`/` Dashboard** · everyone
Five figures and a list: active items, units reserved, items below reorder level, open reorder
alerts, item-set availability. Each tile links to the screen that explains it. This is a status
board, not a workspace.

### Catalogue

**`/items` Items** · everyone (edit: `INVENTORY_CLERK`)
The product list. Search by name or item code, filter by category and active state. Create and edit
items: code, name, description, category, unit cost, selling price, reorder level. Two extra
reference prices (retail, wholesale) are stored and **never** used to price anything — a sale takes
its price from the order line.
Per item: **supplier prices** — what each supplier charges, which is what a purchase order quotes.
Creating an item can post **opening stock** in one step.

**`/item-sets` Item sets** · everyone (edit: `INVENTORY_CLERK`)
Bundles — *"Bedroom Pack: 1 bed frame, 1 mattress, 1 wardrobe, 1 bedside table"* — with their own
set price, below the sum of the parts. These are the **reward packs** a customer chooses at
registration. Shows **availability**: how many complete sets could be assembled from current stock,
which is the limiting item across the whole set.

### Inventory

**`/stock` Stock** · everyone (adjust: `INVENTORY_CLERK`)
Every item's position across all stores. Three numbers that must never be conflated:

- **On hand** — physically present
- **Reserved** — held against an order, still present
- **Available** — on hand − reserved, the only number that matters when deciding if you can sell

Actions: post an **adjustment** (with a reason, always), run a **reorder scan**, **rebuild from
ledger** (recomputes every balance from the movement history and reports what it repaired).
Also: movement history, and reorder alerts.

**`/stores` Stores** and **`/stores/:id`** · everyone
Physical locations. One is the default. A store's detail page lists what is in it.

### Procurement

**`/suppliers` Suppliers** · everyone (edit: `PROCUREMENT_OFFICER`)
Code, name, contact, email, phone, address; activate and deactivate. A supplier with no address
cannot be sent an order — the send dialog says so before you press the button.

**`/purchase-orders` Create order** · everyone (edit: `PROCUREMENT_OFFICER`)
Purchase orders through their life:

```
draft ──send──▶ sent ──confirm arrival──▶ (received)
  │                                            │
  └── editable                                 └── lines frozen
       cancel                                      → Received orders
```

Creating an order: choose a supplier, then add lines. **The line editor is a fixed entry row —
Item, Quantity, Unit cost, Add — above a table of what has been added**, with Edit and Delete per
row, a line total per row, and an order total at the foot. Adding clears the entry row. Items
already on the order are excluded from the picker. Enter adds the line; it must not submit the
order.

Sending emails the supplier and **freezes the lines permanently**. Confirming arrival records who
signed for it and moves it to Received orders. It does **not** move stock.

**`/receiving` Received orders** · everyone (`INVENTORY_CLERK` to shelve)
Two lists: **waiting to be put into a store**, and **already added to stores**.

> **The split matters.** Confirming a delivery arrived and putting it on a shelf are two duties.
> **Stock only moves at "add to stores."** A delivery can sit in the first list for a day, and
> during that day the stock figures are honest about not having it yet.

Also supports a manual receipt, for stock arriving with no purchase order.

### Sales

**`/hierarchy` Referral hierarchy** · `ADMIN`
The referral tree, explorable. Roots, downline, upline, per-node stage progress.

**`/rewards` Reward packs** · `ADMIN` · *carries a count badge when anybody is waiting*
Two lists: **waiting to be issued** and **already issued**. A customer appears in the first the
moment their fifth referral is approved. Issuing takes the pack's contents out of a store — real
stock movement — and records who issued it. No stock has moved for anything in the first list.

### Reporting

**`/reports` Stock and sales** · everyone
Three reports, each with a CSV export: stock position (value = on hand × unit cost), sales over a
date range, customer/buyer totals. Figures are read live with no caching, so they are correct at
the moment they are read.
**The day boundary is Asia/Colombo**, not UTC — an evening sale belongs to that evening.

**`/analytics` Buyer analytics** · `FINANCE_OFFICER`, `SUPPORT_AGENT`, `SUPER_ADMIN`
Who buys what, including a treemap.

### Onboarding

This section reads in the order things happen.

**`/my-registration` User registration** · everyone
*Labelled "User registration" for admins, "My registration" for anyone else.*
An administrator registers a customer in person: creates their account **and** files their
registration in one flow. Fields: name, email and/or phone, password, referrer Business ID, NIC
number, NIC photo, bank transfer slip, address, bank details, chosen item pack.

> **Leaving the referrer blank registers a root.** Only an administrator can do this. It means
> "nobody referred this person" — they get a Business ID of their own, and that ID is what the
> office hands to the first five people they recruit. Somebody has to be first. The customer's own
> registration form still requires a referrer.

As the referrer ID is typed it is validated, then looked up live: the referrer's name appears, with
how many of their five places are used, or an error if the ID is unknown or full.

**`/registrations` Registration verification** · `KYC_REVIEWER`, `ADMIN`
The review queue.

```
submitted ──claim──▶ under review ──▶ approved   → Business ID allocated, permanent
                          │
                          ├──▶ rejected            → final
                          └──▶ resubmit required   → back to the applicant with comments
```

**A record must be claimed before it can be decided**, one reviewer at a time, and **nobody may
review their own**. Reviewing shows the NIC document and bank slip through short-lived,
single-use access tokens — every view is logged against the reviewer.

On approval a banner shows the allocated Business ID and says to hand it to the five people that
customer recruits. That number is the point of the screen.

**`/distributors` Customers** · `ADMIN`
Everyone who has signed up, approved or not. Search by name, email or Business ID; filter to
approved only. Columns: Business ID, name, contact, status, level, who referred them.

**`/distributors/:id` Customer profile** · `ADMIN`
One customer in full: details, their referrer, their direct referrals, stage progress, chosen pack,
registration history — and **referral cards**.

**`/referral-cards/:batchId` Referral cards** · `ADMIN`
Prints the five cards. **Designed for a dot-matrix printer**: 64-column monospace, no colour, no
shading, rules drawn with `=` and `-`, one card per page. Each card carries the parent's name and
ID, the specific child ID that seat will receive, and the item pack.

Only **free** seats print. If seats 1 and 2 are taken, a batch contains cards 3, 4 and 5 — printing
all five would promise an ID somebody already holds.

### Administration

**`/announcements` Announcements** · `ADMIN`
Write a notice with a topic, sub-topic, rich text (bold, italic, underline, highlight, headings,
lists) and an optional picture. **Save and send are separate** — a draft reaches nobody until
"Send to everyone", and each announcement is only ever sent once. Live notices appear at the top
of every customer's portal home page until taken down or until their optional end date. The
composer previews with the same component the portal renders, so the preview cannot drift.

**`/users` Users and roles** · `SUPER_ADMIN`
**Staff accounts only** — customers are deliberately excluded; they belong on the Customers screen,
where their registration, referrer and stages are visible. Create staff accounts and replace their
roles wholesale. A super admin cannot remove their own `SUPER_ADMIN` role.

---

## 6 · Customer portal — 5 screens

Everything here is about **one person's own position**. A customer can see their parent and their
direct referrals and **nothing else** — not their grandparent, not their siblings, not the wider
tree. That boundary is enforced server-side; do not design around it.

### The gate

The portal has five states, and until the last one there is **exactly one screen**:

| State | What the customer sees |
|---|---|
| `REGISTRATION_REQUIRED` | Nothing submitted. Lands directly on the registration form. |
| `PENDING_REVIEW` | Submitted, waiting. Read-only, with a step trail. Nothing to do. |
| `CHANGES_REQUESTED` | Rejected with the door open. Reviewer's comments, and the form again. |
| `REJECTED` | Final. |
| `EXPIRED` | Was approved; the membership period ran out. Portal closed until an admin extends them. Their Business ID, place and referrals are all kept. |
| `ACTIVE` | Approved. All five screens appear at once. |

Navigation is derived from this, not from roles — before approval it collapses to a single link.
Showing four locked pages would be a menu of disappointments.

**A new customer lands on the registration page, not a dashboard.** They arrive at
*"Finish setting up your account"* with what they need listed: their referrer's Business ID, their
NIC and a photo of it, and their bank transfer slip.

### The screens, once active

**`/portal` Dashboard** — Business ID (*"give this to anyone you refer"*), level, direct referral
count, member since, stage progress.

**`/portal/registration` Business registration** — the application, its status, the reviewer's
comments if any, and the step trail. Visible before and after approval.

**`/portal/details` My details** — name, contact, NIC last 4 digits, bank details, address.

**`/portal/stages` My stages** — five stages, how many are complete, what each means, and the item
pack waiting at the end.

**`/portal/referrals` My referrals** — their five seats: who occupies each, which are free.

---

## 7 · Rules the UI must respect

Each of these is enforced by the server. An interface that implies otherwise creates a failure the
user cannot understand.

**Five seats, permanently.** Not raisable. A vacated seat is never refilled.

**A card number is a bearer token.** Eight characters, drawn at random, unguessable, spent once,
and checked against the parent Business ID the applicant also types. It is required at
registration and is **not** the Business ID pattern — the card no longer promises a particular ID,
because seats are consumed in approval order, not sale order. A rejected registration hands the
card back.

**Membership expires.** Counted from approval, default 60 days, set by a super admin and
extendable per person by an admin. Expiry closes the portal and touches nothing else — the tree,
the Business ID and the referrer's stage count are all unaffected, so extending restores
everything. Extending somebody already overdue counts from today. Show it colour-coded on the
customer list and profile, and **never by colour alone** — the label says "5d left" or
"12d overdue" in words.

**Business IDs are allocated at approval and are permanent.** Never editable, anywhere.

**Claim before deciding, and never your own.** Two reviewers cannot hold one record.

**Documents are never linked directly.** NIC scans and bank slips open through a single-use,
short-lived token, and every access is logged with who looked and at what.

**NIC numbers are hashed and encrypted; the plaintext is never stored.** Only the last 4 digits can
be displayed. Never design a screen showing a full NIC.

**The audit log is append-only** — a database guarantee, not a convention. Nothing edits or deletes
it.

**Available ≠ on hand.** Only availability answers "can I sell this".

**Stock moves at "add to stores", not at "delivery arrived".**

**Pack prices on a printed card are frozen** at print time; the catalogue changing later does not
change the paper somebody is holding.

**Reward packs are goods, not money.** An admin issues them; stock drops. There is no clawback.

**Announcement bodies are structure, not HTML.** A typed block document — heading, paragraph,
list, image, with bold/italic/underline/highlight marks. The renderer walks it and emits elements;
there is no `dangerouslySetInnerHTML` anywhere in the path and there must never be. Unknown blocks
render as nothing. The server refuses block types outside its allowlist, links included. Adding a
formatting button means changing three places — editor, server allowlist, renderer — which is
deliberate friction on the part that decides what lands in every customer's browser.

**Announcement images are the only documents served without a token.** The endpoint checks the
document *kind*, so a NIC scan can never be fetched through it.

**Money is LKR**, `14,2` precision, and every price displayed with two decimals.

**The reporting day boundary is Asia/Colombo.**

---

## 8 · API shape

112 endpoints under `/api/v1/`. Same-origin — nginx serves the SPA and proxies `/api` to the
application, so there is no CORS and no bearer token to manage.

**Session is an HTTP-only cookie.** Not readable from JavaScript, sent automatically. A 401 means
the session ended: send the user to sign-in.

**Lists are cursor-paginated**, in a frozen envelope:

```json
{ "data": [ … ], "nextCursor": "opaque-string-or-null" }
```

The current UI follows cursors to completion rather than paginating, because the client asked for
no pagination. `nextCursor: null` means the end. Movement history is capped at 200 per page.

**Errors** carry a stable machine code plus a human message, and field errors where relevant:

```json
{ "code": "REFERRER_AT_CAPACITY",
  "message": "That distributor already has the maximum number of referrals.",
  "fieldErrors": { "email": "INVALID_EMAIL" } }
```

Codes worth handling specifically: `REFERRER_NOT_FOUND`, `REFERRER_AT_CAPACITY`,
`INVALID_BUSINESS_ID`, `SEATS_EXHAUSTED`, `NO_FREE_SEATS`, `IDENTIFIER_REQUIRED`, `INVALID_MOBILE`,
`EMAIL_ALREADY_REGISTERED`, `MOBILE_ALREADY_REGISTERED`, `ACCOUNT_NOT_ACTIVE`, `LOGIN_RATE_LIMITED`,
`REGISTRATION_ALREADY_OPEN`, `INSUFFICIENT_STOCK`.

**Types are generated from the API, not written by hand.** `npm run api:sync` fetches the OpenAPI
document from a running backend and regenerates `src/api/schema.d.ts`. A server-side rename then
fails the build instead of failing in the browser. **Keep this.** Whatever else changes, do not
replace generated types with hand-written ones.

### Key response shapes

```ts
DistributorNode {
  id, businessId, userId, fullName, referredBy, path, status,
  directChildCount, depth, approvedAt, stagesCompleted, bonusEligible, itemSetId
}

StockByItemResponse {
  itemId, sku, itemName, onHand, reserved, available,
  reorderLevel, belowReorderLevel, storeCount, stores[]
}

PurchaseOrderResponse {
  id, poNumber, supplierId, locationId, status, expectedDate, note, total,
  createdAt, sentAt, closedAt, sentToEmail, arrivedAt, arrivalAttestedName, lines[]
}

PortalView {
  access, userId, fullName, email, mobile, joinedAt,
  registration{…}, distributorId, businessId, approvedAt,
  stages{…}, parent, children[], reward{…}
}
```

---

## 9 · Status vocabularies

Every state a badge might need to render.

| Thing | States |
|---|---|
| Registration | `draft`, `submitted`, `under_review`, `approved`, `rejected`, `resubmit_required` |
| Customer | `pending`, `active`, `suspended`, `deleted` |
| Purchase order | `draft`, `sent`, `partially_received`, `received`, `cancelled` |
| Sales order | `awaiting_payment`, `payment_review`, `paid`, `fulfilled`, `cancelled` |
| Payment | `pending`, `verified`, `rejected` |
| Reservation | `active`, `released`, `consumed`, `expired` |
| Reward | `eligible`, `issued`, `cancelled` |
| Reorder alert | `open`, `cleared` |
| User account | `unverified`, `active`, `suspended`, `deleted` |
| Portal access | `REGISTRATION_REQUIRED`, `PENDING_REVIEW`, `CHANGES_REQUESTED`, `REJECTED`, `EXPIRED`, `ACTIVE` |

---

## 10 · Constraints on the build

**Stack:** React 19, Vite 6, Tailwind 4, TanStack Query, react-i18next, react-router.

**Text is internationalised with English as the key** — `t('Save changes')`, not `t('btn.save')`.
Sinhala and Tamil are likely later. Keep it.

**The audience is not comfortable with computers.** The client's own words. Most customers have no
email address. Error messages say what to do, not what went wrong.

**Desktop-first for staff** — the office works on desktops, and the tables are wide.
**The portal must work on a phone**, which is how customers will open it.

**Referral cards must stay 64-column monospace.** That is a dot-matrix constraint, not a style
choice: proportional type prints as a slow bitmap, shading wears the ribbon out.

**Print stylesheets matter.** Cards and invoices are printed on paper that customers keep.
