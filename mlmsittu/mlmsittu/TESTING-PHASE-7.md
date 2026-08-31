# Testing Phase 7 — Scale-Up

Nothing on any screen changed. That is the point of this phase: it makes the system survive a
restart and run on more than one machine, and if you can tell the difference by looking at it,
something has gone wrong.

**One decision worth knowing before you start.** The plan called for Redis. There is no supported
Redis build for Windows, and PostgreSQL — already running, already holding your data — does the
same two jobs. So sessions and rate limits live in the database. Everything Gate 7 asks for still
holds, and moving to Redis later is a dependency and two properties.

---

## 0. Start it

```powershell
cd E:\MLM-Sittu\mlmsittu\mlmsittu
.\gradlew bootRun
```

The first start applies migrations **V20** and **V21**. Watch for `Successfully applied 2
migrations`.

---

## 1. Your session survives a restart

This is the one that failed in Phase 1, deliberately, so that fixing it would be visible.

1. Sign in at http://localhost:5173.
2. **Stop the backend** (Ctrl+C in the Gradle terminal). Leave the browser tab alone.
3. Start it again: `.\gradlew bootRun`.
4. Reload the page.

**You should still be signed in.** Before this phase you would have been thrown back to the login
screen, because the session lived in the application's memory and died with it.

To see where it went:

```powershell
psql -U postgres -d mlmsitty -c "SELECT session_id, principal_name FROM spring_session;"
```

Your session is a row. Sign out and check again — the row is gone.

---

## 2. Two instances share it

This is what a shared session store actually buys, and it is the thing that makes a second server
possible at all.

Leave the first instance running and start a second in another terminal:

```powershell
.\gradlew bootRun --args="--server.port=8081"
```

Both talk to the same database. Now take the session cookie your browser already has and send it
to the *other* instance:

```powershell
# in the browser devtools console, on a tab where you are signed in:
fetch('http://localhost:8081/api/v1/auth/me', { credentials: 'include' }).then(r => r.status)
```

**Expect 200**, and the response is your own account. The instance on 8081 has never seen you log
in; it read your session out of PostgreSQL.

> If you prefer not to use devtools, the same thing is visible in the verification I ran: a cookie
> issued by 8080 returned `200` and the correct name on 8081, while a made-up cookie returned
> `401`.

---

## 3. Rate limits hold across both

Before this phase the limiter counted in memory, so two instances allowed twice the limit and a
restart wiped the count.

With both instances running, make **six failed logins against 8080 and six against 8081** — same
email, wrong password, alternating. The limit is ten.

**The eleventh attempt should be refused with 429**, regardless of which instance it lands on.

```powershell
psql -U postgres -d mlmsitty -c "SELECT limit_key, count(*) FROM rate_limit_attempt GROUP BY 1;"
```

You will see two keys counting: one for the account and one for your IP address.

> **Worth knowing:** the per-IP limit is real, and it caught me out during testing. Twelve wrong
> logins from one machine will then block *your own* login from that same machine for fifteen
> minutes — including the admin account. That is correct behaviour, not a bug. To clear it while
> testing: `DELETE FROM rate_limit_attempt;`

---

## 4. Emails cannot outlive a failed transaction

This is the subtlest change and the most valuable one.

Before, sending a purchase order emailed the supplier *inside* the transaction that marked the
order sent. Two things could go wrong, and both did in principle:

- the database failed a moment later, the order rolled back, and the supplier kept the email;
- the mail server was down, and an otherwise perfectly good order was refused.

Now the email is written to a table in the same transaction and delivered a few seconds later.

**Send a purchase order** as you normally would, then:

```powershell
psql -U postgres -d mlmsitty -c "SELECT recipient, subject, status, attempts FROM outbox_message ORDER BY created_at DESC LIMIT 5;"
```

- Immediately after sending: one row, `status = pending`.
- Within about five seconds: `status = sent`, `attempts = 1`.

The email itself still appears in the backend log exactly as before.

**With both instances running**, send another order. Two pollers are now competing for the same
table. **The row must show `attempts = 1`** — not 2. That is `FOR UPDATE SKIP LOCKED` doing its
job; without it both instances would send the same email.

