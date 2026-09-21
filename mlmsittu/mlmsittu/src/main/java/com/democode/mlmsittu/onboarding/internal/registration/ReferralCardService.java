package com.democode.mlmsittu.onboarding.internal.registration;

import com.democode.mlmsittu.catalogue.api.ItemSetCatalogue;
import com.democode.mlmsittu.catalogue.api.ItemSetRef;
import com.democode.mlmsittu.shared.businessid.PositionalId;
import org.springframework.dao.DuplicateKeyException;
import com.democode.mlmsittu.shared.businessid.CardNumber;
import com.democode.mlmsittu.shared.error.NotFoundException;
import org.springframework.http.HttpStatus;
import com.democode.mlmsittu.shared.error.ApiException;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Printed referral cards.
 *
 * <p>A customer needs five referrals to finish all five stages. An administrator prints five cards
 * and hands them over; the customer gives one to each person they recruit. The card carries the
 * parent's Business ID, the pack on offer, and a code identifying that individual card.
 *
 * <h2>A card is a bearer token</h2>
 *
 * <p>Cards are sold. The buyer pays the customer who was given them, types the number at
 * registration, and joins the network underneath that customer. So whoever holds the paper can
 * claim a place — which is what makes the number worth protecting and worth spending exactly once.
 *
 * <p>Three things follow, and all three are enforced rather than assumed:
 *
 * <ul>
 *   <li><b>The number is unguessable.</b> Drawn at random by {@link CardNumber}, not derived from
 *       the parent. It used to read {@code 123} for the third card under parent {@code 12}, which
 *       told anybody holding one card the numbers of the other four.
 *   <li><b>It is redeemed once.</b> Claimed under a row lock at submission and released if the
 *       registration is rejected, so two people cannot spend the same card and a refused applicant
 *       does not lose theirs.
 *   <li><b>It must match the parent the applicant names.</b> A card resold outside the intended
 *       upline is refused, and a mistyped Business ID is caught rather than silently attaching
 *       somebody to the wrong person.
 * </ul>
 *
 * <h2>What a card no longer promises</h2>
 *
 * <p>A particular Business ID. It used to print the identifier that seat was destined for, which
 * was a promise the system could not keep: seats are consumed in the order registrations are
 * approved, not the order cards were sold, so the third card sold can easily become seat 1. The
 * card now says which parent it belongs to and nothing about where under them the buyer lands.
 */
@Service
public class ReferralCardService {

    /**
     * Five, matching the referral cap.
     *
     * <p>Not read from {@code system_config} like the cap itself. The cap governs what the database
     * will accept; this governs how many pieces of paper an administrator gets by default, and they
     * can ask for a different number. Coupling them would mean raising the cap silently changed how
     * many cards print, which is a stationery decision rather than a policy one.
     */
    public static final int DEFAULT_CARD_COUNT = 5;

    /**
     * One card per seat, and there are only five seats.
     *
     * <p>This used to allow twenty, when a card carried a random code and printing extras was
     * harmless. It cannot now: a card's code is the child identifier {@code parent + seat}, and
     * seats run 1-5. A sixth card would name an identifier that can never be issued.
     */
    private static final int MAX_CARD_COUNT = PositionalId.SEATS;

    private final JdbcTemplate jdbc;
    private final ItemSetCatalogue itemSets;

    public ReferralCardService(JdbcTemplate jdbc, ItemSetCatalogue itemSets) {
        this.jdbc = jdbc;
        this.itemSets = itemSets;
    }

    // ------------------------------------------------------------------ shapes

    /** One card, as printed. */
    public record ReferralCard(UUID id, String code, int cardNumber) {}

    /**
     * A print run, with everything the printed page needs.
     *
     * <p>The parent's name and Business ID are resolved at read time — those identify a person and
     * should follow a correction. The pack name and price are the values stored when the batch was
     * created, because they are printed on paper somebody is holding and must not change under
     * them when the catalogue does.
     */
    public record ReferralCardBatch(
            UUID id,
            UUID distributorId,
            String parentName,
            String parentBusinessId,
            UUID itemSetId,
            String itemSetName,
            BigDecimal itemSetPrice,
            String issuedByName,
            Instant issuedAt,
            String note,
            int referralsUsed,
            List<ReferralCard> cards) {}

