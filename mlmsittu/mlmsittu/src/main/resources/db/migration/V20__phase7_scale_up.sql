-- Phase 7: the infrastructure that lets more than one instance run at once.
--
-- The plan specifies Redis for sessions and rate limiting. This uses PostgreSQL instead, for the
-- same reason the project runs Postgres natively rather than in Docker and uses JdbcTemplate
-- rather than jOOQ: the *observable* requirements of Gate 7 are "sessions survive a restart",
-- "a cookie from instance A is accepted by instance B", and "rate limits hold across instances".
-- A shared database satisfies all three with no new service to install, run or monitor. Swapping
-- to Redis later is a dependency plus two properties, because nothing above these tables knows
-- which store is underneath.

-- ---------------------------------------------------------------- sessions (P7-01, P7-02)
--
-- Spring Session's own schema, verbatim from spring-session-jdbc. Reproduced here rather than
-- letting Spring create it, because schema in this project is owned by Flyway and nothing else —
-- an application that creates its own tables at startup is one that behaves differently on a
-- database it cannot alter.

CREATE TABLE spring_session (
    primary_id            CHAR(36) NOT NULL,
    session_id            CHAR(36) NOT NULL,
    creation_time         BIGINT   NOT NULL,
    last_access_time      BIGINT   NOT NULL,
    max_inactive_interval INT      NOT NULL,
    expiry_time           BIGINT   NOT NULL,
    principal_name        VARCHAR(100),
    CONSTRAINT spring_session_pk PRIMARY KEY (primary_id)
);

CREATE UNIQUE INDEX spring_session_ix1 ON spring_session (session_id);
CREATE INDEX spring_session_ix2 ON spring_session (expiry_time);
CREATE INDEX spring_session_ix3 ON spring_session (principal_name);

CREATE TABLE spring_session_attributes (
    session_primary_id CHAR(36)     NOT NULL,
    attribute_name     VARCHAR(200) NOT NULL,
    attribute_bytes    BYTEA        NOT NULL,
    CONSTRAINT spring_session_attributes_pk PRIMARY KEY (session_primary_id, attribute_name),
    CONSTRAINT spring_session_attributes_fk FOREIGN KEY (session_primary_id)
        REFERENCES spring_session (primary_id) ON DELETE CASCADE
);

-- ---------------------------------------------------------------- rate limiting (P7-05)

-- One row per attempt, swept by window rather than counted in a column.
--
-- A counter with a reset timestamp is cheaper and wrong at the edges: it turns a sliding window
-- into a fixed one, and a fixed window lets somebody make 2× the limit by straddling the boundary.
-- Rows are small, the sweep is indexed, and login volume is nowhere near the point where this
-- costs anything.
CREATE TABLE rate_limit_attempt (
    id           BIGSERIAL PRIMARY KEY,
    limit_key    VARCHAR(255) NOT NULL,
    attempted_at TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX idx_rate_limit_key_time ON rate_limit_attempt (limit_key, attempted_at DESC);
CREATE INDEX idx_rate_limit_sweep ON rate_limit_attempt (attempted_at);

-- ---------------------------------------------------------------- outbox (P7-06)

-- Messages committed with the business change that caused them, dispatched afterwards.
--
-- Until now a purchase order emailed its supplier *inside* the transaction that marked it sent. A
-- database failure a statement later rolled the order back while the supplier kept the email; a
-- mail server failure rolled back an order that was otherwise fine. Both are fixed by writing a
-- row here instead — it commits atomically with the order, and a poller sends it afterwards.
CREATE TABLE outbox_message (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),

    -- What kind of message, so a future channel does not need a new table.
    channel       VARCHAR(24)  NOT NULL,
    recipient     VARCHAR(320) NOT NULL,
    subject       VARCHAR(255) NOT NULL,
    body          TEXT         NOT NULL,

    status        VARCHAR(16)  NOT NULL DEFAULT 'pending',
    attempts      INTEGER      NOT NULL DEFAULT 0,
    last_error    VARCHAR(500),

    -- Why the message exists, for tracing a delivery back to the thing that caused it.
    reference_type VARCHAR(48),
    reference_id   UUID,

    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    dispatched_at TIMESTAMPTZ,

    CONSTRAINT chk_outbox_channel CHECK (channel IN ('email')),
    CONSTRAINT chk_outbox_status  CHECK (status IN ('pending', 'sent', 'failed')),
    CONSTRAINT chk_outbox_sent    CHECK (status <> 'sent' OR dispatched_at IS NOT NULL)
);

-- The poller's query: pending work that is due, oldest first. Partial, because sent rows are the
-- overwhelming majority within a day and the poller never looks at them.
CREATE INDEX idx_outbox_due ON outbox_message (next_attempt_at)
    WHERE status = 'pending';

CREATE INDEX idx_outbox_reference ON outbox_message (reference_type, reference_id);

SELECT grant_app_privileges();
