# Distribution & Inventory Platform — Architecture and Developer Handoff

**Stack:** Java 21 · Spring Boot 3.3 · PostgreSQL 16 · React 19 PWA
**Document type:** Architecture specification (architect → development team)
**Version:** 2.0 — Spring Boot revision
**Date:** 6 August 2026
**Status:** For implementation

---

## 0. Scope Statement

### 0.1 In scope

| Area | Summary |
|---|---|
| Inventory management | Items, stock ledger, valuation, adjustments, multi-location |
| Procurement | Suppliers, purchase orders, partial goods receipt |
| Sales | Orders, reservation, invoicing, fulfilment |
| Payments | Bank-transfer slip upload, manual verification |
| Item Sets | Composite bundles, virtual composition |
| Customers | CRM, table view and treemap hierarchy view |
| Distributor onboarding | Self-signup, email verification, KYC document review |
| Referral attribution | Who referred whom; tracked, queryable, visualised |
| Administration | RBAC with separation of duties, audit logging |
| Reporting | Stock, sales, procurement, distributor activity |

### 0.2 Enrolment reward mechanics — a special part of the scope

**Enrollment-driven reward mechanics are a special part of this design.**

This includes state machines for unlocking stages/levels, reward eligibility calculated from the number of referrals down, gated product entitlements, and payment logic keyed to enrollment counts.
When a user registers into the system, that person needs to complet a 4stages. Each stage is completed when that user (parent) has one child user. (When another user registers using a parent referral ID, that parent user unlocks 1 stage. Same as parent should unlock 4 stages. Only 4 users can use 1 referral ID; that means only 4 child allow to one parent user.) If a user unlocks all 4 stages, that user is automatically eligible to unlock the bonus stage.

Developers should consider `referral_summary.` 
---

## 1. Architecture Overview

### 1.1 Style

**Modular monolith.** Gradle multi-module project, single deployable jar, strict package boundaries enforced by ArchUnit tests in CI.

Rationale: tens of thousands of registered users at low concurrency (single-digit percent daily active). Microservices would add distributed-transaction complexity for no throughput benefit — and your sharpest correctness risk (§4.2 stock contention) is precisely the thing that gets harder without a single transactional boundary.

### 1.2 Stack

| Layer | Choice | Notes |
|---|---|---|
| Runtime | Java 21 LTS | Virtual threads enabled |
| Framework | Spring Boot 3.3 | Web MVC, not WebFlux — see §1.4 |
| Persistence (general) | Spring Data JPA / Hibernate 6 | Most modules |
| Persistence (hierarchy) | **jOOQ** | `ltree` module only — see §2.2 |
| Migrations | Flyway | Expand/contract, forward-only |
| Security | Spring Security 6 | Argon2id, session-based |
| Session store | `spring-session-data-redis` | Horizontal scaling |
| Cache / queue | Redis 7 + Redisson | Distributed locks, rate limits |
| Jobs | `@Scheduled` + Spring Batch | Outbox dispatch, retention sweep |
| Object storage | AWS SDK v2 (S3-compatible) | MinIO or DO Spaces |
| API docs | springdoc-openapi 2.x | Generates the TS client in CI |
| Build | Gradle 8 (Kotlin DSL) | Multi-module |
| Frontend | React 19 · Vite · TypeScript | PWA via `vite-plugin-pwa` |
| UI | Tailwind + shadcn/ui | Components vendored into repo |
| Visualisation | D3 | Treemap, hierarchy |
| i18n | react-i18next | English first, SI/TA ready |

### 1.3 Module structure

```
platform/
├── shared-kernel/        audit, outbox, storage, BusinessId, config
├── identity/             users, sessions, RBAC
├── onboarding/           registration, KYC review
├── hierarchy/            referral graph — jOOQ + ltree
├── catalogue/            items, categories, item sets
├── inventory/            stock ledger, locations, reservation
├── commerce/             purchase orders, receipts, sales, payments
├── reporting/            read-model queries, exports
└── app/                  Spring Boot entry point, wiring
```

Modules depend on `shared-kernel` and on published service interfaces only. Enforce with ArchUnit:

