-- ==============================================================================================
-- In-app notifications.
--
-- Distinct from outbox_message, which is an email delivery queue: it is addressed to an email
-- address, it retries, and it is finished once the message is sent. A notification is addressed to
-- a *user*, is never delivered anywhere, and lives until they have read it.
--
-- Some events produce both — "your registration was approved" is worth an email and worth a badge
-- on the bell. They are raised separately on purpose, because a customer with no email address
-- still gets the notification, and that is most of this client's customers.
-- ==============================================================================================

CREATE TABLE notification (
    id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),

    -- ON DELETE CASCADE: notifications are not a record of what happened, they are a person's
    -- unread list. The audit log is the record, and it outlives the account deliberately.
    user_id    UUID NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,

    -- What kind of thing happened, for grouping and for choosing an icon. Free text rather than a
    -- CHECK constraint: a new kind should not need a migration, and an unrecognised one renders
    -- with the default icon rather than failing.
    kind       VARCHAR(48)  NOT NULL,

    title      VARCHAR(200) NOT NULL,
    body       VARCHAR(1000),

    -- Where to go when it is clicked, as an app-relative path. Nullable: not every notification
    -- has somewhere useful to point.
    link       VARCHAR(200),

    read_at    TIMESTAMPTZ,
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- The list query: one person's notifications, newest first.
CREATE INDEX idx_notification_user ON notification (user_id, created_at DESC);

-- The badge query, which runs on every poll and must stay cheap. Partial, so the index holds only
-- unread rows — on an account with ten thousand read notifications it stays the size of the few
-- that are not.
CREATE INDEX idx_notification_unread ON notification (user_id) WHERE read_at IS NULL;

SELECT grant_app_privileges();
