package com.democode.mlmsittu.inventory.internal.stock;

import com.democode.mlmsittu.shared.audit.api.AuditContext;
import com.democode.mlmsittu.shared.audit.api.Audited;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Rebuilds {@code stock_level} from {@code stock_movement} (development plan P2-05).
 *
 * <p>This is the operation that makes "the ledger is the source of truth" a fact rather than a
 * slogan. If the projection can always be regenerated from the movements, then any drift — a bug,
 * a bad migration, someone with a psql prompt — is recoverable rather than a data-loss incident.
 *
 * <p><b>It writes no movement.</b> A reconciliation movement would change the very sum being
 * compared against, so the ledger is treated as read-only here and only the projection is
 * corrected. Use {@code StockMovementType.RECONCILIATION} for the opposite case, where a physical
 * count says the ledger itself is wrong.
 *
 * <p><b>It does not touch {@code reserved}.</b> Reservations are live state with no ledger
 * representation; there is nothing to replay them from. Only {@code on_hand} is derivable.
 */
@Service
public class StockReconciliationService {

    private static final Logger log = LoggerFactory.getLogger(StockReconciliationService.class);

    private final StockLevelRepository levels;
    private final StockMovementRepository movements;

    public StockReconciliationService(
            StockLevelRepository levels, StockMovementRepository movements) {
        this.levels = levels;
        this.movements = movements;
    }

    /** One row that disagreed, and what was done about it. */
    public record Discrepancy(
            UUID itemId, UUID locationId, int projectedOnHand, long ledgerTotal, boolean repaired) {}

    public record Report(int rowsChecked, int discrepanciesFound, List<Discrepancy> discrepancies) {}

    @Transactional
    @Audited(action = "STOCK_RECONCILED", entityType = "stock_level")
    public Report reconcile() {
        // Ledger truth, keyed by row. Sorted so locks are taken in a consistent order relative to
        // every other writer in the system.
        Map<StockLevelId, Long> ledgerTotals = new TreeMap<>();
        for (LedgerTotal total : movements.totalsByItemAndLocation()) {
            ledgerTotals.put(new StockLevelId(total.itemId(), total.locationId()), total.total());
        }

        // Existing projection rows. Any row with no movements at all must end up at zero — a
        // stale non-zero row is exactly the kind of drift this job exists to find.
        Map<StockLevelId, StockLevel> projection = new HashMap<>();
        for (StockLevel level : levels.findAllOrdered()) {
            projection.put(new StockLevelId(level.getItemId(), level.getLocationId()), level);
        }
        projection.keySet().forEach(key -> ledgerTotals.putIfAbsent(key, 0L));

        List<Discrepancy> discrepancies = new ArrayList<>();

        for (Map.Entry<StockLevelId, Long> entry : ledgerTotals.entrySet()) {
            StockLevelId key = entry.getKey();
            long expected = entry.getValue();

            levels.ensureRowExists(key.getItemId(), key.getLocationId());
            StockLevel level =
                    levels.lockForUpdate(key.getItemId(), key.getLocationId()).orElseThrow();

            if (level.getOnHand() == expected) {
                continue;
            }

            log.warn(
                    "Stock projection drift on item {} at location {}: projection {}, ledger {}",
                    key.getItemId(),
                    key.getLocationId(),
                    level.getOnHand(),
                    expected);

            discrepancies.add(
                    new Discrepancy(
                            key.getItemId(),
                            key.getLocationId(),
                            level.getOnHand(),
                            expected,
                            true));

            level.overwriteOnHand(Math.toIntExact(expected));
            levels.save(level);
        }

        Report report = new Report(ledgerTotals.size(), discrepancies.size(), discrepancies);
        AuditContext.record(
                null,
                null,
                Map.of(
                        "rowsChecked", report.rowsChecked(),
                        "discrepanciesFound", report.discrepanciesFound()));
        return report;
    }
}