```java
@AnalyzeClasses(packages = "lk.company.platform")
class ModuleBoundaryTest {
    @ArchTest
    static final ArchRule modules_do_not_touch_each_others_internals =
        SlicesRuleDefinition.slices()
            .matching("lk.company.platform.(*)..")
            .should().notDependOnEachOther()
            .ignoreDependency(alwaysTrue(),
                resideInAPackage("..shared_kernel.."));
}
```

Cross-module JPA associations are prohibited. Reference other aggregates by ID.

### 1.4 MVC, not WebFlux

Enable virtual threads and use blocking MVC:

```properties
spring.threads.virtual.enabled=true
```

WebFlux would force reactive drivers, break `@Transactional` semantics, and complicate the pessimistic locking in §4.5 — all for concurrency you don't need. Virtual threads give you the I/O scalability without the programming model change.

---

## 2. Identity, Business ID, and Hierarchy

### 2.1 Business ID

User-facing identifier. Printed on cards, quoted over the phone, typed into referral fields.

**Format:** `SLV-XXXXX-C` — fixed prefix, Crockford Base32 of a sequence value, Damm check character.

- Crockford Base32 excludes `I`, `L`, `O`, `U` — no 1/I or 0/O confusion
- Damm check character detects all single-character errors and all adjacent transpositions
- Validated client-side, so a mistyped parent ID never reaches the server
- Allocated at **approval**, not signup
- **Immutable for life**, never reissued

```java
// shared-kernel/src/main/java/.../BusinessId.java
public final class BusinessId {
    private static final char[] ALPHABET =
        "0123456789ABCDEFGHJKMNPQRSTVWXYZ".toCharArray();
    private static final Pattern SHAPE =
        Pattern.compile("^SLV-([0-9A-HJKMNP-TV-Z]{5})-([0-9A-HJKMNP-TV-Z])$");

    public static String encode(long seq) {
        var sb = new StringBuilder();
        long n = seq;
        for (int i = 0; i < 5; i++) { sb.insert(0, ALPHABET[(int)(n % 32)]); n /= 32; }
        String body = sb.toString();
        return "SLV-" + body + "-" + Damm.compute(body);
    }

    public static boolean isValid(String id) {
        if (id == null) return false;
        var m = SHAPE.matcher(id.toUpperCase(Locale.ROOT));
        return m.matches() && Damm.compute(m.group(1)) == m.group(2).charAt(0);
    }
}
```

Expose validation as a Jakarta constraint so it runs at the DTO boundary:

```java
@Target({FIELD, PARAMETER}) @Retention(RUNTIME)
@Constraint(validatedBy = BusinessIdValidator.class)
public @interface ValidBusinessId {
    String message() default "INVALID_BUSINESS_ID";
    Class<?>[] groups() default {};
    Class<? extends Payload>[] payload() default {};
}
```

Capacity: 32⁵ ≈ 33.5 million. Use the sequence, not a random source — uniqueness without collision retries.

**Do not use a materialized path as the public identifier.** A path like `1131` is mutable under tree edits, unbounded in length, leaks the full upline, is trivially enumerable, and has no error detection — a typo produces another *valid* ID and silently attaches the record to the wrong parent. The path is retained internally (§2.2) and still displayed in admin views.

### 2.2 Hierarchy — jOOQ, not Hibernate

```sql
CREATE EXTENSION IF NOT EXISTS ltree;

CREATE TABLE distributor (
  id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  business_id  VARCHAR(12) UNIQUE,
  user_id      UUID NOT NULL REFERENCES app_user(id),
  referred_by  UUID REFERENCES distributor(id),
  path         LTREE NOT NULL,
  status       VARCHAR(24) NOT NULL DEFAULT 'pending',
  approved_at  TIMESTAMPTZ,
  created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_dist_path_gist  ON distributor USING GIST (path);
CREATE INDEX idx_dist_path_btree ON distributor USING BTREE (path);
CREATE INDEX idx_dist_referred_by ON distributor (referred_by);
```

**Hibernate has no `ltree` type.** Rather than writing a custom `UserType` you'd own forever, isolate this module behind jOOQ. The hierarchy module is query-shaped and gains nothing from an ORM.

