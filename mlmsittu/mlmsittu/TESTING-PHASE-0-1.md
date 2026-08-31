# Manual Test Guide — Phase 0 and Phase 1

Everything below has been run and passes on this machine. Your job is to confirm it on yours, and
to poke at it in ways I did not think of.

**What is built:** foundation (Flyway, module boundaries, error handling, response envelope, seed
data) and identity (users, Argon2id passwords, session login, TOTP 2FA, six roles, append-only
audit log, login throttling).

**What is not built yet:** items, stock, orders, payments, KYC, referral hierarchy. Those are
Phases 2 onward.

---

## 1. One-time setup

You only ever do this once.

### 1.1 Create the application database role

The app does **not** connect as `postgres`. It connects as a weaker role that has no `UPDATE` or
`DELETE` on `audit_log` — that is what makes the audit trail genuinely append-only instead of
merely append-only by convention. Create it:

```powershell
cd E:\MLM-Sittu\mlmsittu\mlmsittu
& "C:\Program Files\PostgreSQL\18\bin\psql.exe" -h localhost -U postgres -d mlmsitty -f db\bootstrap\01-app-role.sql
```

Password when prompted: `postgres`

You should see `NOTICE: Created role mlmsittu_app.` Running it a second time is safe.

### 1.2 Make psql easier to reach (optional, but you will want it)

```powershell
$env:Path += ";C:\Program Files\PostgreSQL\18\bin"
```

Add it permanently through *System Properties → Environment Variables* if you like.

---

## 2. Starting the app

**First run of the day** — creates the seven test accounts:

```powershell
.\gradlew bootRun --args="--spring.profiles.active=seed"
```

**Any other time:**

```powershell
.\gradlew bootRun
```

Flyway applies migrations automatically at startup. Watch for
`Successfully applied 3 migrations`.

The seeder is idempotent — accounts that already exist are left alone, so you can use the seed
profile every time if you prefer.

### Test accounts

All seven share the password **`Password123!`** and, except the last, the TOTP secret
**`JBSWY3DPEHPK3PXPJBSWY3DPEHPK3PXP`**.

| Email | Role | TOTP |
|---|---|---|
| `super@mlmsittu.local` | SUPER_ADMIN | enrolled |
| `kyc@mlmsittu.local` | KYC_REVIEWER | enrolled |
| `inventory@mlmsittu.local` | INVENTORY_CLERK | enrolled |
| `procurement@mlmsittu.local` | PROCUREMENT_OFFICER | enrolled |
| `finance@mlmsittu.local` | FINANCE_OFFICER | enrolled |
| `support@mlmsittu.local` | SUPPORT_AGENT | enrolled |
| `newadmin@mlmsittu.local` | SUPER_ADMIN | **not enrolled** — use this one to test first-time 2FA setup |

> These credentials are published in the source tree. That is fine for a laptop and unacceptable
> anywhere else. The `seed` profile must never be enabled on staging or production.

### Getting a 2FA code without a phone

```powershell
.\gradlew totp -Psecret=JBSWY3DPEHPK3PXPJBSWY3DPEHPK3PXP
```

Prints the current six-digit code and how long it stays valid.

You can also add the secret to Google Authenticator (choose *Enter a setup key*) and use that —
both produce the same code, which is itself a decent check that the implementation is correct.

---

## 3. Fastest route: import the Postman collection

Import `postman/MLM-Sittu-Phase-0-1.postman_collection.json`.

It has 20 requests in four folders with assertions already written, and a pre-request script that
**computes the TOTP code for you** — no phone, no Gradle task, no typing six digits before they
expire.

Run the folders top to bottom. Postman keeps the session cookie automatically. Everything should
be green.

> Leave the throttling folder for last — it deliberately locks logins from your machine for 15
> minutes. Restarting the app clears it (the limiter is in memory until Phase 7).

The rest of this document is the same thing done by hand, so you can see what each step proves.

---

## 4. Gate 0 — foundation

### 4.1 Build is green from clean

```powershell
.\gradlew clean build
```

Expect `BUILD SUCCESSFUL` and three passing tests.

### 4.2 Module boundaries are enforced — *prove the test can fail*

A boundary test that has never been seen to fail proves nothing. Create this file:

`src\main\java\com\democode\mlmsittu\catalogue\internal\Oops.java`

```java
package com.democode.mlmsittu.catalogue.internal;

import com.democode.mlmsittu.identity.internal.service.AuthService;

class Oops {
    Class<?> reachIntoAnotherModule() {
        return AuthService.class;
    }
}
```

Then:

```powershell
.\gradlew test
```

**Expected:** the build fails with

```
Module 'catalogue' reaches into internals of 'identity'
```

