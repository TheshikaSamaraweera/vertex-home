// Load test for MLM Sittu — k6 (https://k6.io).
//
// Run (backend on :8080, demo data loaded):
//
//   docker run --rm --network host -v "$PWD/load-tests:/scripts" grafana/k6 run /scripts/app-load.js
//
// Environment overrides (pass with -e NAME=value):
//   BASE_URL        default http://localhost:8080
//   STAFF_EMAIL     default admin@demo.local
//   STAFF_PASSWORD  default demo-password-123
//   CUSTOMER_PASS   default customer-pass-123
//   PEAK            peak virtual users, default 150
//   STAFF_SESSIONS  staff sessions to sign in, default 40
//   CUSTOMER_SESSIONS customer sessions, default 30 (spread over the demo customers)
//   SMOKE           set to 1 for a 30-second trial with 4 users, to check the script itself
//   THINK           pause multiplier between screens, default 1 (1–3 s). 0 = stress: no pause
//
// WHAT IT DOES
// Two kinds of user at once, in the proportions a normal day would have them:
//   - staff (70%)    browsing the back office: dashboard, items, stock, orders, customers, tree
//   - customers (30%) opening the portal: their account, referrals, announcements
// Each virtual user pauses 1–3 s between clicks, like a person reading a page. Load ramps up in
// steps so the report shows where response times start to climb.
//
// SESSIONS
// setup() signs in a pool of sessions up front — STAFF_SESSIONS for the staff account, and
// CUSTOMER_SESSIONS spread across the customer accounts — and each virtual user keeps one.
//
// Why not sign in per virtual user: sign-in hashes the password with Argon2 (about half a second,
// on purpose), so a test that logs everyone in measures password hashing, not the app.
//
// Why not one shared session: sessions are rows in the database, and every request touches its
// session's row. Hundreds of virtual users on one session all queue on one row lock — the first
// stress run here did exactly that and reported a ceiling that was the test's, not the app's.
// Real users each have their own session, so the pool is what resembles production.
//
// READ-ONLY by design: nothing here creates, edits or deletes data, so it is safe to run against a
// database you care about.

import http from 'k6/http';
import { check, group, sleep } from 'k6';
import { Trend } from 'k6/metrics';

const BASE = __ENV.BASE_URL || 'http://localhost:8080';
const API = `${BASE}/api/v1`;
const PEAK = Number(__ENV.PEAK || 150);
const STAFF_SESSIONS = Number(__ENV.STAFF_SESSIONS || 40);
const CUSTOMER_SESSIONS = Number(__ENV.CUSTOMER_SESSIONS || 30);

const CUSTOMERS = [
  'nimal.perera', 'kamala.fernando', 'ruwan.jayasinghe', 'ishani.de.silva', 'chaminda.bandara',
  'asanka.rathnayake', 'sanduni.herath', 'sunethra.silva', 'mahesh.kumara', 'thilini.jayawardena',
].map((name) => `${name}@customer.lk`);

const ENDPOINTS = [
  'stock summary', 'reorder alerts', 'set availability', 'notifications', 'items page',
  'stock for page', 'categories', 'items search', 'items page 2', 'stock by item',
  'purchase orders', 'suppliers', 'awaiting storing', 'goods receipts', 'customers page',
  'customer counts', 'referral tree', 'portal me', 'announcements', 'portal referrals',
];

// Ramp in steps: each plateau shows how the app behaves at that level before the next.
const stages = __ENV.SMOKE
  ? [
      { duration: '10s', target: 4 },
      { duration: '20s', target: 4 },
    ]
  : [
  { duration: '30s', target: Math.round(PEAK * 0.15) },
  { duration: '60s', target: Math.round(PEAK * 0.15) },
  { duration: '30s', target: Math.round(PEAK * 0.5) },
  { duration: '60s', target: Math.round(PEAK * 0.5) },
  { duration: '30s', target: PEAK },
  { duration: '60s', target: PEAK },
  { duration: '20s', target: 0 },
    ];
const share = (fraction) =>
  stages.map((stage) => ({ ...stage, target: Math.max(stage.target ? 1 : 0, Math.round(stage.target * fraction)) }));

