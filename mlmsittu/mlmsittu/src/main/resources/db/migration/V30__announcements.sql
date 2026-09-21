-- ==============================================================================================
-- Announcements: what the office puts in front of every customer.
--
-- An advertisement, a price change, a notice about a delivery. Written by an administrator with
-- headings, bold, italic and highlighting, optionally with a picture, and shown on every
-- customer's portal home page until it is taken down.
--
-- WHY THE BODY IS JSON AND NOT HTML
-- ----------------------------------
-- Because HTML written by one person and rendered in everybody else's browser is an XSS hole held
-- shut by a sanitiser, and sanitisers are a category of thing that gets bypassed. Administrators
-- are trusted, but "trusted" is a statement about intent, not about whether their account is ever
-- compromised — and the blast radius here is every customer.
--
-- So no HTML crosses the boundary in either direction. The body is a document of typed blocks —
-- heading, paragraph, list, image — with inline marks limited to bold, italic and highlight. The
-- browser renders it by walking that structure and emitting React elements, so there is no
-- innerHTML anywhere in the path and a block type nobody recognises is skipped rather than
-- guessed at.
--
-- The cost is a renderer of about sixty lines. The alternative is a sanitiser that has to be
-- right forever.
-- ==============================================================================================

CREATE TABLE announcement (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),

    title       VARCHAR(200) NOT NULL,
    -- A standfirst under the title. Optional, because not every notice needs one and an empty
    -- subtitle looks like a mistake rather than a choice.
    subtitle    VARCHAR(300),

    -- The typed block document. See the header above for why this is not HTML.
    body        JSONB NOT NULL,

    -- The picture, held in the same vault as every other upload so it gets the same magic-byte
    -- check, the same re-encode that strips metadata, and the same size limit. ON DELETE SET NULL
    -- rather than CASCADE: losing the image must not delete the notice.
    image_id    UUID REFERENCES stored_document(id) ON DELETE SET NULL,

    -- Draft until it is sent. An administrator writing a long notice over two sittings should not
    -- be broadcasting the half-finished version in between.
    published_at TIMESTAMPTZ,

    -- When to stop showing it. Null means until somebody takes it down by hand — right for a
    -- standing notice, wrong for "50% off this week", which is why both are possible.
    expires_at  TIMESTAMPTZ,

    created_by  UUID NOT NULL REFERENCES app_user(id),
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- What the portal asks for on every home page load: published, not expired, newest first. Partial,
-- so drafts never enter the index.
CREATE INDEX idx_announcement_live ON announcement (published_at DESC)
 WHERE published_at IS NOT NULL;

-- The admin list, which includes drafts.
CREATE INDEX idx_announcement_created ON announcement (created_at DESC);


-- ----------------------------------------------------------------------------------------------
-- An announcement image is a fourth kind of document.
--
-- Unlike the other three it is meant to be seen by everybody, which the serving endpoint enforces
-- by kind: only 'announcement' is readable without a single-use token. A NIC scan can never be
-- served that way because it can never carry this kind.
-- ----------------------------------------------------------------------------------------------
ALTER TABLE stored_document DROP CONSTRAINT IF EXISTS chk_document_kind;
ALTER TABLE stored_document
    ADD CONSTRAINT chk_document_kind
    CHECK (kind IN ('nic', 'bank_slip', 'announcement', 'other'));

SELECT grant_app_privileges();