    // ------------------------------------------------------------------ issuing

    /**
     * Prints a batch of cards for one customer.
     *
     * @param itemSetId the pack to print on the cards; may be null for cards that name no pack
     * @throws NotFoundException if the customer does not exist or has no Business ID yet
     */
    @Transactional
    public ReferralCardBatch issue(UUID distributorId, UUID itemSetId, Integer count, String note, UUID issuedBy) {
        int cards = count == null ? DEFAULT_CARD_COUNT : count;
        if (cards < 1 || cards > MAX_CARD_COUNT) {
            throw new ApiException(
                    HttpStatus.BAD_REQUEST,
                    "INVALID_CARD_COUNT",
                    "Between 1 and " + MAX_CARD_COUNT + " cards at a time.");
        }

        // A Business ID is allocated at approval. Printing cards for somebody who does not have one
        // yet would produce a card whose whole purpose — naming the referrer — is blank.
        List<String> businessIds =
                jdbc.queryForList(
                        "SELECT business_id FROM distributor WHERE id = ? AND deleted_at IS NULL",
                        String.class,
                        distributorId);

        if (businessIds.isEmpty()) {
            throw new NotFoundException("DISTRIBUTOR_NOT_FOUND", "No such customer.");
        }
        if (businessIds.get(0) == null || businessIds.get(0).isBlank()) {
            throw new ApiException(
                    HttpStatus.BAD_REQUEST,
                    "NOT_APPROVED",
                    "This customer has no Business ID yet. Cards can only be printed once they are approved.");
        }

        String parentId = businessIds.get(0);

        // How many places are genuinely still open under this parent.
        //
        // Two things consume one: a child already approved, and a card already printed and not
        // yet redeemed. Counting only the first would let an administrator print five more cards
        // for somebody who already has five out, and the sixth buyer would pay for a card that
        // can never be honoured.
        Integer occupied =
                jdbc.queryForObject(
                        """
                        SELECT (SELECT count(*) FROM distributor
                                 WHERE referred_by = ? AND business_id IS NOT NULL)
                             + (SELECT count(*) FROM referral_card c
                                  JOIN referral_card_batch b ON b.id = c.batch_id
                                 WHERE b.distributor_id = ? AND c.redeemed_at IS NULL)
                        """,
                        Integer.class,
                        distributorId,
                        distributorId);

        int free = PositionalId.SEATS - (occupied == null ? 0 : occupied);

        if (free <= 0) {
            throw new ApiException(
                    HttpStatus.BAD_REQUEST,
                    "NO_FREE_SEATS",
                    "All "
                            + PositionalId.SEATS
                            + " referral places under this customer are taken or already have a"
                            + " card out for them.");
        }

        // Asking for more than are free is not an error worth refusing — the honest answer is to
        // print the ones that can actually be sold.
        int toPrint = Math.min(cards, free);

        ItemSetRef pack =
                itemSetId == null ? null : itemSets.findById(itemSetId).orElseThrow(
                        () -> new NotFoundException("ITEM_SET_NOT_FOUND", "That item pack does not exist."));

        UUID batchId =
                jdbc.queryForObject(
                        """
                        INSERT INTO referral_card_batch
                               (distributor_id, item_set_id, item_set_name, item_set_price, issued_by, note)
                        VALUES (?, ?, ?, ?, ?, ?)
                        RETURNING id
                        """,
                        UUID.class,
                        distributorId,
                        pack == null ? null : pack.id(),
                        pack == null ? null : pack.name(),
                        pack == null ? null : pack.setPrice(),
                        issuedBy,
                        note == null || note.isBlank() ? null : note.trim());

        for (int position = 1; position <= toPrint; position++) {
            insertCard(batchId, position);
        }

        return findBatch(batchId).orElseThrow();
    }

