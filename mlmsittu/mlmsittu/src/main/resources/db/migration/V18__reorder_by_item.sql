-- Reorder alerts belong to an item, not to an item in a place.
--
-- The old rule raised one alert per (item, location). That was defensible while there was one
-- store; the moment a second one exists it is actively wrong, because creating a store instantly
-- raises an alert for every tracked item in it — each sitting at zero, none of them actually short.
-- The client saw exactly that: a delivery split across two stores made the goods look like they
-- needed reordering again.
--
-- What matters is how much of an item the business holds. Where it sits is a separate question,
-- answered by the per-store breakdown on the stock screen.

-- Existing alerts are per-location and about to be judged by a different rule. Close them rather
-- than leaving them open against a threshold that no longer applies; the next scan re-raises
-- whatever is genuinely short, now measured across every store.
UPDATE reorder_alert
   SET status = 'cleared',
       cleared_at = now(),
       on_hand_at_clearance = on_hand_at_detection
 WHERE status = 'open';

DROP INDEX idx_reorder_alert_open;

-- An item-wide alert has no single location. Nullable rather than dropped: the historical rows
-- above genuinely were raised about one store, and rewriting history to pretend otherwise would
-- lose the only record of why they fired.
ALTER TABLE reorder_alert ALTER COLUMN location_id DROP NOT NULL;

COMMENT ON COLUMN reorder_alert.location_id IS
    'Null on alerts raised since V18 — the threshold is judged on the item total across every store. Older rows keep the store they were raised against.';
COMMENT ON COLUMN reorder_alert.on_hand_at_detection IS
    'Available, not on hand, and summed across all active stores. Reserved units are already promised to somebody and are not replenishment cover.';

-- One open alert per item, wherever the stock is.
CREATE UNIQUE INDEX idx_reorder_alert_open ON reorder_alert (item_id) WHERE status = 'open';

SELECT grant_app_privileges();