Delete `Oops.java`, run again, confirm green. *This is the check that keeps the codebase from
turning into a ball of mud as Phases 2–6 land.*

### 4.3 Extensions and schema

```sql
SELECT extname FROM pg_extension ORDER BY 1;
```

**Expected:** `ltree`, `pgcrypto`, `plpgsql`.

```sql
\d audit_log
```

**Expected:** `Partitioned table`, partition key `RANGE (created_at)`.

### 4.4 Health check

```
GET http://localhost:8080/actuator/health
```

**Expected:** `{"status":"UP", ...}`

### 4.5 Errors carry a code and leak nothing

```
GET http://localhost:8080/api/v1/no-such-thing
```

**Expected:** a `application/problem+json` body with `type`, `title`, `status`, `detail`, `code`,
`instance` — and **no** Java class name, no `Exception`, no stack trace, no SQL.

### 4.6 The response envelope is frozen

Log in first (section 5), then:

```
GET http://localhost:8080/api/v1/probe/list
```

**Expected:** `{"data":[...],"nextCursor":null}`

Both keys must be present. Phase 7 fills in `nextCursor` **without changing this shape** — if you
ever see a list endpoint return a bare `[...]` array, that is a bug worth stopping for.

---

## 5. Gate 1 — identity, RBAC, audit

### 5.1 Login is two steps for administrators

**Step 1**

```
POST http://localhost:8080/api/v1/auth/login
Content-Type: application/json

{ "email": "finance@mlmsittu.local", "password": "Password123!" }
```

**Expected:** `200` with

```json
{ "mfaRequired": true, "enrolmentRequired": false, "challengeId": "..." }
```

No session cookie is usable yet. **A password alone does not get an administrator in** — that is
the Gate 1 requirement.

**Step 2** — get a code (`.\gradlew totp -Psecret=JBSWY3DPEHPK3PXPJBSWY3DPEHPK3PXP`), then:

```
POST http://localhost:8080/api/v1/auth/login/totp
Content-Type: application/json

{ "challengeId": "<paste from step 1>", "code": "<six digits>" }
```

**Expected:** `200`, a `Set-Cookie: MLMSESSION=...` header carrying `HttpOnly` and `SameSite=Lax`,
and a user object. Confirm the response contains **no** `passwordHash` and **no** `totpSecret`.

**Also try:**

| Do this | Expect |
|---|---|
| Wrong code `000000` | `401` `INVALID_TOTP_CODE` |
| Reuse the same `challengeId` after success | `401` `MFA_CHALLENGE_INVALID` (single-use) |
| Wait 5+ minutes, then use the challenge | `401` `MFA_CHALLENGE_INVALID` (expired) |
| Six wrong codes on one challenge | `401` — the challenge is burned after five |

### 5.2 First-time 2FA enrolment

Log in as `newadmin@mlmsittu.local`. Step 1 returns something different:

```json
{
  "mfaRequired": true,
  "enrolmentRequired": true,
  "challengeId": "...",
  "totpSecret": "CG7247XXVTNDN32NMEP3CH44UIPPVKUO",
  "otpauthUri": "otpauth://totp/MLM%20Sittu:newadmin%40mlmsittu.local?secret=..."
}
```

Add that secret to Google Authenticator, or run
`.\gradlew totp -Psecret=<the secret you got>`, then complete step 2.

**Expected:** login succeeds and the secret is now stored. Log out and log in again — this time
you get the ordinary challenge, not another enrolment. *Nothing is persisted until a correct code
proves the user actually saved the secret.*

### 5.3 All six roles

Log in as each account and call each probe endpoint:

```
GET /api/v1/probe/super-admin     needs SUPER_ADMIN
GET /api/v1/probe/kyc             needs KYC_REVIEWER
GET /api/v1/probe/inventory       needs INVENTORY_CLERK
GET /api/v1/probe/procurement     needs PROCUREMENT_OFFICER
GET /api/v1/probe/finance         needs FINANCE_OFFICER
GET /api/v1/probe/support         needs SUPPORT_AGENT
```

**Expected:** each account gets `200` on its own endpoint and `403` `FORBIDDEN` on the other five.

The one worth checking deliberately: **as `super@mlmsittu.local`, call `/probe/finance`.**
It must return `403`. Super admin is a role, not a master key — architecture §8.1 requires that a
single person cannot complete a financially sensitive loop alone, and a blanket bypass would undo
that.

> These `/probe/*` endpoints are temporary scaffolding. Each one gets replaced by the real
> endpoint that role guards as Phases 2–6 land.

### 5.4 Logout kills the session