export const options = {
  scenarios: {
    staff: { executor: 'ramping-vus', exec: 'staff', startVUs: 0, stages: share(0.7) },
    customers: { executor: 'ramping-vus', exec: 'customer', startVUs: 0, stages: share(0.3) },
  },
  thresholds: {
    // The pass mark, for page requests only: 95% under half a second, 99% under a second.
    // Sign-ins are excluded on purpose — passwords are hashed with Argon2, which is slow by
    // design (about half a second) so that a stolen hash is slow to crack.
    'http_req_duration{kind:page}': ['p(95)<500', 'p(99)<1000'],
    http_req_failed: ['rate<0.01'],
    checks: ['rate>0.99'],
    // One entry per endpoint. k6 only reports a tagged sub-metric when something refers to it,
    // so these are what make the per-endpoint table possible; the limit itself is generous.
    ...Object.fromEntries(ENDPOINTS.map((name) => [`http_req_duration{name:${name}}`, ['p(95)<1500']])),
  },
  // The summary lists these per endpoint, so a slow one is named rather than averaged away.
  summaryTrendStats: ['avg', 'med', 'p(90)', 'p(95)', 'p(99)', 'max'],
  // Signing in the session pool takes about half a second per session.
  setupTimeout: '3m',
};

const pageTime = new Trend('page_load_time', true);

function signIn(identifier, password) {
  const res = http.post(`${API}/auth/login`, JSON.stringify({ identifier, password }), {
    headers: { 'Content-Type': 'application/json' },
    tags: { name: 'login' },
  });
  const cookie = res.cookies.MLMSESSION && res.cookies.MLMSESSION[0];
  if (res.status !== 200 || !cookie) {
    throw new Error(`sign-in failed for ${identifier}: ${res.status} ${res.body}`);
  }
  if (JSON.parse(res.body).mfaRequired) {
    throw new Error(`${identifier} needs a two-factor code; use an Admin or Super Admin account`);
  }
  // Forget this session before the next sign-in. Login deliberately ends any session the client
  // already holds (a defence against session fixation), so carrying one account's cookie into the
  // next account's login would sign the first one out — which is exactly what the first version
  // of this script did, and why nine sessions in ten came back 401.
  http.cookieJar().clear(BASE);
  return `MLMSESSION=${cookie.value}`;
}

export function setup() {
  const staffCookies = Array.from({ length: STAFF_SESSIONS }, () =>
    signIn(__ENV.STAFF_EMAIL || 'admin@demo.local', __ENV.STAFF_PASSWORD || 'demo-password-123'),
  );
  const customerCookies = Array.from({ length: CUSTOMER_SESSIONS }, (_, index) =>
    signIn(CUSTOMERS[index % CUSTOMERS.length], __ENV.CUSTOMER_PASS || 'customer-pass-123'),
  );
  return { staffCookies, customerCookies };
}

/** Each virtual user keeps the same session for the whole run, as a person would. */
const sessionFor = (cookies) => cookies[(__VU - 1) % cookies.length];

function get(path, cookie, name) {
  // The session goes in the header, never in the VU's cookie jar, so each request is exactly the
  // account the scenario meant and nothing lingers between iterations.
  const res = http.get(`${API}${path}`, {
    headers: { Cookie: cookie },
    tags: { name, kind: 'page' },
    jar: null,
  });
  const ok = check(res, { [`${name} is 200`]: (r) => r.status === 200 });
  if (!ok && __ENV.DEBUG) console.log(`${name} -> ${res.status} ${String(res.body).slice(0, 140)}`);
  return res;
}

/** One visit to a screen: the requests that screen makes, then a pause to read it. */
function screen(name, requests) {
  group(name, () => {
    const started = Date.now();
    requests();
    pageTime.add(Date.now() - started, { screen: name });
  });
  // THINK scales the pause: 1 is a person reading (1–3 s), 0 is a stress test with no pause.
  const think = Number(__ENV.THINK ?? 1);
  if (think > 0) sleep((1 + Math.random() * 2) * think);
}

const SEARCHES = ['chair', 'table', 'sofa', 'bed', 'oak', 'teak', 'shelf'];