```java
@Repository
public class HierarchyRepository {
    private final DSLContext dsl;

    private static final Field<String> PATH =
        DSL.field("path", SQLDataType.VARCHAR);

    public List<DistributorNode> descendantsOf(String rootPath, int maxDepth) {
        return dsl.select(DISTRIBUTOR.ID, DISTRIBUTOR.BUSINESS_ID, PATH)
                  .from(DISTRIBUTOR)
                  .where(DSL.condition("path <@ {0}::ltree", rootPath))
                  .and(DSL.condition("nlevel(path) <= {0}",
                        DSL.inline(rootPath.split("\\.").length + maxDepth)))
                  .and(DISTRIBUTOR.STATUS.eq("active"))
                  .fetchInto(DistributorNode.class);
    }

    public List<DistributorNode> ancestorsOf(String path) {
        return dsl.select(DISTRIBUTOR.ID, DISTRIBUTOR.BUSINESS_ID, PATH)
                  .from(DISTRIBUTOR)
                  .where(DSL.condition("path @> {0}::ltree", path))
                  .orderBy(DSL.field("nlevel(path)"))
                  .fetchInto(DistributorNode.class);
    }
}
```

Register jOOQ's Postgres dialect and let it share the Spring-managed `DataSource` so `@Transactional` still applies.

**Width cap** — configurable, default 4, in `system_config`. Enforced in the service layer, not as a constraint, so admins can change it without migration:

```java
@Transactional
public void assertCapacity(UUID parentId) {
    int cap = config.getInt("referral.max_direct", 4);
    int current = hierarchyRepo.countDirect(parentId);
    if (current >= cap) throw new ConflictException("REFERRER_AT_CAPACITY");
}
```

### 2.3 Deletion and NIC re-registration

```sql
CREATE TABLE identity_document (
  id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id        UUID NOT NULL REFERENCES app_user(id),
  nic_hash       BYTEA NOT NULL,
  nic_encrypted  BYTEA NOT NULL,
  nic_last4      VARCHAR(4) NOT NULL,
  doc_object_key TEXT NOT NULL,
  deleted_at     TIMESTAMPTZ,
  created_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX idx_nic_hash_active
  ON identity_document (nic_hash) WHERE deleted_at IS NULL;
```

Partial unique index: one active record per NIC, unlimited history. Uniqueness checks hit the HMAC; plaintext is never queried.

```java
@Service
public class NicProtection {
    private final SecretKey pepper;   // from env / KMS — NOT the database
    private final AesGcmCipher cipher;

    public byte[] hash(String nic) {
        var mac = Mac.getInstance("HmacSHA256");
        mac.init(pepper);
        return mac.doFinal(nic.trim().toUpperCase().getBytes(UTF_8));
    }
}
```

The pepper must live outside the database. Sri Lankan NIC format is small enough that an unpeppered hash is brute-forceable from a stolen dump alone.

Deletion is **soft**. Financial records are retained for statutory audit. The Business ID is retired permanently.

---

## 3. Onboarding and KYC

### 3.1 Two phases

**Phase 1 — Account.** Name, email, mobile, password. Both channels verified before login.
**Phase 2 — Business registration.** NIC number, NIC image, bank slip, address, bank details, item set, referrer Business ID. Enters the review queue.

### 3.2 Verification

**Amended 28 Aug 2026: mobile verification by SMS is out of scope.** The client chose not to take
on an SMS provider, so a mobile number is collected as contact detail and is not verified. The row
below is kept struck through rather than deleted, because "we decided against it" is a more useful
record than silence for anyone who wonders later why mobile is unverified.

Terminology for the team: **TOTP** (RFC 6238) is authenticator-app based and used for admin 2FA.

| Channel | Mechanism | TTL | Attempts |
|---|---|---|---|
| Email | Signed token link | 24 h | — |
| ~~Mobile~~ | ~~6-digit SMS OTP~~ | — | **out of scope** |
| Admin 2FA | TOTP | 30 s window | 5 |

Login rate limits are shared across instances (P7-05) and backed by PostgreSQL rather than
Redisson — see the §13 note on infrastructure substitutions.

### 3.3 Review workflow