    /**
     * Inserts one card, with a number nobody can guess.
     *
     * <p>Retried on collision rather than trusted. Eight characters from a 31-character alphabet
     * makes a clash vanishingly unlikely, and "vanishingly unlikely" is not "impossible" — the
     * unique index is what guarantees it, and this is what turns the guarantee into a second draw
     * instead of a failed print run.
     *
     * <p>{@code card_number} is now only the card's position within its batch: card 1 of 3. It
     * used to be the seat the buyer would occupy, which the card no longer promises.
     */
    private void insertCard(UUID batchId, int positionInBatch) {
        for (int attempt = 0; attempt < 20; attempt++) {
            try {
                jdbc.update(
                        "INSERT INTO referral_card (batch_id, code, card_number) VALUES (?, ?, ?)",
                        batchId,
                        CardNumber.generate(),
                        positionInBatch);
                return;
            } catch (DuplicateKeyException collision) {
                // Draw again.
            }
        }
        throw new ApiException(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "CARD_NUMBER_ALLOCATION_FAILED",
                "Could not allocate a card number. Try again.");
    }

    // ------------------------------------------------------------------ redeeming

    /** A card, resolved far enough to decide whether it may be spent. */
    public record CardClaim(UUID cardId, UUID distributorId, String parentBusinessId) {}

    /**
     * Claims a card for a registration, or explains precisely why it cannot be.
     *
     * <p>{@code FOR UPDATE} on the card row. Two people typing the same number at the same instant
     * is not a theoretical race here — a card number is a thing that gets shared, photographed and
     * forwarded, and the second person must be told it is spent rather than both being let in.
     *
     * <p>Every refusal is a distinct code, because they mean different things to the person at the
     * desk: a number that does not exist is a typo, a number belonging to somebody else is a card
     * sold outside its upline, and a spent one is a card that has already been used. Collapsing
     * them into "invalid card" would leave an administrator guessing which.
     */
    @Transactional
    public CardClaim claim(String rawCardNumber, String parentBusinessId) {
        String normalised = CardNumber.normalise(rawCardNumber);

        if (!CardNumber.isValid(normalised)) {
            throw new ApiException(
                    HttpStatus.BAD_REQUEST,
                    "INVALID_CARD_NUMBER",
                    "That is not a card number. It is eight characters, like K7M2-P4X9.");
        }

        List<CardRow> found =
                jdbc.query(
                        """
                        SELECT c.id,
                               c.redeemed_at IS NOT NULL AS spent,
                               b.distributor_id,
                               d.business_id
                          FROM referral_card c
                          JOIN referral_card_batch b ON b.id = c.batch_id
                          JOIN distributor d         ON d.id = b.distributor_id
                         WHERE upper(c.code) = ?
                           FOR UPDATE OF c
                        """,
                        (rs, row) ->
                                new CardRow(
                                        rs.getObject("id", UUID.class),
                                        rs.getBoolean("spent"),
                                        rs.getObject("distributor_id", UUID.class),
                                        rs.getString("business_id")),
                        normalised);

        if (found.isEmpty()) {
            throw new NotFoundException(
                    "CARD_NOT_FOUND", "No card has that number. Check it against the card.");
        }

        CardRow card = found.get(0);

        if (card.spent()) {
            throw new ApiException(
                    HttpStatus.CONFLICT,
                    "CARD_ALREADY_USED",
                    "That card has already been used to register somebody.");
        }

        // The card and the Business ID must agree. Checking both is what catches a card resold
        // outside its upline, and a mistyped parent ID that would otherwise attach this person to
        // somebody they have never met.
        String normalisedParent = PositionalId.normalise(parentBusinessId);
        if (!normalisedParent.equals(card.parentBusinessId())) {
            ApiException mismatch =
                    new ApiException(
                            HttpStatus.BAD_REQUEST,
                            "CARD_PARENT_MISMATCH",
                            "That card was not issued by the customer whose ID you entered.");
            mismatch.with("cardParentBusinessId", card.parentBusinessId());
            throw mismatch;
        }

        return new CardClaim(card.id(), card.distributorId(), card.parentBusinessId());
    }

    private record CardRow(UUID id, boolean spent, UUID distributorId, String parentBusinessId) {}

