package com.democode.mlmsittu.reporting.internal;

import com.democode.mlmsittu.reporting.api.SalesReport;
import com.democode.mlmsittu.shared.error.ApiException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.ZoneId;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Sales over a date range (development plan P6-02). */
@Service
public class SalesReportService {

    /**
     * The product breakdown is returned in full, not as a top ten.
     *
     * <p>A truncated list cannot add up to the report's own net total, and a breakdown that
     * refuses to reconcile with the figure above it is worse than no breakdown — somebody will
     * try to make the two agree and conclude the system is wrong. The screen shows the top few and
     * says how many there are; the data behind it is complete.
     *
     * <p>The cap is a safety rail against a catalogue nobody expected, not a feature. At this
     * system's size it is unreachable.
     */
    private static final int PRODUCT_LIMIT = 500;

    /**
     * A ceiling on the range, so one mistyped year cannot ask the database to group a decade.
     * Generous enough that no real question hits it.
     */
    private static final long MAX_RANGE_DAYS = 1096; // three years

    private final ReportingRepository repository;

    public SalesReportService(ReportingRepository repository) {
        this.repository = repository;
    }

    /**
     * @param from inclusive; defaults to 30 days back
     * @param to inclusive; defaults to today. Both are read as Sri Lankan calendar days.
     */
    @Transactional(readOnly = true)
    public SalesReport report(LocalDate from, LocalDate to) {
        LocalDate end = to != null ? to : LocalDate.now(ZoneId.of(ReportingRepository.REPORT_ZONE));
        LocalDate start = from != null ? from : end.minusDays(30);

        if (start.isAfter(end)) {
            throw new ApiException(
                    HttpStatus.BAD_REQUEST,
                    "INVALID_DATE_RANGE",
                    "The start of the range is after its end.");
        }
        if (start.plusDays(MAX_RANGE_DAYS).isBefore(end)) {
            throw new ApiException(
                            HttpStatus.BAD_REQUEST,
                            "DATE_RANGE_TOO_WIDE",
                            "Ask for at most three years at a time.")
                    .with("maxDays", MAX_RANGE_DAYS);
        }

        ReportingRepository.Totals totals = repository.salesTotals(start, end);

        // An empty range is an answer, not a failure: zero orders, zero value, empty lists. The
        // caller gets the same shape either way and never has to tell a quiet week from an error.
        return new SalesReport(
                start,
                end,
                totals.orderCount(),
                totals.gross(),
                totals.discount(),
                totals.net(),
                averageOf(totals),
                repository.salesByDay(start, end),
                repository.topProducts(start, end, PRODUCT_LIMIT));
    }

    private BigDecimal averageOf(ReportingRepository.Totals totals) {
        if (totals.orderCount() == 0) {
            return BigDecimal.ZERO;
        }
        return totals.net()
                .divide(BigDecimal.valueOf(totals.orderCount()), 2, RoundingMode.HALF_UP);
    }
}