```
draft → submitted → under_review → { approved | rejected | resubmit_required }
                          ↑                                    │
                          └────────────────────────────────────┘
```

Transitions recorded in append-only `registration_event` with reviewer, timestamp, reason.

Reviewer UI requirements:
- Side-by-side document viewer, zoom and rotate
- Structured rejection reasons (enum) plus free text
- Submitter cannot review their own registration
- Claim-based locking — one reviewer per record
- Every document view logged (PDPA)

Approval, single transaction:

```java
@Transactional
public void approve(UUID registrationId, UUID reviewerId) {
    var reg = repo.findForUpdate(registrationId)
        .orElseThrow(() -> new NotFoundException("REGISTRATION_NOT_FOUND"));

    if (reg.getSubmittedBy().equals(reviewerId))
        throw new ForbiddenException("SELF_REVIEW_FORBIDDEN");

    String businessId = BusinessId.encode(sequences.nextBusinessId());
    String parentPath = hierarchyRepo.pathOf(reg.getReferrerId());

    reg.approve(reviewerId, businessId,
                parentPath + "." + sequences.nextNodeSegment());

    outbox.publish(new DistributorApproved(reg.getId(), businessId));
}
```

---

## 4. Domain Model

### 4.1 Catalogue and inventory

```sql
CREATE TABLE item (
  id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  sku           VARCHAR(64) UNIQUE NOT NULL,
  name          VARCHAR(255) NOT NULL,
  category_id   UUID REFERENCES category(id),
  unit_cost     NUMERIC(14,2) NOT NULL,
  selling_price NUMERIC(14,2) NOT NULL,
  reorder_level INTEGER NOT NULL DEFAULT 0,
  is_active     BOOLEAN NOT NULL DEFAULT true
);

CREATE TABLE stock_level (
  item_id     UUID NOT NULL REFERENCES item(id),
  location_id UUID NOT NULL REFERENCES location(id),
  on_hand     INTEGER NOT NULL DEFAULT 0,
  reserved    INTEGER NOT NULL DEFAULT 0,
  version     BIGINT NOT NULL DEFAULT 0,
  PRIMARY KEY (item_id, location_id),
  CONSTRAINT chk_non_negative CHECK (on_hand >= 0 AND reserved >= 0)
);

CREATE TABLE stock_movement (
  id             BIGSERIAL PRIMARY KEY,
  item_id        UUID NOT NULL REFERENCES item(id),
  location_id    UUID NOT NULL REFERENCES location(id),
  qty_delta      INTEGER NOT NULL,
  movement_type  VARCHAR(24) NOT NULL,
  reference_type VARCHAR(32),
  reference_id   UUID,
  created_by     UUID NOT NULL,
  created_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);
```

`unit_cost` is admin-maintained (per decision). No batch/expiry tracking — durable goods. Multi-location schema from day one with a single seeded default; retrofitting location into `stock_level` post-launch would rewrite every stock query.

`stock_movement` is the append-only ledger. `stock_level` is a projection, reconcilable by replay. **Never write `stock_level` outside `StockLedgerService`** — enforce with an ArchUnit rule.

### 4.2 Item Sets — virtual composition

A set does not consume component stock on creation. Components decrement on release.

```sql
CREATE TABLE item_set (
  id        UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  code      VARCHAR(64) UNIQUE NOT NULL,
  name      VARCHAR(255) NOT NULL,
  set_price NUMERIC(14,2) NOT NULL,
  is_active BOOLEAN NOT NULL DEFAULT true
);

CREATE TABLE item_set_line (
  set_id   UUID NOT NULL REFERENCES item_set(id) ON DELETE CASCADE,
  item_id  UUID NOT NULL REFERENCES item(id),
  quantity INTEGER NOT NULL CHECK (quantity > 0),
  PRIMARY KEY (set_id, item_id)
);
```

Items may belong to multiple sets (per decision). Consequences:

- **Availability is computed, not stored:** `min(floor(available(item) / qty))` across lines
- **Overlapping sets compete.** Two sets sharing an item can each report available while only one can ship. Availability is advisory; only reservation (§4.5) is authoritative
- Set price is independent of component sum — surface the delta as a margin indicator in admin UI

