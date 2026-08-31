package com.democode.mlmsittu.inventory.internal.service;

import com.democode.mlmsittu.inventory.api.LocationDirectory.LocationRef;
import com.democode.mlmsittu.inventory.api.StockLedger;
import com.democode.mlmsittu.inventory.api.StockPosting;
import com.democode.mlmsittu.inventory.api.StockView;
import com.democode.mlmsittu.inventory.internal.location.LocationService;
import com.democode.mlmsittu.shared.audit.api.AuditContext;
import com.democode.mlmsittu.shared.audit.api.Audited;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Manual stock corrections (development plan P2-10).
 *
 * <p>Notice what this class does <em>not</em> do: it never touches a stock level. It builds a
 * posting and hands it to the ledger, exactly as the goods-receipt path does. That is the whole
 * point of the sealed aggregate — there is one way in, so there is one place where the balance
 * rules live.
 */
@Service
public class StockAdjustmentService {

    private final StockLedger ledger;
    private final LocationService locations;

    public StockAdjustmentService(StockLedger ledger, LocationService locations) {
        this.ledger = ledger;
        this.locations = locations;
    }

    /**
     * @param reason mandatory. An adjustment without one is an unexplained change to a financial
     *     record; the ledger service rejects it and so does the DTO.
     */
    @Transactional
    @Audited(action = "STOCK_ADJUSTED", entityType = "stock_level", auditFailures = true)
    public StockView adjust(
            UUID itemId, UUID locationId, int qtyDelta, String reason, String note, UUID actorId) {

        LocationRef location = locations.require(locationId);

        StockView before =
                ledger.levelOf(itemId, location.id())
                        .orElse(new StockView(itemId, location.id(), 0, 0));

        ledger.post(
                StockPosting.adjustment(itemId, location.id(), qtyDelta, reason, note, actorId));

        StockView after = ledger.levelOf(itemId, location.id()).orElseThrow();

        AuditContext.record(itemId, snapshot(before, null), snapshot(after, reason));
        return after;
    }

    private Map<String, Object> snapshot(StockView view, String reason) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("itemId", view.itemId());
        map.put("locationId", view.locationId());
        map.put("onHand", view.onHand());
        map.put("reserved", view.reserved());
        if (reason != null) {
            map.put("reason", reason);
        }
        return map;
    }
}
