-- Phase 4 · Onboarding, KYC and the referral hierarchy (architecture §2 and §3).

-- ---------------------------------------------------------------------------------------------
-- Runtime configuration. P4-04 requires the referral width cap to change without a restart, so it
-- lives here rather than in application.properties.
-- ---------------------------------------------------------------------------------------------

CREATE TABLE system_config (
    key         VARCHAR(64) PRIMARY KEY,
    value       VARCHAR(255) NOT NULL,
    description VARCHAR(500),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_by  UUID REFERENCES app_user(id)
);

INSERT INTO system_config (key, value, description) VALUES
    ('referral.max_direct', '4',
     'Maximum direct referrals one distributor may have. Architecture §2.2.');

-- ---------------------------------------------------------------------------------------------
-- The referral graph.
--
-- `path` is an ltree materialised path, used for subtree queries. It is internal: architecture
-- §2.1 is emphatic that it must never be the public identifier, because it is mutable under tree
-- edits, leaks the whole upline, is enumerable, and has no error detection.
-- ---------------------------------------------------------------------------------------------

CREATE TABLE distributor (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),

    -- Allocated at approval, never before, and never reissued.
    business_id  VARCHAR(12) UNIQUE,

    user_id      UUID NOT NULL REFERENCES app_user(id),
    referred_by  UUID REFERENCES distributor(id),

    -- Null until approval, when the parent's path plus a new segment is computed.
    path         LTREE,

    status       VARCHAR(24) NOT NULL DEFAULT 'pending',

    -- Denormalised child count, maintained under a row lock on this row.
    --
    -- The obvious alternative -- COUNT(*) at approval time -- is a check-then-act race: two
    -- approvals under one parent both read 3, both conclude they are the fourth, and the cap of 4
    -- admits a fifth child. Architecture §4.4 (amended) spells this out. There is deliberately no
    -- CHECK pinning it to 4, because the cap is configurable at runtime; the lock is what makes
    -- it correct, and the service reads the limit from system_config.
    direct_child_count INTEGER NOT NULL DEFAULT 0 CHECK (direct_child_count >= 0),

    approved_at  TIMESTAMPTZ,
    deleted_at   TIMESTAMPTZ,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT chk_distributor_status CHECK (status IN
        ('pending', 'active', 'suspended', 'deleted'))
);

-- GIST powers the containment operators (@> and <@) that subtree and ancestor queries use.
-- BTREE powers ordering and equality. Architecture §2.2 asks for both.
CREATE INDEX idx_dist_path_gist ON distributor USING GIST (path);
CREATE INDEX idx_dist_path_btree ON distributor USING BTREE (path);
CREATE INDEX idx_dist_referred_by ON distributor (referred_by);
CREATE INDEX idx_dist_user ON distributor (user_id);

-- One live distributor per user. A deleted one may re-register (F-06).
CREATE UNIQUE INDEX idx_dist_user_active ON distributor (user_id) WHERE deleted_at IS NULL;

-- Segments for the materialised path. A sequence rather than the row's own id because an ltree
-- label may only contain letters, digits and underscores — a UUID's hyphens are not valid.
CREATE SEQUENCE distributor_path_segment_seq START 1;
CREATE SEQUENCE business_id_seq START 1;

-- Attribution and reporting only. Architecture §4.4 (amended): entitlement logic must read stored,
-- transactionally guarded state, never this view — recomputing a count at decision time is the
-- race described above.
CREATE VIEW referral_summary AS
SELECT d.id,
       d.business_id,
       d.referred_by,
       d.path,
       (SELECT count(*) FROM distributor c
         WHERE c.referred_by = d.id AND c.status = 'active') AS direct_count
FROM distributor d;

-- ---------------------------------------------------------------------------------------------
-- Uploaded files. Stored privately; never served directly.
-- ---------------------------------------------------------------------------------------------

CREATE TABLE stored_document (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    object_key   TEXT NOT NULL UNIQUE,
    kind         VARCHAR(32) NOT NULL,
    content_type VARCHAR(128) NOT NULL,
    byte_size    BIGINT NOT NULL CHECK (byte_size > 0),

    -- Of the re-encoded bytes actually stored, not of the upload.
    sha256       VARCHAR(64) NOT NULL,

    uploaded_by  UUID NOT NULL REFERENCES app_user(id),
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT chk_document_kind CHECK (kind IN ('nic', 'bank_slip', 'other'))
);

-- ---------------------------------------------------------------------------------------------
-- National identity documents (architecture §2.3).
--
-- The plaintext NIC is never stored and never queried. Uniqueness is checked against an HMAC whose
-- pepper lives outside the database — the Sri Lankan NIC format is small enough that an unpeppered
-- hash is brute-forceable from a stolen dump alone.
-- ---------------------------------------------------------------------------------------------

