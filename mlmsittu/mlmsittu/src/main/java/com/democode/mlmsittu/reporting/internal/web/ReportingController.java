package com.democode.mlmsittu.reporting.internal.web;

import com.democode.mlmsittu.reporting.api.CustomerAnalyticsRow;
import com.democode.mlmsittu.reporting.api.SalesReport;
import com.democode.mlmsittu.reporting.api.StockReport;
import com.democode.mlmsittu.reporting.api.TreemapNode;
import com.democode.mlmsittu.reporting.internal.CustomerAnalyticsService;
import com.democode.mlmsittu.reporting.internal.ReportExportService;
import com.democode.mlmsittu.reporting.internal.SalesReportService;
import com.democode.mlmsittu.reporting.internal.StockReportService;
import com.democode.mlmsittu.shared.api.PagedResponse;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Reports and exports (development plan P6-01 to P6-04, P6-07).
 *
 * <h2>Who may read what</h2>
 *
 * Stock reporting is open to anyone signed in — a clerk needs it to do their job, and it contains
 * no personal data. <b>Sales figures and customer analytics are not</b>: they are commercial and
 * personal data, so they are restricted to finance, support and admins. An inventory clerk asking
 * for the sales report gets a 403, which is the same answer architecture §8.1 gives everywhere
 * else.
 */
@RestController
@RequestMapping("/api/v1/reports")
@PreAuthorize("hasRole('STAFF')")
public class ReportingController {

    /** Everyone allowed to see money and customers. */
    private static final String COMMERCIAL_ROLES =
            "hasAnyRole('FINANCE_OFFICER', 'SUPER_ADMIN', 'SUPPORT_AGENT')";

    private final StockReportService stock;
    private final SalesReportService sales;
    private final CustomerAnalyticsService customers;
    private final ReportExportService exports;

    public ReportingController(
            StockReportService stock,
            SalesReportService sales,
            CustomerAnalyticsService customers,
            ReportExportService exports) {
        this.stock = stock;
        this.sales = sales;
        this.customers = customers;
        this.exports = exports;
    }

    // ================================================================== stock (P6-01)

    @GetMapping("/stock")
    public StockReport stockReport(
            @RequestParam(required = false) UUID locationId,
            @RequestParam(required = false) UUID categoryId,
            @RequestParam(defaultValue = "false") boolean belowReorderOnly,
            @RequestParam(defaultValue = "false") boolean includeInactive) {
        return stock.report(locationId, categoryId, belowReorderOnly, includeInactive);
    }

    @GetMapping("/stock.csv")
    public ResponseEntity<byte[]> stockCsv(
            @RequestParam(required = false) UUID locationId,
            @RequestParam(required = false) UUID categoryId,
            @RequestParam(defaultValue = "false") boolean belowReorderOnly,
            @RequestParam(defaultValue = "false") boolean includeInactive) {

        StockReport report = stock.report(locationId, categoryId, belowReorderOnly, includeInactive);
        return asCsv(exports.stockCsv(report.rows()), "stock-report");
    }

    // ================================================================== sales (P6-02)

    @GetMapping("/sales")
    @PreAuthorize(COMMERCIAL_ROLES)
    public SalesReport salesReport(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
                    LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
                    LocalDate to) {
        return sales.report(from, to);
    }

    @GetMapping("/sales.csv")
    @PreAuthorize(COMMERCIAL_ROLES)
    public ResponseEntity<byte[]> salesCsv(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
                    LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
                    LocalDate to) {
        return asCsv(exports.salesCsv(sales.report(from, to)), "sales-report");
    }

    // ================================================================== customers (P6-03, P6-04)

    @GetMapping("/customers")
    @PreAuthorize(COMMERCIAL_ROLES)
    public PagedResponse<CustomerAnalyticsRow> customerAnalytics(
            @RequestParam(defaultValue = "false") boolean includeInactive) {
        // The frozen envelope, unpaginated for now — P6-03 wants all five hundred rows in one
        // response so the load time can be measured against what Phase 7's cursor does to it.
        return PagedResponse.of(customers.customers(includeInactive));
    }

    @GetMapping("/customers.csv")
    @PreAuthorize(COMMERCIAL_ROLES)
    public ResponseEntity<byte[]> customersCsv(
            @RequestParam(defaultValue = "false") boolean includeInactive) {
        return asCsv(exports.customersCsv(customers.customers(includeInactive)), "customers");
    }

    @GetMapping("/customers/treemap")
    @PreAuthorize(COMMERCIAL_ROLES)
    public TreemapNode customerTreemap(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
                    LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
                    LocalDate to) {
        return customers.treemap(from, to);
    }

    // ================================================================== helpers

    /**
     * Served as an attachment, never inline.
     *
     * <p>{@code nosniff} with it: the content is user-influenced text, and a browser that decides
     * for itself that a {@code .csv} is really HTML would render whatever a customer typed into a
     * name field. The download is also {@code no-store} — these are commercial figures and a shared
     * machine's disk cache is not where they belong.
     */
    private ResponseEntity<byte[]> asCsv(byte[] content, String basename) {
        String filename = basename + "-" + LocalDate.now() + ".csv";
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_TYPE, "text/csv; charset=utf-8")
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .header("X-Content-Type-Options", "nosniff")
                .contentType(MediaType.parseMediaType("text/csv; charset=utf-8"))
                .body(content);
    }
}
