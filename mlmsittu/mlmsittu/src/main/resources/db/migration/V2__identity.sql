-- P1-01 · Identity schema: users, roles, and the grant table between them.
--
-- Named app_role rather than role: ROLE is a reserved word in the SQL standard and a
-- cluster-level concept in PostgreSQL. app_user / app_role keeps both readable and unambiguous.

CREATE TABLE app_user (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    email           VARCHAR(320) NOT NULL,
    mobile          VARCHAR(24),
    full_name       VARCHAR(255) NOT NULL,
    -- Argon2id encodings run ~100 characters; 255 leaves headroom without reaching for TEXT,
    -- which keeps Hibernate's validate-mode type check straightforward.
    password_hash   VARCHAR(255) NOT NULL,
    status          VARCHAR(24)  NOT NULL DEFAULT 'unverified',
    email_verified  BOOLEAN      NOT NULL DEFAULT false,
    mobile_verified BOOLEAN      NOT NULL DEFAULT false,

    -- TOTP (RFC 6238) for admin 2FA. Distinct from the SMS OTP used for mobile verification
    -- in Phase 4 — architecture §3.2 is explicit that these must not share an implementation.
    totp_secret     VARCHAR(64),
    totp_enabled    BOOLEAN      NOT NULL DEFAULT false,

    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),

    CONSTRAINT chk_app_user_status
        CHECK (status IN ('unverified', 'active', 'suspended', 'deleted'))
);

-- Case-insensitive uniqueness: Sittu@example.com and sittu@example.com are one account.
CREATE UNIQUE INDEX idx_app_user_email ON app_user (lower(email));

CREATE TABLE app_role (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    code         VARCHAR(48)  NOT NULL UNIQUE,
    description  VARCHAR(255) NOT NULL,

    -- Whether holders of this role must pass TOTP before a session is issued.
    -- All six administrative roles do; distributor accounts added in Phase 4 will not.
    requires_mfa BOOLEAN      NOT NULL DEFAULT true
);

CREATE TABLE user_role (
    user_id    UUID NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    role_id    UUID NOT NULL REFERENCES app_role(id),
    granted_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    granted_by UUID REFERENCES app_user(id),
    PRIMARY KEY (user_id, role_id)
);

CREATE INDEX idx_user_role_role ON user_role (role_id);

-- ---------------------------------------------------------------------------------------------
-- The six roles of architecture §8.1. Reference data, not seed data: production needs these too.
-- ---------------------------------------------------------------------------------------------

INSERT INTO app_role (code, description, requires_mfa) VALUES
    ('SUPER_ADMIN',         'Full access. Cannot self-approve KYC.',            true),
    ('KYC_REVIEWER',        'Review queue, document view, approve and reject.', true),
    ('INVENTORY_CLERK',     'Items, stock, goods receipt.',                     true),
    ('PROCUREMENT_OFFICER', 'Suppliers and purchase orders.',                   true),
    ('FINANCE_OFFICER',     'Payment verification and financial reports.',      true),
    ('SUPPORT_AGENT',       'Read-only customer and distributor data.',         true);

SELECT grant_app_privileges();