    /** Marks a claimed card spent, once the registration it belongs to exists. */
    @Transactional
    public void markRedeemed(UUID cardId, UUID userId, UUID registrationId) {
        jdbc.update(
                """
                UPDATE referral_card
                   SET redeemed_at = now(), redeemed_by = ?, registration_id = ?
                 WHERE id = ? AND redeemed_at IS NULL
                """,
                userId,
                registrationId,
                cardId);
    }

    /**
     * Hands a card back.
     *
     * <p>A registration refused for a blurred NIC photograph must not cost somebody the card they
     * paid for. Called when a registration is rejected or sent back for changes — the card returns
     * to unspent and the same number works on the next attempt.
     */
    @Transactional
    public void release(UUID registrationId) {
        jdbc.update(
                """
                UPDATE referral_card
                   SET redeemed_at = NULL, redeemed_by = NULL, registration_id = NULL
                 WHERE registration_id = ?
                """,
                registrationId);
    }

    // ------------------------------------------------------------------ reading

    @Transactional(readOnly = true)
    public List<ReferralCardBatch> listForDistributor(UUID distributorId) {
        List<UUID> batchIds =
                jdbc.queryForList(
                        "SELECT id FROM referral_card_batch WHERE distributor_id = ? ORDER BY issued_at DESC",
                        UUID.class,
                        distributorId);
        List<ReferralCardBatch> batches = new ArrayList<>(batchIds.size());
        for (UUID id : batchIds) {
            findBatch(id).ifPresent(batches::add);
        }
        return batches;
    }

    @Transactional(readOnly = true)
    public Optional<ReferralCardBatch> findBatch(UUID batchId) {
        List<ReferralCardBatch> found =
                jdbc.query(
                        """
                        SELECT b.id,
                               b.distributor_id,
                               u.full_name                        AS parent_name,
                               d.business_id                      AS parent_business_id,
                               b.item_set_id,
                               b.item_set_name,
                               b.item_set_price,
                               issuer.full_name                   AS issued_by_name,
                               b.issued_at,
                               b.note,
                               COALESCE(d.direct_child_count, 0)  AS referrals_used
                          FROM referral_card_batch b
                          JOIN distributor d   ON d.id = b.distributor_id
                          JOIN app_user u      ON u.id = d.user_id
                          JOIN app_user issuer ON issuer.id = b.issued_by
                         WHERE b.id = ?
                        """,
                        (rs, row) ->
                                new ReferralCardBatch(
                                        rs.getObject("id", UUID.class),
                                        rs.getObject("distributor_id", UUID.class),
                                        rs.getString("parent_name"),
                                        rs.getString("parent_business_id"),
                                        rs.getObject("item_set_id", UUID.class),
                                        rs.getString("item_set_name"),
                                        rs.getBigDecimal("item_set_price"),
                                        rs.getString("issued_by_name"),
                                        rs.getTimestamp("issued_at").toInstant(),
                                        rs.getString("note"),
                                        rs.getInt("referrals_used"),
                                        List.of()),
                        batchId);

        if (found.isEmpty()) {
            return Optional.empty();
        }

        List<ReferralCard> cards =
                jdbc.query(
                        "SELECT id, code, card_number FROM referral_card WHERE batch_id = ? ORDER BY card_number",
                        (rs, row) ->
                                new ReferralCard(
                                        rs.getObject("id", UUID.class),
                                        // Hyphenated here and nowhere else: this is the one place
                                        // the number is on its way to a human.
                                        CardNumber.format(rs.getString("code")),
                                        rs.getInt("card_number")),
                        batchId);

        ReferralCardBatch batch = found.get(0);
        return Optional.of(
                new ReferralCardBatch(
                        batch.id(),
                        batch.distributorId(),
                        batch.parentName(),
                        batch.parentBusinessId(),
                        batch.itemSetId(),
                        batch.itemSetName(),
                        batch.itemSetPrice(),
                        batch.issuedByName(),
                        batch.issuedAt(),
                        batch.note(),
                        batch.referralsUsed(),
                        cards));
    }
}
