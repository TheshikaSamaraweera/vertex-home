package com.democode.mlmsittu.reporting.internal;

import com.democode.mlmsittu.reporting.api.CustomerAnalyticsRow;
import com.democode.mlmsittu.reporting.api.TreemapNode;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Customer table and treemap (development plan P6-03, P6-04). */
@Service
public class CustomerAnalyticsService {

    private final ReportingRepository repository;

    public CustomerAnalyticsService(ReportingRepository repository) {
        this.repository = repository;
    }

    /**
     * Every customer with their order history.
     *
     * <p>Deliberately unsorted beyond name and unpaginated: P6-03 asks for five hundred rows in one
     * response so the load time can be measured against Phase 7's cursor pagination. Sorting is the
     * client's job here because the whole set is already in its hands — once pagination lands, sort
     * moves to the server with it.
     */
    @Transactional(readOnly = true)
    public List<CustomerAnalyticsRow> customers(boolean includeInactive) {
        return repository.customerAnalytics(includeInactive);
    }

    /**
     * The treemap feed, nested category → customer → value (architecture §6.4).
     *
     * <p>Built here rather than in the browser because the nesting is a property of the data, not
     * of the drawing. The same tree feeds the table toggle and the CSV export, so all three agree
     * by construction.
     */
    @Transactional(readOnly = true)
    public TreemapNode treemap(LocalDate from, LocalDate to) {
        LocalDate end = to != null ? to : LocalDate.now(ZoneId.of(ReportingRepository.REPORT_ZONE));
        LocalDate start = from != null ? from : end.minusDays(90);

        // LinkedHashMap: the query already returns categories in name order and customers by
        // descending value within each. Preserving that means the largest rectangle in each block
        // is predictable rather than dependent on hash order.
        Map<String, List<TreemapNode>> byCategory = new LinkedHashMap<>();

        for (ReportingRepository.TreemapCell cell : repository.treemapCells(start, end)) {
            byCategory
                    .computeIfAbsent(cell.categoryName(), key -> new ArrayList<>())
                    .add(
                            TreemapNode.leaf(
                                    cell.customerName(),
                                    cell.value(),
                                    cell.daysSinceLastOrder()));
        }

        List<TreemapNode> categories =
                byCategory.entrySet().stream()
                        .map(entry -> TreemapNode.branch(entry.getKey(), entry.getValue()))
                        .toList();

        return TreemapNode.branch("Sales", categories);
    }
}