CREATE TABLE identity_document (
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id        UUID NOT NULL REFERENCES app_user(id),
    nic_hash       BYTEA NOT NULL,
    nic_encrypted  BYTEA NOT NULL,
    nic_last4      VARCHAR(4) NOT NULL,
    document_id    UUID REFERENCES stored_document(id),
    deleted_at     TIMESTAMPTZ,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- One active record per NIC, unlimited history. This single index is what lets a deleted account
-- re-register with the same NIC without weakening uniqueness for live records (F-06).
CREATE UNIQUE INDEX idx_nic_hash_active ON identity_document (nic_hash) WHERE deleted_at IS NULL;
CREATE INDEX idx_nic_user ON identity_document (user_id);

-- ---------------------------------------------------------------------------------------------
-- Business registration and its review.
-- ---------------------------------------------------------------------------------------------

CREATE TABLE registration (
    id                      UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id                 UUID NOT NULL REFERENCES app_user(id),

    -- What the applicant typed, kept alongside the resolved row so a later dispute can show it.
    referrer_business_id    VARCHAR(12),
    referrer_distributor_id UUID REFERENCES distributor(id),

    status                  VARCHAR(24) NOT NULL DEFAULT 'draft',

    full_address            VARCHAR(500),
    bank_name               VARCHAR(255),
    bank_branch             VARCHAR(255),
    bank_account_number     VARCHAR(64),
    item_set_id             UUID REFERENCES item_set(id),

    nic_document_id         UUID REFERENCES stored_document(id),
    slip_document_id        UUID REFERENCES stored_document(id),
    identity_document_id    UUID REFERENCES identity_document(id),

    -- Claim-based locking: one reviewer per record (architecture §3.3).
    claimed_by              UUID REFERENCES app_user(id),
    claimed_at              TIMESTAMPTZ,

    submitted_at            TIMESTAMPTZ,
    reviewed_by             UUID REFERENCES app_user(id),
    reviewed_at             TIMESTAMPTZ,
    rejection_reason        VARCHAR(64),
    rejection_note          VARCHAR(1000),

    created_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at              TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT chk_registration_status CHECK (status IN
        ('draft', 'submitted', 'under_review', 'approved', 'rejected', 'resubmit_required'))
);

CREATE INDEX idx_registration_status ON registration (status, submitted_at);
CREATE INDEX idx_registration_user ON registration (user_id);

-- One live registration per user; a rejected or approved one does not block a new attempt.
CREATE UNIQUE INDEX idx_registration_open_per_user ON registration (user_id)
    WHERE status IN ('draft', 'submitted', 'under_review', 'resubmit_required');

-- Append-only transition log (architecture §3.3). Every state change, who made it and why.
CREATE TABLE registration_event (
    id              BIGSERIAL PRIMARY KEY,
    registration_id UUID NOT NULL REFERENCES registration(id) ON DELETE CASCADE,
    from_status     VARCHAR(24),
    to_status       VARCHAR(24) NOT NULL,
    actor_id        UUID REFERENCES app_user(id),
    reason          VARCHAR(64),
    note            VARCHAR(1000),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_registration_event_reg ON registration_event (registration_id, created_at);

-- ---------------------------------------------------------------------------------------------
-- PDPA access logging (architecture §7.1): every KYC document view — actor, subject, time, IP.
-- ---------------------------------------------------------------------------------------------

CREATE TABLE document_access_log (
    id           BIGSERIAL PRIMARY KEY,
    document_id  UUID NOT NULL REFERENCES stored_document(id),
    actor_id     UUID REFERENCES app_user(id),
    subject_user_id UUID REFERENCES app_user(id),
    action       VARCHAR(32) NOT NULL,
    ip           INET,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_doc_access_document ON document_access_log (document_id, created_at DESC);
CREATE INDEX idx_doc_access_actor ON document_access_log (actor_id, created_at DESC);

-- ---------------------------------------------------------------------------------------------
-- Channel verification (architecture §3.2). Email uses a signed-token link; mobile uses a stored
-- random OTP. Different mechanisms, different tables, no shared implementation.
-- ---------------------------------------------------------------------------------------------

CREATE TABLE email_verification_token (
    id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id    UUID NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    token_hash BYTEA NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    used_at    TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_email_token_hash ON email_verification_token (token_hash);

CREATE TABLE mobile_otp (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id      UUID NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,

    -- Hashed, and compared in constant time. A stored plaintext OTP is a password in a table.
    code_hash    BYTEA NOT NULL,

    expires_at   TIMESTAMPTZ NOT NULL,
    attempts     INTEGER NOT NULL DEFAULT 0,
    locked_until TIMESTAMPTZ,
    consumed_at  TIMESTAMPTZ,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_mobile_otp_user ON mobile_otp (user_id, created_at DESC);

-- ---------------------------------------------------------------------------------------------
-- Two more append-only tables.
--
-- Registered by extending the helper's array rather than with a one-off REVOKE here. A one-off
-- would be undone by the next migration that calls grant_app_privileges() — which is exactly the
-- regression V8 was written to fix, and repeating it two phases later would be worse than having
-- made it the first time.
-- ---------------------------------------------------------------------------------------------

CREATE OR REPLACE FUNCTION grant_app_privileges() RETURNS void AS $$
DECLARE
    append_only CONSTANT TEXT[] := ARRAY[
        'audit_log',
        'stock_movement',
        'registration_event',
        'document_access_log'
    ];
    target RECORD;
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'mlmsittu_app') THEN
        RAISE NOTICE 'Role mlmsittu_app does not exist - skipping grants.';
        RETURN;
    END IF;

    GRANT USAGE ON SCHEMA public TO mlmsittu_app;
    GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO mlmsittu_app;
    GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO mlmsittu_app;

    FOR target IN
        SELECT child.relname AS name
        FROM pg_class child
        JOIN pg_namespace ns ON ns.oid = child.relnamespace
        WHERE ns.nspname = 'public'
          AND child.relkind IN ('r', 'p')
          AND (
                child.relname = ANY (append_only)
                OR EXISTS (
                    SELECT 1
                    FROM pg_inherits inh
                    JOIN pg_class parent ON parent.oid = inh.inhparent
                    WHERE inh.inhrelid = child.oid
                      AND parent.relname = ANY (append_only)
                )
          )
    LOOP
        EXECUTE format(
            'REVOKE UPDATE, DELETE, TRUNCATE ON public.%I FROM mlmsittu_app', target.name);
    END LOOP;
END;
$$ LANGUAGE plpgsql;

SELECT grant_app_privileges();
