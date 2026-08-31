package com.democode.mlmsittu.inventory.internal.stock;

import com.democode.mlmsittu.catalogue.api.ItemCatalogue;
import com.democode.mlmsittu.inventory.api.InsufficientStockException;
import com.democode.mlmsittu.inventory.api.StockLedger;
import com.democode.mlmsittu.inventory.api.StockMovementType;
import com.democode.mlmsittu.inventory.api.StockMovementView;
import com.democode.mlmsittu.inventory.api.StockPosting;
import com.democode.mlmsittu.inventory.api.StockView;
import com.democode.mlmsittu.shared.datasource.ReadFromPrimary;
import com.democode.mlmsittu.shared.error.ApiException;
import com.democode.mlmsittu.shared.error.NotFoundException;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The single writer of {@code stock_level} (development plan P2-04, architecture §4.1).
 *
 * <p>Two invariants this class exists to hold:
 *
 * <ol>
 *   <li><b>The ledger and the projection never disagree.</b> Both are written in one transaction,
 *       so there is no window in which a movement exists without its effect, or the reverse.
 *   <li><b>Stock cannot go negative</b> — checked under a row lock, and backed by a database
 *       CHECK constraint for anything that somehow gets past the check.
 * </ol>
 */
@ReadFromPrimary
@Service
public class StockLedgerService implements StockLedger {

    private final StockLevelRepository levels;
    private final StockMovementRepository movements;
    private final ItemCatalogue itemCatalogue;

    public StockLedgerService(
            StockLevelRepository levels,
            StockMovementRepository movements,
            ItemCatalogue itemCatalogue) {
        this.levels = levels;
        this.movements = movements;
        this.itemCatalogue = itemCatalogue;
    }

    @Override
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void post(StockPosting posting) {
        postAll(List.of(posting));
    }

    @Override
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void postAll(List<StockPosting> postings) {
        if (postings.isEmpty()) {
            return;
        }

        postings.forEach(this::validate);
        assertItemsExist(postings);

        // Net the postings per (item, location) first. A receipt that lists the same item on two
        // lines must be one balance check against the total, not two that each pass alone.
        //
        // TreeMap, not HashMap: iteration order is the sort order of StockLevelId, which is what
        // makes the lock order deterministic. Two concurrent postings touching the same rows in
        // opposite sequence would otherwise deadlock — the §4.5 lesson, which applies to any
        // multi-row write, not only to reservation.
        Map<StockLevelId, Integer> netByRow = new TreeMap<>();
        for (StockPosting posting : postings) {
            netByRow.merge(
                    new StockLevelId(posting.itemId(), posting.locationId()),
                    posting.qtyDelta(),
                    Integer::sum);
        }

        for (Map.Entry<StockLevelId, Integer> entry : netByRow.entrySet()) {
            applyToLevel(entry.getKey(), entry.getValue());
        }

        // Movements last, so nothing is appended if a balance check above rejected the batch.
        postings.forEach(this::appendMovement);
    }

    @Override
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void ensurePosition(UUID itemId, UUID locationId) {
        if (itemId == null || locationId == null) {
            throw new ApiException(
                    HttpStatus.BAD_REQUEST,
                    "INVALID_STOCK_POSTING",
                    "Item and location are both required.");
        }
        // No movement and no actor, because nothing moved. The row starts at zero and the ledger
        // still explains it: an empty position is the sum of no movements.
        levels.ensureRowExists(itemId, locationId);
    }

    private void applyToLevel(StockLevelId key, int delta) {
        if (delta == 0) {
            // Postings that cancel out within one batch still each get a movement row, but the
            // projection has nothing to change.
            return;
        }

        levels.ensureRowExists(key.getItemId(), key.getLocationId());

        StockLevel level =
                levels.lockForUpdate(key.getItemId(), key.getLocationId())
                        .orElseThrow(
                                () ->
                                        new IllegalStateException(
                                                "stock_level row vanished after being ensured: "
                                                        + key));

        int newOnHand = level.getOnHand() + delta;

        if (newOnHand < 0) {
            throw new InsufficientStockException(
                    key.getItemId(), key.getLocationId(), -delta, level.getOnHand());
        }

        if (newOnHand < level.getReserved()) {
            // Legal on_hand, but it would strand reservations that could never be fulfilled.
            // Release the reservations first, then adjust.
            throw new ApiException(
                            HttpStatus.CONFLICT,
                            "STOCK_RESERVED",
                            "That would take stock below the quantity already reserved.")
                    .with("itemId", key.getItemId())
                    .with("locationId", key.getLocationId())
                    .with("resultingOnHand", newOnHand)
                    .with("reserved", level.getReserved());
        }

        level.applyDelta(delta);
        levels.save(level);
    }

