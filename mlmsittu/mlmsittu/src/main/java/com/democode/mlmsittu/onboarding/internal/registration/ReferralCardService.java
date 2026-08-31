package com.democode.mlmsittu.onboarding.internal.registration;

import com.democode.mlmsittu.catalogue.api.ItemSetCatalogue;
import com.democode.mlmsittu.catalogue.api.ItemSetRef;
import com.democode.mlmsittu.shared.businessid.PositionalId;
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
 * <h2>What a card is not</h2>
 *
 * <p>It is not a credential and it is not redeemed. Registration asks for the referrer's Business
 * ID and nothing else, exactly as it did before this class existed — the applicant never types a
 * card code, and no endpoint accepts one. The code exists so an administrator can say which
 * physical cards were printed, for whom, and by whom.
 *
 * <p>That distinction is worth holding on to. If cards ever <em>are</em> made redeemable, the code
 * becomes a bearer token: whoever holds the paper can join the network, and it would need rate
 * limiting, expiry and single-use enforcement. None of that is here, because none of it is needed
 * for a printed reference.
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

        // Only seats nobody occupies.
        //
        // A card's child ID is parent + seat, so a card for an occupied seat names an identifier
        // that already belongs to somebody. Printing five regardless would hand the customer paper
        // promising an ID another person is already using — and there would be no way to honour it.
        List<String> takenChildIds =
                jdbc.queryForList(
                        """
                        SELECT business_id FROM distributor
                         WHERE referred_by = ? AND business_id IS NOT NULL
                        """,
                        String.class,
                        distributorId);

        List<Integer> freeSeats = new ArrayList<>();
        for (int seat = 1; seat <= PositionalId.SEATS; seat++) {
            if (!takenChildIds.contains(PositionalId.child(parentId, seat))) {
                freeSeats.add(seat);
            }
        }

        if (freeSeats.isEmpty()) {
            throw new ApiException(
                    HttpStatus.BAD_REQUEST,
                    "NO_FREE_SEATS",
                    "All "
                            + PositionalId.SEATS
                            + " referral places under this customer are already filled.");
        }

        // Asking for more than are free is not an error worth refusing — the honest answer is to
        // print the ones that exist.
        List<Integer> seats = freeSeats.subList(0, Math.min(cards, freeSeats.size()));

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

        for (int seat : seats) {
            insertCard(batchId, parentId, seat);
        }

        return findBatch(batchId).orElseThrow();
    }

    /**
     * Inserts one card.
     *
     * <p>The code is the identifier the child will receive: the parent's Business ID with the seat
     * number appended, so the card for seat 3 under parent {@code 12} reads {@code 123}. Nothing is
     * drawn at random, and nothing can collide — the value is a function of the parent and the seat.
     *
     * <p>{@code card_number} holds the seat, not a position within the batch. A batch printed when
     * seats 1 and 2 are already filled contains cards numbered 3, 4 and 5.
     *
     * <p>Which means reprinting a batch produces the same five identifiers, deliberately. Card 3
     * for parent 12 is always 123, whether it was printed today or last year.
     */
    private void insertCard(UUID batchId, String parentBusinessId, int seat) {
        jdbc.update(
                "INSERT INTO referral_card (batch_id, code, card_number) VALUES (?, ?, ?)",
                batchId,
                PositionalId.child(parentBusinessId, seat),
                seat);
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
                                        rs.getString("code"),
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