---

## 5. Pagination, which you should not be able to notice

Every list still looks and behaves exactly as it did. The catalogue is now fetched in pages behind
the scenes, and the item pickers still contain every item.

The check that matters is that nothing broke: **open any screen with an item dropdown and confirm
the whole catalogue is there.** If pagination were wrong you would see a truncated list.

For the API-level proof:

```
GET /api/v1/items?limit=7
  -> { "data": [ ...7 items... ], "nextCursor": "UGVyZiBpdGVt..." }

GET /api/v1/items?limit=7&cursor=<that cursor>
  -> the next 7, no repeats
```

The envelope is the same `{ data, nextCursor }` it has been since Phase 0. A made-up cursor is
refused with `INVALID_CURSOR` rather than quietly starting again from the top.

---

## 6. What is built but switched off

**Read replica routing.** You have one database, so there is nothing to route to and the routing
does not exist at runtime. When a replica appears, three properties turn it on:

```properties
spring.datasource.replica.url=jdbc:postgresql://replica-host:5432/mlmsitty
spring.datasource.replica.username=mlmsittu_app
spring.datasource.replica.password=...
```

Read-only work then goes to the replica. **Stock availability deliberately does not** — it stays
on the primary whatever happens, because a replica can be seconds behind and answering "is there
enough?" from a stale copy is how the same units get promised to two customers.

---

## 6b. If you were logged in before this phase

**Moving sessions into the database changed the cookie format**, and a browser holding the old one
hit a bug: every request returned 500, the error page included, so the application was unusable
for that browser until its cookies were cleared.

That is fixed — an unrecognisable cookie is now ignored, and you are simply treated as logged out.
**You should not have to clear anything.** Load the app, log in, carry on.

<details>
<summary>What was actually wrong, if you are curious</summary>

Spring Session base64-decodes the cookie and uses the result directly in
`WHERE SESSION_ID = ?`. An old Tomcat id like `CC3E7A94F5BA0B27AEECDAA63FB6E089` is thirty-two
valid base64 characters, so it decodes happily — into twenty-three bytes, three of which are NUL.
PostgreSQL refuses a text parameter containing a NUL byte, so the query failed rather than simply
finding no row.

The real fault was not the stale cookie. It was that a value taken straight off the network was
being used in a query without anyone checking it could be an identifier at all. Any junk cookie —
from another app on `localhost`, or edited by hand — did the same thing.

</details>

---

## 7. What to watch for

| Symptom | What it means |
|---|---|
| Logged out after a restart | sessions are back on the heap — check `spring_session` has rows |
| A cookie works on 8080 but not 8081 | the two instances are not sharing a session store |
| The 11th failed login succeeds | the rate limit is counting per instance again |
| `attempts = 2` on an outbox row | two pollers delivered the same message — `SKIP LOCKED` is not working |
| An item dropdown looks short | cursor-following stopped early |
| A list screen shows a raw array | something bypassed the frozen envelope |
| Every request returns 500 after an upgrade | a cookie is reaching the session lookup unvalidated |

---

## What changed in the plan, and why

| Ticket | Plan said | Built | Why |
|---|---|---|---|
| P7-01/02 | Redis sessions | PostgreSQL (`spring-boot-starter-session-jdbc`) | No supported Redis on Windows; same observable behaviour |
| P7-05 | Redis rate limiting | PostgreSQL | Same store, same reason |
| P7-07 | Read replica routing | Built, dormant | No replica exists yet; two properties away |
| P7-08 | Query pass | Done, one fix | See below |

**The query pass found one real problem.** Measured against 53,000 items rather than the 300 in
your development database, the paginated catalogue was doing a full scan and a sort — 78 ms to
return eleven rows, and worsening with every item added. It needed an index (V21) *and* a change to
how the query is written: the form JPQL can express cannot use that index for a seek. The native
row-value form does. **45 ms → 1.4 ms**, same rows, same index.

Everything else — movement history, ledger replay, rate-limit counting, the outbox poll — already
used an index and ran in under two milliseconds at that volume.