### 4.3 Procurement and sales

```
Supplier → PurchaseOrder → GoodsReceipt → stock_movement(+)
Customer → SalesOrder → Reservation → Payment → Fulfilment → stock_movement(−)
```

`goods_receipt` supports partial receipt against a PO line.

### 4.4 Referral attribution

```sql
CREATE VIEW referral_summary AS
SELECT d.id, d.business_id, d.referred_by,
       (SELECT count(*) FROM distributor c
         WHERE c.referred_by = d.id AND c.status = 'active') AS direct_count
FROM distributor d;
```

Read-only. Attribution and reporting **only**.

> **Amendment, 8 Aug 2026.** An earlier revision said "no entitlement logic derives from
> `direct_count`". That exclusion was recorded in error and is withdrawn — the §0.2 reward
> mechanics are in scope.
>
> The view nevertheless remains unsuitable as the basis for entitlement, for a technical reason
> rather than a scope one. `direct_count` is computed per read. Deriving a stage unlock from it
> means *read the count, decide, then write* — and two referral approvals for the same parent
> committing concurrently will both read `3`, both conclude they are the fourth, and both award
> the stage. The same race lets the width cap of 4 admit a fifth child.
>
> Stage state must therefore be **stored and transactionally guarded**, not recomputed: take a
> row lock on the parent distributor before counting and inserting, and back the cap with a
> database constraint rather than a service-layer check alone. This is the §4.5 lesson applied to
> the referral graph — a check-then-act on unlocked rows is not a check.

### 4.5 Stock reservation — the concurrency-critical path

```java
@Repository
public interface StockLevelRepository extends JpaRepository<StockLevel, StockLevelId> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "3000"))
    @Query("select s from StockLevel s where s.itemId = :itemId and s.locationId = :locId")
    Optional<StockLevel> lockForUpdate(@Param("itemId") UUID itemId,
                                       @Param("locId") UUID locId);
}
```

```java
@Service
@RequiredArgsConstructor
public class ReservationService {

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void reserve(UUID orderId, List<OrderLine> lines, UUID locationId) {

        // Expand sets into components, then aggregate duplicates
        Map<UUID, Integer> required = expandToComponents(lines);

        // Deterministic lock ordering — MANDATORY, prevents deadlock
        List<UUID> ordered = required.keySet().stream().sorted().toList();

        for (UUID itemId : ordered) {
            int need = required.get(itemId);
            var stock = stockRepo.lockForUpdate(itemId, locationId)
                .orElseThrow(() -> new NotFoundException("STOCK_ROW_MISSING"));

            if (stock.available() < need)
                throw new InsufficientStockException(itemId, need, stock.available());

            stock.reserve(need);
        }
        orderRepo.markConfirmed(orderId);
    }
}
```

**Lock ordering is not optional.** Two concurrent orders sharing components in opposite sequence will deadlock under load. Sorting by `itemId` before locking makes it impossible. This is the single most important paragraph in the document.

---

## 5. Payments

Bank transfer with slip upload and manual verification. No gateway, no wallet, no automated disbursement.

```sql
CREATE TABLE payment (
  id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  sales_order_id   UUID REFERENCES sales_order(id),
  amount           NUMERIC(14,2) NOT NULL,
  bank_ref         VARCHAR(128),
  slip_object_key  TEXT NOT NULL,
  status           VARCHAR(24) NOT NULL DEFAULT 'pending',
  verified_by      UUID,
  verified_at      TIMESTAMPTZ,
  rejection_reason TEXT
);

CREATE UNIQUE INDEX idx_payment_bank_ref
  ON payment (bank_ref) WHERE bank_ref IS NOT NULL;
```

The unique index blocks the dominant fraud in slip-based systems: one genuine slip submitted against multiple orders. Verifiers type the reference from the image; the database rejects reuse.

Catch it cleanly:

```java
try {
    paymentRepo.saveAndFlush(payment);
} catch (DataIntegrityViolationException e) {
    if (e.getMostSpecificCause().getMessage().contains("idx_payment_bank_ref"))
        throw new ConflictException("DUPLICATE_BANK_REFERENCE");
    throw e;
}
```

