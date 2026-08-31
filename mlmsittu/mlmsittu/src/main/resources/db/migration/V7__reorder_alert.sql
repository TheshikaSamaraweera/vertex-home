-- P2-09 · Reorder detection.
--
-- An alert is a piece of state with a lifecycle, not a log line: it is raised when stock falls
-- below the reorder level and cleared when it recovers. Storing it means the procurement team
-- sees a list of things needing attention rather than a stream of repeated notifications.

CREATE TABLE reorder_alert (
    id                   UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    item_id              UUID NOT NULL REFERENCES item(id),
    location_id          UUID NOT NULL REFERENCES location(id),

    -- Captured at detection time. The item's reorder_level can be edited afterwards, and an
    -- alert should still say what the threshold was when it fired.
    reorder_level        INTEGER NOT NULL,
    on_hand_at_detection INTEGER NOT NULL,

    status               VARCHAR(24) NOT NULL DEFAULT 'open',
    raised_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    cleared_at           TIMESTAMPTZ,
    on_hand_at_clearance INTEGER,

    CONSTRAINT chk_reorder_alert_status CHECK (status IN ('open', 'cleared'))
);

-- At most one open alert per item and location. Without this, every scan while stock stays low
-- would raise another alert and the queue would become unreadable within a day.
CREATE UNIQUE INDEX idx_reorder_alert_open
    ON reorder_alert (item_id, location_id) WHERE status = 'open';

CREATE INDEX idx_reorder_alert_raised ON reorder_alert (raised_at DESC);

SELECT grant_app_privileges();