export function staff(data) {
  const c = sessionFor(data.staffCookies);
  const pick = (list) => list[Math.floor(Math.random() * list.length)];

  screen('dashboard', () => {
    get('/stock/summary', c, 'stock summary');
    get('/stock/reorder-alerts?openOnly=true', c, 'reorder alerts');
    get('/item-sets/availability', c, 'set availability');
    get('/notifications', c, 'notifications');
  });

  screen('items', () => {
    const page = get('/items?includeInactive=false&limit=20', c, 'items page');
    const ids = (page.json('data') || []).map((item) => `itemId=${item.id}`).join('&');
    if (ids) get(`/stock?${ids}`, c, 'stock for page');
    get('/categories', c, 'categories');
  });

  screen('items search', () => {
    get(`/items?includeInactive=false&limit=20&search=${pick(SEARCHES)}`, c, 'items search');
  });

  screen('items next page', () => {
    const first = get('/items?includeInactive=false&limit=20', c, 'items page');
    const next = first.json('nextCursor');
    if (next) get(`/items?includeInactive=false&limit=20&cursor=${next}`, c, 'items page 2');
  });

  screen('stock', () => {
    get('/stock/by-item?limit=20', c, 'stock by item');
    get('/stock/reorder-alerts?openOnly=true', c, 'reorder alerts');
  });

  screen('purchase orders', () => {
    get('/purchase-orders?limit=20', c, 'purchase orders');
    get('/suppliers', c, 'suppliers');
  });

  screen('received orders', () => {
    get('/purchase-orders/awaiting-storing', c, 'awaiting storing');
    get('/goods-receipts?limit=20', c, 'goods receipts');
  });

  screen('customers', () => {
    get('/admin/distributors?includeApplicants=true&limit=20', c, 'customers page');
    get('/admin/distributors/counts?includeApplicants=true', c, 'customer counts');
  });

  screen('hierarchy', () => {
    get('/distributors/tree', c, 'referral tree');
  });
}

export function customer(data) {
  const c = sessionFor(data.customerCookies);

  screen('portal home', () => {
    get('/portal/me', c, 'portal me');
    get('/announcements', c, 'announcements');
    get('/notifications', c, 'notifications');
  });

  screen('portal referrals', () => {
    get('/portal/me', c, 'portal me');
    get('/portal/referrals', c, 'portal referrals');
  });
}

export function handleSummary(data) {
  return {
    '/scripts/results/summary.json': JSON.stringify(data, null, 2),
    stdout: textSummary(data),
  };
}

// A compact per-endpoint table, slowest first — the part worth reading.
function textSummary(data) {
  const rows = [];
  for (const [key, metric] of Object.entries(data.metrics)) {
    const match = key.match(/^http_req_duration\{name:(.+)\}$/);
    if (match && metric.values) rows.push([match[1], metric.values]);
  }
  rows.sort((a, b) => b[1]['p(95)'] - a[1]['p(95)']);
  const fmt = (n) => (n == null ? '-' : `${n.toFixed(0)}ms`.padStart(8));
  const lines = [
    '',
    'Per endpoint (slowest p95 first)',
    `${'endpoint'.padEnd(22)}${'median'.padStart(8)}${'p95'.padStart(8)}${'p99'.padStart(8)}${'max'.padStart(8)}`,
    ...rows.map(([name, v]) => `${name.padEnd(22)}${fmt(v.med)}${fmt(v['p(95)'])}${fmt(v['p(99)'])}${fmt(v.max)}`),
  ];
  const m = data.metrics;
  const page = m['http_req_duration{kind:page}'].values;
  lines.push(
    '',
    `requests: ${m.http_reqs.values.count}  (${m.http_reqs.values.rate.toFixed(1)}/s)`,
    `failed:   ${(m.http_req_failed.values.rate * 100).toFixed(2)}%`,
    `pages:    median ${fmt(page.med).trim()}, p95 ${fmt(page['p(95)']).trim()}, p99 ${fmt(page['p(99)']).trim()}  (sign-ins excluded)`,
    `peak VUs: ${m.vus_max.values.max}`,
    '',
    'Thresholds:',
    ...Object.entries(data.metrics)
      .filter(([, metric]) => metric.thresholds)
      .flatMap(([name, metric]) =>
        Object.entries(metric.thresholds).map(([rule, r]) => `  ${r.ok ? 'PASS' : 'FAIL'}  ${name} ${rule}`),
      ),
    '',
  );
  return lines.join('\n');
}