Verification is a separate role from order entry (§8.1).

---

## 6. Frontend

### 6.1 Deployment split

Vite builds to static files served by Cloudflare Pages. Spring serves `/api/**`. Independent deploys — a UI fix shouldn't require a backend restart given Spring's ~10 s startup.

**Recommended:** route both through one Cloudflare hostname (`/api/*` → Spring, everything else → static). Same origin, `SameSite=Lax`, no CORS configuration at all.

If you do use separate origins, session cookies need:

```properties
server.servlet.session.cookie.same-site=none
server.servlet.session.cookie.secure=true
server.servlet.session.cookie.http-only=true
```

plus `setAllowCredentials(true)` in `CorsConfigurationSource` and `credentials: 'include'` in the frontend fetch wrapper. `SameSite=None` requires `Secure`, so HTTPS is mandatory in local dev — use `mkcert`.

### 6.2 Type safety across the language boundary

springdoc-openapi emits the spec; generate the TypeScript client in CI:

```bash
./gradlew generateOpenApiDocs
npx openapi-typescript build/openapi.json -o src/api/schema.d.ts
```

Fail the build on drift. This recovers what you'd otherwise lose crossing from Java to TypeScript.

### 6.3 PWA

Service worker caches the app shell and reference data. **Never cache stock levels, payment status, or KYC records** — stale inventory causes overselling. Mutations are online-only.

### 6.4 Visualisations

**Customer treemap.** D3 `treemap`, category → customer → order value. Area encodes value, colour encodes recency. Include a table toggle; treemaps are poor for precise comparison and unusable on small screens. Table is default under 768 px.

**Referral hierarchy.** D3 `tree`, lazy-loaded one level at a time via the jOOQ `path <@` query. Render depth 2, expand on demand. Do not fetch the full tree.

**Progress indicators.** Where sales milestones are displayed, drive them from sales volume against target. Four-band scale (`#e2e8f0` locked / `#fbbf24` in progress / `#34d399` complete / `#3b82f6` exceeded), with icon and label redundancy for accessibility.

### 6.5 Accessibility and i18n

WCAG 2.1 AA; full keyboard navigation in the review queue, your highest-volume admin surface. react-i18next with namespaced keys, English bundle only at v1, ESLint rule banning hardcoded user-facing strings. Sinhala needs Noto Sans Sinhala and ~30% width overhead reserved in layouts.

---

## 7. Security and Data Protection

### 7.1 PDPA obligations

| Obligation | Implementation |
|---|---|
| Encryption at rest | AES-256-GCM for NIC; SSE on object storage; encrypted DB volume |
| Access logging | Every KYC document view — actor, subject, timestamp, IP |
| Retention | Spring Batch `retention-sweep` job purges after statutory period |
| Subject access | Export endpoint producing all personal data for a user |
| Erasure | Soft delete + document purge; financial records retained with justification |
| Breach notification | Runbook §10.4 |

### 7.2 Document storage

Never serve KYC images from a public bucket. Flow: authorise → log access → presigned URL, 60 s TTL, single use. Bucket policy denies all public access.

Validate uploads by magic bytes (Apache Tika), not extension. Strip EXIF. Max 10 MB. Re-encode server-side to neutralise embedded payloads.

### 7.3 Application security

```java
@Bean
PasswordEncoder passwordEncoder() {
    return new Argon2PasswordEncoder(16, 32, 1, 1 << 16, 3);
}
```

- Session cookies: `HttpOnly`, `Secure`, `SameSite`, rotated on privilege change
- Rate limiting per IP and per account on all auth endpoints (Redisson)
- Parameterised queries only — JPA/jOOQ, never string concatenation
- CSP without `unsafe-inline`
- OWASP Dependency-Check in CI

---

## 8. Administration

### 8.1 RBAC with separation of duties

| Role | Permissions |
|---|---|
| `super_admin` | All; cannot self-approve KYC |
| `kyc_reviewer` | Review queue, document view, approve/reject |
| `inventory_clerk` | Items, stock, goods receipt |
| `procurement_officer` | Suppliers, purchase orders |
| `finance_officer` | Payment verification, financial reports |
| `support_agent` | Read-only customer and distributor data |

