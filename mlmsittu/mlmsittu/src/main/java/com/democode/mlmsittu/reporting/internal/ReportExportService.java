package com.democode.mlmsittu.reporting.internal;

import com.democode.mlmsittu.reporting.api.CustomerAnalyticsRow;
import com.democode.mlmsittu.reporting.api.SalesReport;
import com.democode.mlmsittu.reporting.api.StockReportRow;
import java.util.List;
import java.util.function.Function;
import org.springframework.stereotype.Service;

/**
 * The CSV shape of each report (development plan P6-07).
 *
 * <p>Column lists live here rather than in the controller so that "what does the export contain"
 * has one answer, and so the escaping in {@link CsvWriter} is the only path any of them can take.
 */
@Service
public class ReportExportService {

    private final CsvWriter csv;

    public ReportExportService(CsvWriter csv) {
        this.csv = csv;
    }

    public byte[] stockCsv(List<StockReportRow> rows) {
        return csv.write(
                List.of(
                        "SKU", "Item", "Category", "Location",
                        "On hand", "Reserved", "Available",
                        "Reorder level", "Below reorder", "Unit cost", "Stock value"),
                List.of(
                        StockReportRow::sku,
                        StockReportRow::name,
                        row -> orDash(row.categoryName()),
                        StockReportRow::locationName,
                        StockReportRow::onHand,
                        StockReportRow::reserved,
                        StockReportRow::available,
                        StockReportRow::reorderLevel,
                        row -> row.belowReorder() ? "yes" : "no",
                        StockReportRow::unitCost,
                        StockReportRow::stockValue),
                rows);
    }

    /**
     * The daily breakdown, which is the part anyone actually recalculates by hand.
     *
     * <p>Not the totals: a one-row file of grand totals is a screenshot, not a dataset, and
     * whoever opens this wants to pivot it.
     */
    public byte[] salesCsv(SalesReport report) {
        return csv.write(
                List.of("Day", "Orders", "Net value"),
                List.of(
                        (Function<SalesReport.DailySales, Object>) SalesReport.DailySales::day,
                        SalesReport.DailySales::orderCount,
                        SalesReport.DailySales::netValue),
                report.daily());
    }

    public byte[] customersCsv(List<CustomerAnalyticsRow> rows) {
        return csv.write(
                List.of(
                        "Code", "Name", "City", "Status",
                        "Orders", "Total value", "Last order", "Days since"),
                List.of(
                        CustomerAnalyticsRow::code,
                        CustomerAnalyticsRow::name,
                        row -> orDash(row.city()),
                        row -> row.active() ? "active" : "deactivated",
                        CustomerAnalyticsRow::orderCount,
                        CustomerAnalyticsRow::totalValue,
                        row -> row.lastOrderAt() == null ? "never" : row.lastOrderAt().toString(),
                        row -> row.daysSinceLastOrder() == null ? "" : row.daysSinceLastOrder()),
                rows);
    }

    private static Object orDash(String value) {
        return value == null || value.isBlank() ? "—" : value;
    }
}
