-- ============================================================================================
-- V35: announcements as marketing.
--
-- An announcement was a notice at the bottom of the dashboard. The business wants to use it to sell:
-- a banner at the top, a category a customer can filter by, a button that takes them somewhere, the
-- option to reach customers who have not registered yet — and a way to tell whether any of it works.
--
--   category    what kind of post it is; drives the chip colour and the customer's filter
--   featured    shown in the banner carousel at the top of the dashboard
--   audience    'everyone' (every signed-in customer, registered or not) or 'members' (approved
--               customers only). The default keeps the old behaviour.
--   cta_label   the button's words
--   cta_target  where the button goes — a name from a fixed list, never a URL. A notice carries no
--               link anybody else chose (see V30), and a button is a link; so it can only point at
--               a page of the portal itself.
--
-- announcement_engagement records who has seen and who has clicked, once per person each, so the
-- office can see reach and click-through without counting page refreshes.
-- ============================================================================================

ALTER TABLE announcement
    ADD COLUMN category   VARCHAR(16) NOT NULL DEFAULT 'news',
    ADD COLUMN featured   BOOLEAN     NOT NULL DEFAULT false,
    ADD COLUMN audience   VARCHAR(16) NOT NULL DEFAULT 'members',
    ADD COLUMN cta_label  VARCHAR(40),
    ADD COLUMN cta_target VARCHAR(24);

ALTER TABLE announcement
    ADD CONSTRAINT chk_announcement_category
        CHECK (category IN ('news', 'offer', 'new_arrival', 'event')),
    ADD CONSTRAINT chk_announcement_audience
        CHECK (audience IN ('everyone', 'members')),
    ADD CONSTRAINT chk_announcement_cta_target
        CHECK (cta_target IS NULL
               OR cta_target IN ('item_packs', 'registration', 'referrals', 'stages', 'offers')),
    -- A button needs both its words and its destination, or neither.
    ADD CONSTRAINT chk_announcement_cta_pair
        CHECK ((cta_label IS NULL) = (cta_target IS NULL));

CREATE TABLE announcement_engagement (
    announcement_id UUID        NOT NULL REFERENCES announcement(id) ON DELETE CASCADE,
    user_id         UUID        NOT NULL REFERENCES app_user(id),
    kind            VARCHAR(8)  NOT NULL,
    first_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (announcement_id, user_id, kind),
    CONSTRAINT chk_engagement_kind CHECK (kind IN ('view', 'click'))
);

CREATE INDEX idx_announcement_engagement_user ON announcement_engagement (user_id);

SELECT grant_app_privileges();