```java
@PreAuthorize("hasRole('KYC_REVIEWER')")
@PostMapping("/admin/registrations/{id}/approve")
public ResponseEntity<Void> approve(@PathVariable UUID id,
                                    @AuthenticationPrincipal AppUser actor) { ... }
```

Enforced constraints: submitter cannot review their own registration; order recorder cannot verify their own payment; role changes require `super_admin` and are audit-logged. All checks server-side — client-side gating is UX only.

### 8.2 Audit log

```sql
CREATE TABLE audit_log (
  id          BIGSERIAL PRIMARY KEY,
  actor_id    UUID,
  action      VARCHAR(64) NOT NULL,
  entity_type VARCHAR(64) NOT NULL,
  entity_id   UUID,
  before      JSONB,
  after       JSONB,
  ip          INET,
  created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
```

Append-only — revoke `UPDATE` and `DELETE` from the application role. Monthly partitions on `created_at`. Populate via a Hibernate `@EntityListener` or an AOP aspect on service methods; do not scatter audit calls through business logic.

### 8.3 Transactional outbox

```java
@Scheduled(fixedDelay = 2000)
@Transactional
public void dispatch() {
    var batch = dsl.select().from(OUTBOX)
        .where(OUTBOX.SENT_AT.isNull())
        .orderBy(OUTBOX.CREATED_AT)
        .limit(100)
        .forUpdate().skipLocked()       // multi-instance safe
        .fetchInto(OutboxRow.class);

    for (var row : batch) { handler.handle(row); markSent(row.id()); }
}
```

`SKIP LOCKED` lets multiple app instances poll without contention. Writing side effects as rows in the same transaction as the business change makes them atomic — sending mail inside a transaction means a rollback still delivers the message.

---

## 9. API Design

REST, versioned at `/api/v1`. Cursor pagination — offset pagination degrades and yields duplicates under concurrent inserts.

```
POST   /api/v1/auth/register
POST   /api/v1/auth/verify-email
POST   /api/v1/auth/verify-otp
POST   /api/v1/auth/login

POST   /api/v1/registrations
GET    /api/v1/registrations/{id}
POST   /api/v1/admin/registrations/{id}/claim
POST   /api/v1/admin/registrations/{id}/approve
POST   /api/v1/admin/registrations/{id}/reject

GET    /api/v1/items?cursor=&limit=
POST   /api/v1/items
GET    /api/v1/item-sets/{id}/availability
GET    /api/v1/stock?locationId=

POST   /api/v1/purchase-orders
POST   /api/v1/goods-receipts
POST   /api/v1/sales-orders
POST   /api/v1/payments
POST   /api/v1/admin/payments/{id}/verify

GET    /api/v1/distributors/{id}/referrals?depth=2
GET    /api/v1/admin/customers/treemap
```

Errors follow RFC 9457 via `ProblemDetail` (built into Spring 6):

```java
@ExceptionHandler(InsufficientStockException.class)
ProblemDetail handle(InsufficientStockException e) {
    var pd = ProblemDetail.forStatus(HttpStatus.CONFLICT);
    pd.setTitle("Insufficient stock");
    pd.setProperty("code", "INSUFFICIENT_STOCK");
    pd.setProperty("itemId", e.getItemId());
    pd.setProperty("shortfall", e.getShortfall());
    return pd;
}
```

The frontend matches on `code`, never on message text. Idempotency keys required on all POSTs creating financial records.

---

## 10. Deployment

### 10.1 Topology

Hetzner or DigitalOcean, Frankfurt or Singapore. AWS `ap-south-1` (Mumbai) has lower latency to Colombo at roughly 4× cost — not justified at this scale.

```
Cloudflare (DNS, TLS, WAF, CDN, static hosting)
        │
   Load balancer
        │
   App containers ×2  ──┬── Managed PostgreSQL (+ read replica)
   Worker profile ×1    ├── Redis
                        └── Object storage (KYC, slips)
```

Run the scheduler on a dedicated instance via a Spring profile so `@Scheduled` jobs don't multiply across replicas.

