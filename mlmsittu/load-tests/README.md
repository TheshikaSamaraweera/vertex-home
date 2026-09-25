# Load tests

[k6](https://k6.io) script for the MLM Sittu API. Read-only: it creates, edits and deletes nothing.

## Run

Backend on `:8080` with the demo data loaded (the staff account `admin@demo.local` and the
`…@customer.lk` customers).

```bash
# 30-second check that the script itself works
docker run --rm --network host -e SMOKE=1 -v "$PWD/load-tests:/scripts" grafana/k6 run /scripts/app-load.js

# Realistic: 150 users who pause 1–3 s between screens (about 5 minutes)
docker run --rm --network host -v "$PWD/load-tests:/scripts" grafana/k6 run /scripts/app-load.js

# Stress: 500 users clicking non-stop, to find the ceiling
docker run --rm --network host -e PEAK=500 -e THINK=0 -v "$PWD/load-tests:/scripts" grafana/k6 run /scripts/app-load.js
```

Options (`-e NAME=value`): `BASE_URL`, `PEAK`, `THINK`, `STAFF_SESSIONS`, `CUSTOMER_SESSIONS`,
`STAFF_EMAIL`, `STAFF_PASSWORD`, `CUSTOMER_PASS`, `DEBUG=1` (log failing requests). The full k6
summary is written to `results/summary.json`; the folder must be writable by the container
(`chmod 777 load-tests/results`).

The mix is 70% staff (dashboard, items, search, paging, stock, orders, receipts, customers,
referral tree) and 30% customers (portal home, referrals). Pass mark for page requests:
p95 < 500 ms, p99 < 1 s, errors < 1%. Sign-ins are measured separately — Argon2 password hashing
takes about half a second by design.

## Results — 24 Sep 2026

One 8-core / 31 GB laptop running **everything**: k6, the Spring Boot backend (dev `bootRun`) and
Postgres in Docker. Demo data: 125 items, 21 customers, 6 purchase orders.

| Run | Users | Requests | Throughput | Errors | Median | p95 | p99 | Pass |
|---|---|---|---|---|---|---|---|---|
| Realistic (1–3 s pauses) | 150 | 24,945 | 80 /s | 0% | 8 ms | 16 ms | 23 ms | ✅ |
| Stress, no pauses | 200 | 294,386 | 992 /s | 0% | 84 ms | 239 ms | 314 ms | ✅ |
| Stress, no pauses | 500 | 341,456 | 1,026 /s | 0% | 159 ms | 596 ms | 853 ms | ❌ p95 |

**What it means**

- At a realistic pace, 150 simultaneous users barely register: every endpoint answers in under
  25 ms at p95, and Postgres peaked at under half a core.
- Throughput levels off at about **1,000 requests/second**. At that point the backend was using
  about 4.8 cores and Postgres 1.6, with k6 on the rest of the machine — the ceiling is this
  laptop running all three, not a fault in the app. Nothing errored in any run.
- A real user makes roughly one request per second while active, so this one machine serves
  several hundred people using the system at the same moment.
- Slowest endpoint under stress: `stock by item` (several stores' figures per item), then the
  customer directory and the referral tree — all still under 900 ms at p95 with 500 non-stop users.

**Tuning notes from the runs**

- A larger database pool (30 instead of 10) did **not** raise throughput. Leave `DB_POOL_MAX` as it is.
- Sessions live in Postgres (Spring Session JDBC), so every request reads and updates its session
  row. Hundreds of requests sharing one session serialise on that row — the first version of this
  script did that and reported a false ceiling. Real users have their own sessions and are
  unaffected, but if the API is ever called by an integration that reuses one session at high
  rate, expect exactly that queueing. Redis-backed sessions would remove the per-request write.
- For numbers that describe production, run k6 from a different machine against a production
  build (`java -jar`, `prod` profile), not the dev server.
