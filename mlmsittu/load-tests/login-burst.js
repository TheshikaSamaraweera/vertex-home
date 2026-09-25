// Sign-in burst: many people pressing "Sign in" in the same second (a 9 am rush).
//
//   docker run --rm --network host -e USERS=20 -v "$PWD/load-tests:/scripts" grafana/k6 run /scripts/login-burst.js
//
// Why this matters for sizing: passwords are hashed with Argon2 using 64 MB of memory each, on
// purpose (it makes stolen hashes expensive to crack). Twenty simultaneous sign-ins therefore ask
// for over a gigabyte at once — far more than normal browsing ever does — so this, not page
// traffic, is what decides how much memory the app needs.
//
// Uses the demo customer accounts. Successful sign-ins reset the login rate limiter, so a burst of
// correct passwords is safe to repeat.

import http from 'k6/http';
import { check } from 'k6';

const BASE = __ENV.BASE_URL || 'http://localhost:8080';
const USERS = Number(__ENV.USERS || 20);

const ACCOUNTS = [
  'nimal.perera', 'kamala.fernando', 'ruwan.jayasinghe', 'ishani.de.silva', 'chaminda.bandara',
  'dilini.wickramasinghe', 'asanka.rathnayake', 'priyanka.gunawardena', 'tharindu.senanayake',
  'sanduni.herath', 'lahiru.weerasinghe', 'nadeesha.kumari', 'pradeep.dissanayake',
  'hasini.madushani', 'sachith.abeywardena', 'sunethra.silva', 'mahesh.kumara',
  'thilini.jayawardena', 'buddhika.samarasinghe',
].map((name) => `${name}@customer.lk`);

export const options = {
  scenarios: {
    // Every virtual user signs in once, all released together.
    burst: { executor: 'per-vu-iterations', vus: USERS, iterations: 1, maxDuration: '2m' },
  },
  thresholds: { http_req_failed: ['rate==0'] },
  summaryTrendStats: ['med', 'p(95)', 'max'],
};

export default function () {
  const identifier = ACCOUNTS[(__VU - 1) % ACCOUNTS.length];
  const res = http.post(
    `${BASE}/api/v1/auth/login`,
    JSON.stringify({ identifier, password: __ENV.CUSTOMER_PASS || 'customer-pass-123' }),
    { headers: { 'Content-Type': 'application/json' }, jar: null },
  );
  const ok = check(res, { 'signed in': (r) => r.status === 200 });
  if (!ok) console.log(`${identifier} -> ${res.status} ${String(res.body).slice(0, 160)} ${res.error || ''}`);
}