```
POST /api/v1/auth/logout        → 204
GET  /api/v1/auth/me            → 401  (same cookie, now dead)
```

### 5.5 Sessions do not survive a restart

Log in, then restart the app, then call `/api/v1/auth/me` with the same cookie.

**Expected: `401`.** This is correct for now — sessions live in memory until Phase 7 adds Redis
(P7-01), where the same test flips to `200`. Noting the failure here is the point of the exercise.

### 5.6 Login throttling

Send the same bad-password login **11 times**:

```
POST /api/v1/auth/login
{ "email": "support@mlmsittu.local", "password": "WrongPassword1!" }
```

**Expected:** attempts 1–10 return `401`, attempt **11 returns `429`** with code
`LOGIN_RATE_LIMITED`.

Restart the app to clear it. Limits are per-instance and in memory until Phase 7.

### 5.7 Audit log captures privileged changes

Log in as `super@mlmsittu.local`, then:

```
GET /api/v1/admin/users
```

Copy the `id` of `support@mlmsittu.local`, then:

```
PUT /api/v1/admin/users/<that-id>/roles
Content-Type: application/json

{ "roleCodes": ["KYC_REVIEWER", "SUPPORT_AGENT"] }
```

Now look at the trail:

```sql
SELECT action, entity_type, entity_id, actor_id, before, after, ip, created_at
FROM audit_log
ORDER BY created_at DESC
LIMIT 5;
```

**Expected** — the newest row:

| column | value |
|---|---|
| `action` | `USER_ROLES_CHANGED` |
| `actor_id` | the super admin's id |
| `entity_id` | the support user's id |
| `before` | `{"roles": ["SUPPORT_AGENT"]}` |
| `after` | `{"roles": ["KYC_REVIEWER", "SUPPORT_AGENT"]}` |
| `ip` | `::1` |

**Rejected** actions are recorded too. Try `{"roleCodes":["WIZARD"]}` — you get `400`
`UNKNOWN_ROLE`, and a `USER_ROLES_CHANGED_FAILED` row appears with
`after = {"reason": "UNKNOWN_ROLE"}`. Failed logins land as `LOGIN_FAILED` the same way. *A
rejected privileged action is exactly what you want to find in an audit log six months later.*

### 5.8 The audit log cannot be rewritten — *the important one*

Connect **as the application role**, not as postgres:

```powershell
& "C:\Program Files\PostgreSQL\18\bin\psql.exe" -h localhost -U mlmsittu_app -d mlmsitty
```

Password: `mlmsittu_app`

```sql
DELETE FROM audit_log;                  -- ERROR: permission denied for table audit_log
UPDATE audit_log SET action = 'x';      -- ERROR: permission denied for table audit_log
DELETE FROM audit_log_2026_08;          -- ERROR: permission denied (partitions too)
SELECT count(*) FROM audit_log;         -- works
```

**All three writes must fail.** If any succeeds, the app is connecting as a superuser and the
whole control is void — check `spring.datasource.username` in `application.properties` reads
`mlmsittu_app`.

The partition case matters on its own: revoking on the parent table alone would leave a direct
`DELETE FROM audit_log_2026_08` wide open.

### 5.9 Passwords are Argon2id, never plaintext

```sql
SELECT email, left(password_hash, 38) FROM app_user LIMIT 3;
```

**Expected:** every row starts `$argon2id$v=19$m=65536,t=3,p=1$` — the parameters from
architecture §7.3.

---

## 6. Things worth trying that I have not scripted

The scripted checks pass. These are the ones where you might find something I missed:

- Log in as one role, then hand-edit the `MLMSESSION` cookie value. Expect `401`, not a crash.
- Send `{"email":"not-an-email","password":""}` to login. Expect `400` `VALIDATION_FAILED` with
  per-field errors — and no hint about whether the account exists.
- Send malformed JSON (`{`). Expect `400` `MALFORMED_REQUEST_BODY`, no Jackson class names.
- Try `PUT /api/v1/admin/users/<your-own-id>/roles` with `["SUPPORT_AGENT"]` while logged in as
  the only super admin. Expect `403` `CANNOT_REMOVE_OWN_SUPER_ADMIN` — you should not be able to
  lock yourself out.
- Log in as `support@mlmsittu.local` and call `/api/v1/admin/users`. Expect `403`.
- Time a login for a **nonexistent** email against one with a **wrong password**. They should take
  roughly the same time; a big gap would let someone enumerate valid accounts.

---

## 7. Where I departed from the plan, and why

Four deviations. None are hidden and all are reversible.

1. **One Gradle module, not nine.** You chose this. The boundaries are enforced as packages by
   ArchUnit instead, which is the protection that actually matters — see 4.2. Splitting into real
   Gradle modules later stays mechanical because the dependency graph is kept honest meanwhile.