    private void appendMovement(StockPosting posting) {
        StockMovement movement = new StockMovement();
        movement.setItemId(posting.itemId());
        movement.setLocationId(posting.locationId());
        movement.setQtyDelta(posting.qtyDelta());
        movement.setMovementType(posting.type());
        movement.setReferenceType(posting.referenceType());
        movement.setReferenceId(posting.referenceId());
        movement.setReason(posting.reason());
        movement.setNote(posting.note());
        movement.setCreatedBy(posting.actorId());
        movements.save(movement);
    }

    // ------------------------------------------------------------------ validation

    private void validate(StockPosting posting) {
        if (posting.itemId() == null || posting.locationId() == null) {
            throw new ApiException(
                    HttpStatus.BAD_REQUEST,
                    "INVALID_STOCK_POSTING",
                    "Item and location are both required.");
        }
        if (posting.qtyDelta() == 0) {
            throw new ApiException(
                    HttpStatus.BAD_REQUEST,
                    "ZERO_QUANTITY",
                    "A stock movement of zero carries no information.");
        }
        if (posting.actorId() == null) {
            // Every movement is attributable. This is why Phase 1 came before Phase 2.
            throw new IllegalArgumentException("StockPosting requires an actorId");
        }
        if (posting.type() == StockMovementType.ADJUSTMENT
                && (posting.reason() == null || posting.reason().isBlank())) {
            throw new ApiException(
                    HttpStatus.BAD_REQUEST,
                    "REASON_REQUIRED",
                    "An adjustment must record why stock changed.");
        }
    }

    private void assertItemsExist(List<StockPosting> postings) {
        Set<UUID> itemIds = new LinkedHashSet<>();
        postings.forEach(posting -> itemIds.add(posting.itemId()));

        Set<UUID> known = itemCatalogue.findAllById(itemIds).keySet();
        for (UUID itemId : itemIds) {
            if (!known.contains(itemId)) {
                // Caught here rather than left to the foreign key, so the caller gets a 404 with
                // the offending id instead of a 500 wrapping a constraint name.
                NotFoundException notFound =
                        new NotFoundException("ITEM_NOT_FOUND", "No item with that id.");
                notFound.with("itemId", itemId);
                throw notFound;
            }
        }
    }

    // ------------------------------------------------------------------ reads

    @Override
    @Transactional(readOnly = true)
    public Optional<StockView> levelOf(UUID itemId, UUID locationId) {
        return levels.findById(new StockLevelId(itemId, locationId)).map(StockLevel::toView);
    }

    @Override
    @Transactional(readOnly = true)
    public List<StockView> levelsAt(UUID locationId) {
        return levels.findAtLocation(locationId).stream().map(StockLevel::toView).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<StockView> allLevels() {
        return levels.findAllOrdered().stream().map(StockLevel::toView).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<StockMovementView> movementHistory(UUID itemId, UUID locationId) {
        return movements.search(itemId, locationId).stream().map(this::toView).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<StockMovementView> movementPage(
            UUID itemId, UUID locationId, Long beforeId, int limit) {
        return movements
                .searchPage(itemId, locationId, beforeId, PageRequest.of(0, limit))
                .stream()
                .map(this::toView)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<StockMovementView> movementsFor(String referenceType, UUID referenceId) {
        return movements
                .findByReferenceTypeAndReferenceIdOrderByIdAsc(referenceType, referenceId)
                .stream()
                .map(this::toView)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public long ledgerTotalFor(UUID itemId, UUID locationId) {
        return movements.totalFor(itemId, locationId);
    }

    private StockMovementView toView(StockMovement movement) {
        return new StockMovementView(
                movement.getId(),
                movement.getItemId(),
                movement.getLocationId(),
                movement.getQtyDelta(),
                movement.getMovementType(),
                movement.getReferenceType(),
                movement.getReferenceId(),
                movement.getReason(),
                movement.getNote(),
                movement.getCreatedBy(),
                movement.getCreatedAt());
    }

}