### 10.2 JVM tuning and cost

```dockerfile
FROM eclipse-temurin:21-jre-alpine
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75 -XX:+UseZGC -XX:+ZGenerational"
```

| Component | Spec | Monthly |
|---|---|---|
| App servers ×2 | 4 vCPU / 8 GB | $48 |
| Managed Postgres | 2 vCPU / 4 GB + replica | $60 |
| Redis | 1 GB | $15 |
| Object storage | 250 GB + egress | $12 |
| Load balancer | — | $12 |
| Backups | — | $10 |
| **Total** | | **≈ $157** |

Spring uses ~600–850 MB RSS per instance versus ~200 MB for Node. Irrelevant at 8 GB; it does rule out a $12 single-droplet start. A lean single-server v1 runs ~$75/month.

Use `jlink` for a trimmed runtime (~180 MB image) if CI pull times bother you. GraalVM native-image is not worth the build complexity at this scale.

### 10.3 Pipeline

GitHub Actions → Spotless, ArchUnit, unit tests, integration tests (Testcontainers with real Postgres) → OpenAPI generation + TS client drift check → build image → staging → smoke tests → manual gate → production.

Blue-green with automatic rollback. Set `readinessProbe.initialDelaySeconds: 20` — Spring's 8–15 s startup means health checks fail if you probe too early.

Flyway migrations run as a separate step before deploy. Forward-only, backward-compatible for one release (expand/contract).

### 10.4 Operations

- **Backups:** nightly full, PITR with 7-day WAL, monthly restore drill — an untested backup is not a backup
- **Monitoring:** Micrometer → Prometheus → Grafana. Alert on error rate, p95 latency, queue depth, **KYC queue age**, disk
- **Logging:** Logback JSON encoder; PII redacted at the logger, never at the sink
- **Breach runbook:** contain → scope from `audit_log` → notify per PDPA timelines → post-mortem

---

## 11. Delivery Plan

| Phase | Duration | Deliverable |
|---|---|---|
| 1 | 3 wks | Foundation: Gradle modules, Spring Security, Flyway, audit, outbox, CI/CD, staging |
| 2 | 4 wks | Catalogue, stock ledger, locations, suppliers, goods receipt |
| 3 | 3 wks | Item sets + reservation with pessimistic locking |
| 4 | 4 wks | Onboarding, KYC upload, review queue |
| 5 | 3 wks | Sales orders, payments, slip verification |
| 6 | 3 wks | Reporting, treemap, referral hierarchy (jOOQ) |
| 7 | 2 wks | Hardening, load test, penetration test, UAT |

**≈ 22 weeks**, team of 3–4. Phases 2 and 4 parallelise across two streams.

---

## 12. Risk Register

| Risk | Impact | Mitigation |
|---|---|---|
| Reservation deadlock | Production outage | Deterministic lock ordering §4.5; concurrency test in CI |
| KYC queue backlog | Onboarding stalls | Queue-age alerting; ~40 reviews/reviewer/day capacity model |
| Duplicate bank slips | Financial loss | Unique `bank_ref` index; verifier training |
| Overlapping set contention | Overselling | Transactional reservation; availability marked advisory |
| PDPA non-compliance | Regulatory penalty | §7 controls; retention job; access logs |
| `ltree`/Hibernate friction | Rework | jOOQ isolation from day one §2.2 |
| Concurrent referral approval double-awards a stage, or admits a 5th child | Incorrect entitlements; cap breached | Row-lock the parent before count-and-insert; DB constraint on the cap; concurrency test in CI (see §4.4 amendment) |
| Sinhala retrofit | Rework | i18n scaffolding day one; no hardcoded strings |

---

## 13. Open Items

1. Supplier lead times and reorder policy — needed to finalise reorder automation
2. Invoice format and numbering — confirm statutory requirements with the company accountant
3. ~~SMS provider (Dialog / Mobitel / Text.lk)~~ — **closed 28 Aug 2026: no SMS.** Mobile
   verification is out of scope; the number is contact detail only.
4. Document retention period — confirm with counsel under PDPA
5. Whether distributor pricing differs from retail — affects `sales_order` price resolution

---

**End of document.**
