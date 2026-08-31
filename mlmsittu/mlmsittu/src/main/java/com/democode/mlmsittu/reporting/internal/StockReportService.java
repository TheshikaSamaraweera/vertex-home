package com.democode.mlmsittu.reporting.internal;

import com.democode.mlmsittu.reporting.api.StockReport;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Stock position reporting (development plan P6-01). */
@Service
public class StockReportService {

    private final ReportingRepository repository;

    public StockReportService(ReportingRepository repository) {
        this.repository = repository;
    }

    /**
     * @param belowReorderOnly only items at or under their reorder level, matching the alert rule
     * @param includeInactive deactivated items still hold stock, and a stocktake has to count it
     */
    @Transactional(readOnly = true)
    public StockReport report(
            UUID locationId, UUID categoryId, boolean belowReorderOnly, boolean includeInactive) {
        return StockReport.of(
                repository.stock(locationId, categoryId, belowReorderOnly, includeInactive));
    }
}