2. **Spring Boot 4.1.0, not 3.3.** Also your choice. Three concrete differences from the
   architecture document: the AOP starter is now `spring-boot-starter-aspectj`, Jackson is
   version 3 (`tools.jackson.*`, unchecked exceptions), and Spring Security is 7.x. Everything the
   document specifies is implemented; some import lines differ.

3. **No Docker.** The plan assumes Postgres and MinIO in containers. You have PostgreSQL 18
   installed natively and it works fine, so I used it. **This becomes a real decision at Phase 4**,
   which needs object storage for KYC documents — that is where you either install Docker for
   MinIO, or I write a local-filesystem storage adapter behind the same interface. Nothing before
   Phase 4 is affected.

4. **CSRF tokens are off.** Session cookies are `SameSite=Lax`, so a browser will not attach them
   to a cross-site `POST`, `PUT` or `DELETE` — which is the attack. Nothing changes state on a
   `GET`. This is a normal choice for a same-origin JSON API and it keeps Postman usable. It
   **must** be revisited if the frontend ever ends up on a separate origin needing
   `SameSite=None`, because that removes the protection the decision rests on. Flagged for P9-03.

Two smaller notes:

- `db/seed/dev-seed.sql` from the plan is a Java seeder instead (`DevDataSeeder`). Argon2id hashes
  cannot be produced in plain SQL, and the seeder grows naturally into Phase 2's items and sets.
- The plan's P0-07 check (`SELECT count(*) FROM item` → 20) cannot run yet — `item` does not exist
  until Phase 2. The seeder covers the users half now and gains the catalogue half in Phase 2.

---

## 8. Referral rewards — resolved, with a consequence

**Settled 8 Aug 2026.** §0.2 is correct: the four-stage enrolment reward mechanic **is in scope** —
four stages, one unlocked per referred user, four children maximum per referrer, bonus stage on
completion. The "no entitlement logic" exclusions in §4.4, §12 and the plan's scope note were an
error and have been amended out of `architecture.md`, `development-plan.md` and
`system-flows.html`.

### The consequence you should know about now

Building rewards on referral counts introduces a **check-then-act race**, and it is the same class
of bug as the stock reservation problem in §4.5.

Deriving a stage unlock from a counted value means: read the count, decide, write. Two referral
approvals for the same parent committing at the same moment will **both** read `3`, **both**
conclude they are the fourth, and **both** award the stage. The identical race lets the width cap
of 4 admit a fifth child — the `assertCapacity` sketch in §2.2 counts and compares without holding
a lock, so it is not actually a check.

So, at Phase 4:

- Stage state gets **stored and transactionally guarded**, never recomputed from
  `referral_summary.direct_count` at decision time. That view stays what §4.4 says it is:
  attribution and reporting.
- The parent distributor row gets **locked before count-and-insert**.
- The width cap gets a **database constraint**, not only the service-layer check.
- All of it gets a concurrency test in the shape of P3-05/P3-06 — run it many times, then remove
  the guard and confirm the test fails, so the test is known to catch the real bug.

None of this affects Phases 0–3. I will build straight through them.

### Five things §0.2 does not say

I will need these before the reward work can start. Not urgent — Phases 2 and 3 come first.

1. **What does unlocking a stage actually grant?** §0.2 mentions "gated product entitlements" and
   "payment logic keyed to enrollment counts" without saying which, or how much.
2. **Does a referral count when the child registers, or when their KYC is approved?** Approval is
   my assumption — that is where the Business ID is allocated — but it decides when stage state
   changes, so it should not be an assumption.
3. **What happens when a counted child is later rejected, suspended or deleted?** Does the parent
   lose that stage? Are benefits already granted clawed back?
4. **What is the bonus stage?** §0.2 says a user becomes "eligible to unlock" it, without saying
   what unlocking takes or what it yields.
5. **Is money paid out?** If so it needs the §8.1 separation-of-duties treatment, and §5's "no
   automated disbursement" needs revisiting.

Answers to 1, 2 and 5 change the **data model**, not just the logic — guessing there means rework.

`system-flows.html` also has no flow for stage unlocking. It should get one as F-15 before Phase 4.

---

## 9. When this passes

Tell me and I will start **Phase 2 — catalogue, stock ledger, procurement**. The development plan
calls it the correctness core and its gate the most important in the project: append-only stock
ledger, `stock_level` as a replayable projection, partial goods receipt, and stock that cannot go
negative by any route you can find.

If anything above fails, or behaves in a way that surprises you, tell me what you did and what you
saw. A confusing response is a finding, not a false alarm.
