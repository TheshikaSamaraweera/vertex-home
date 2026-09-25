-- ============================================================================================
-- V34: tracking an issued item pack until it is in the customer's hands.
--
-- Issuing a pack (V19) takes the stock out of a store. That is where the old record stopped, and
-- where the customer's questions start: how do I get it, where is it, when is it done. So an issued
-- pack now carries a tracking number and moves through four stages:
--
--   awaiting_method  issued; waiting for the customer to choose pickup or delivery
--   preparing        a method is chosen; the office is getting it ready
--   dispatched       ready at the warehouse (pickup) or out for delivery
--   completed        picked up or delivered — final. The business account closes here.
--
-- The receiving method is chosen once. After that only an administrator may change it, and only
-- by re-entering their password (enforced in the application; recorded in the audit log).
-- ============================================================================================

CREATE SEQUENCE reward_tracking_seq;

ALTER TABLE reward_entitlement
    ADD COLUMN tracking_number       VARCHAR(32) UNIQUE,
    ADD COLUMN tracking_stage        VARCHAR(24),
    ADD COLUMN receive_method        VARCHAR(16),
    ADD COLUMN pickup_location_id    UUID REFERENCES location(id),
    ADD COLUMN delivery_address      VARCHAR(500),
    ADD COLUMN delivery_contact      VARCHAR(32),
    ADD COLUMN receive_method_set_at TIMESTAMPTZ,
    ADD COLUMN receive_method_set_by UUID REFERENCES app_user(id),
    ADD COLUMN handover_location_id  UUID REFERENCES location(id),
    ADD COLUMN completed_at          TIMESTAMPTZ,
    ADD COLUMN completed_by          UUID REFERENCES app_user(id);

CREATE INDEX idx_reward_tracking_stage ON reward_entitlement (tracking_stage, issued_at DESC)
    WHERE tracking_stage IS NOT NULL;

-- Every stage a pack has been through, newest last: the customer's timeline and the office's
-- record of who moved it.
CREATE TABLE reward_tracking_event (
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    entitlement_id UUID         NOT NULL REFERENCES reward_entitlement(id),
    stage          VARCHAR(24)  NOT NULL,
    note           VARCHAR(500),
    actor_id       UUID REFERENCES app_user(id),
    at             TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX idx_reward_tracking_event ON reward_tracking_event (entitlement_id, at);

-- Packs already issued start at the first stage, with a number of their own.
UPDATE reward_entitlement
   SET tracking_number = 'PK-' || to_char(COALESCE(issued_at, now()), 'YYYY') || '-'
                         || lpad(nextval('reward_tracking_seq')::text, 6, '0'),
       tracking_stage  = 'awaiting_method'
 WHERE status = 'issued';

INSERT INTO reward_tracking_event (entitlement_id, stage, note, actor_id, at)
SELECT id, 'awaiting_method', 'Pack issued', issued_by, issued_at
  FROM reward_entitlement
 WHERE status = 'issued';

ALTER TABLE reward_entitlement
    ADD CONSTRAINT chk_reward_tracking_stage CHECK (
        tracking_stage IS NULL
        OR tracking_stage IN ('awaiting_method', 'preparing', 'dispatched', 'completed')),
    ADD CONSTRAINT chk_reward_receive_method CHECK (
        receive_method IS NULL OR receive_method IN ('pickup', 'delivery')),
    -- A method without the details it needs is not a method: nobody can act on "delivery" with no
    -- address, or on "pickup" with no warehouse.
    ADD CONSTRAINT chk_reward_receive_details CHECK (
        receive_method IS NULL
        OR (receive_method = 'pickup' AND pickup_location_id IS NOT NULL)
        OR (receive_method = 'delivery' AND delivery_address IS NOT NULL
            AND delivery_contact IS NOT NULL)),
    -- Only an issued pack is tracked, and it always has a number to be tracked by.
    ADD CONSTRAINT chk_reward_tracked_when_issued CHECK (
        (status = 'issued') = (tracking_stage IS NOT NULL)
        AND (tracking_stage IS NULL OR tracking_number IS NOT NULL)),
    -- A completed hand-over names the warehouse, the person and the moment.
    ADD CONSTRAINT chk_reward_completed CHECK (
        tracking_stage IS DISTINCT FROM 'completed'
        OR (completed_at IS NOT NULL AND completed_by IS NOT NULL
            AND handover_location_id IS NOT NULL AND receive_method IS NOT NULL));

SELECT grant_app_privileges();
