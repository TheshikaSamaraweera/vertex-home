# MLM Sittu — Frontend

React 19 · Vite · TypeScript · Tailwind 4. Covers Phases 1–3: sign-in, catalogue, item sets,
stock, reservations, procurement, users.

---

## Running it

**Two terminals.** The backend must be up first — the frontend proxies to it.

```powershell
# Terminal 1 — backend
cd E:\MLM-Sittu\mlmsittu\mlmsittu
.\gradlew bootRun --args="--spring.profiles.active=seed"

# Terminal 2 — frontend
cd E:\MLM-Sittu\mlmsittu\frontend
npm install     # first time only
npm run dev
```

Open **http://localhost:5173** — not 8080. Sign in with any seeded account:

| Account | Sees |
|---|---|
| `inventory@mlmsittu.local` | items, stock, adjustments, item sets, reservations, goods receipt |
| `procurement@mlmsittu.local` | suppliers, purchase orders |
| `super@mlmsittu.local` | users and roles, ledger rebuild |

Password `Password123!` for all. Two-factor code:

```powershell
cd ..\mlmsittu
.\gradlew totp -Psecret=JBSWY3DPEHPK3PXPJBSWY3DPEHPK3PXP
```

---

## How it talks to the backend

**One origin, no CORS.** Vite proxies `/api/*` to `localhost:8080`, so the browser sees a single
origin and the session cookie is first-party. Production does the same thing through Cloudflare
(architecture §6.1).

The alternative — the frontend on its own origin — forces `SameSite=None`, which requires
`Secure`, which requires HTTPS in local development. This avoids all of that.

**There is no token.** The backend issues an `HttpOnly` session cookie that JavaScript cannot read,
by design. `AuthContext` answers "am I signed in?" by calling `/auth/me`, not by inspecting
storage — so a session revoked server-side stops working immediately.

**Errors are RFC 9457 problem documents.** `client.ts` turns them into a typed `ApiError` carrying
the machine-readable `code`. Screens branch on `error.code === 'INSUFFICIENT_STOCK'`, never on
message text, which is for humans and will be translated.

---

## Keeping types honest with the backend

Types are **generated from the running backend**, not hand-copied (architecture §6.2).

```powershell
npm run api:sync     # fetch /v3/api-docs -> openapi.json -> src/api/schema.d.ts
npm run api:check    # fail if the checked-in spec no longer matches the backend
```

`src/api/types.ts` gives the generated shapes readable names, but resolves *through* them — so a
Java DTO that gains or loses a field breaks `tsc` here. That is the entire point.

`api:check` is what Phase 8 (P8-05) wires into CI. It compares the whole document, not just the
path list: a field added to a request body changes no paths but breaks the types just as
thoroughly.

**Whenever you change a backend DTO or controller, run `npm run api:sync`.**

---

## Layout

```
src/
├── api/
│   ├── client.ts      fetch wrapper, ProblemDetail -> ApiError, PagedResponse
│   ├── schema.d.ts    GENERATED — do not edit
│   ├── types.ts       readable aliases over the generated shapes
│   └── queries.ts     TanStack Query hooks + cache invalidation
├── auth/
│   ├── AuthContext.tsx   session state via /auth/me
│   └── LoginPage.tsx     password -> TOTP -> enrolment
├── components/
│   ├── ui.tsx         buttons, fields, table, badges, modal, error banner
│   └── AppShell.tsx   role-filtered navigation
└── pages/             one per area
```

---

## Decisions worth knowing

**TanStack Query** is not named in the architecture. It is here because stock data must not go
stale — a goods receipt changes stock levels, set availability, reorder alerts and the purchase
order at once, and `queries.ts` invalidates all of them together. Hand-rolling that is where
screens quietly start showing numbers that are no longer true.

**i18n uses the English text as the key** — `t('Save changes')`, not `t('common.save')`. English
needs no bundle, nothing can drift between a key and its label, and adding `si.json` later is
purely additive. §6.5 asks for i18n scaffolding from day one to avoid a Sinhala retrofit; this is
the cheapest form of that which still wraps every string.

**UI primitives are hand-written**, not vendored from shadcn/ui. shadcn is itself "copy the source
into your repo" — the value is owning the components, and this is a dozen of them with no Radix
dependency, styled from the same tokens as the project's documentation.

**Client-side role checks are UX only.** `AppShell` hides links a role cannot use, but every route
is enforced again by `@PreAuthorize`. Typing a URL directly still returns 403.

---

## Not built yet

- **PWA / service worker** (`vite-plugin-pwa`) — deferred to Phase 8 with the rest of deployment. Note §6.3: the service worker must never cache stock levels, payment status or KYC records.
- **Cursor pagination** — the `{ data, nextCursor }` envelope is already parsed in `queries.ts`; Phase 7 fills in the cursor and only that file changes. P7-04 checks precisely that the screens did not need rewriting.
- **D3 treemap and referral hierarchy** — Phase 6.
- **KYC review queue** — Phase 4.
- **Sales, payments, invoices** — Phase 5.
