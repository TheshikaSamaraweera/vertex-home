package com.democode.mlmsittu.shared;

import static org.assertj.core.api.Assertions.assertThat;

import com.democode.mlmsittu.catalogue.internal.domain.Item;
import com.democode.mlmsittu.catalogue.internal.repo.ItemRepository;
import com.democode.mlmsittu.catalogue.internal.service.ItemService;
import com.democode.mlmsittu.commerce.internal.domain.Customer;
import com.democode.mlmsittu.commerce.internal.domain.SalesOrder;
import com.democode.mlmsittu.commerce.internal.service.CustomerService;
import com.democode.mlmsittu.commerce.internal.service.SalesOrderService;
import com.democode.mlmsittu.commerce.internal.service.SalesOrderService.LineRequest;
import com.democode.mlmsittu.commerce.internal.service.SalesOrderService.OrderRequest;
import com.democode.mlmsittu.identity.internal.domain.AppUser;
import com.democode.mlmsittu.identity.internal.domain.UserStatus;
import com.democode.mlmsittu.identity.internal.repo.AppUserRepository;
import com.democode.mlmsittu.inventory.api.LocationDirectory;
import com.democode.mlmsittu.inventory.api.StockLedger;
import com.democode.mlmsittu.inventory.api.StockPosting;
import com.democode.mlmsittu.inventory.internal.web.InventoryController;
import com.democode.mlmsittu.inventory.internal.web.dto.InventoryDtos.StockByItemResponse;
import com.democode.mlmsittu.shared.api.Cursor;
import com.democode.mlmsittu.shared.api.PagedResponse;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;

/**
 * The list screens read one page at a time, so each paged list has to walk its rows exactly once
 * — and a search has to narrow the whole list, not just whichever page happens to be showing.
 *
 * <p>The test database keeps rows between runs, so every walk here is confined to rows this test
 * made: a unique name tag for name-ordered lists, a unique timestamp for time-ordered ones.
 */
@SpringBootTest
@ActiveProfiles("test")
@DisplayName("List pagination")
class ListPaginationTest {

    @Autowired private ItemService itemService;
    @Autowired private ItemRepository items;
    @Autowired private CustomerService customers;
    @Autowired private SalesOrderService orders;
    @Autowired private InventoryController inventory;
    @Autowired private StockLedger ledger;
    @Autowired private LocationDirectory locations;
    @Autowired private AppUserRepository users;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private JdbcTemplate jdbc;

    private UUID locationId;
    private UUID actor;

    @BeforeEach
    void setUp() {
        locationId = locations.defaultLocation().id();
        actor = newUser();
    }

    @Test
    @DisplayName("an item search pages through every match and nothing else")
    void itemSearchNarrowsEveryPage() {
        String tag = unique();
        Set<UUID> created = new LinkedHashSet<>();
        for (int i = 0; i < 5; i++) {
            created.add(newItem(tag + " " + i, 3));
        }

        Set<UUID> seen = new LinkedHashSet<>();
        Cursor cursor = null;
        for (int guard = 0; guard < 10; guard++) {
            List<Item> fetched = itemService.page(true, tag, cursor, 2);
            List<Item> rows = fetched.size() > 2 ? fetched.subList(0, 2) : fetched;
            rows.forEach(item -> assertThat(seen.add(item.getId())).isTrue());
            if (fetched.size() <= 2) {
                break;
            }
            Item last = rows.get(rows.size() - 1);
            cursor = new Cursor(last.getName(), last.getId());
        }

        assertThat(seen).containsExactlyElementsOf(created);
    }

    @Test
    @DisplayName("a customer search pages by name and stops when the matches run out")
    void customerSearchPages() {
        String tag = unique();
        List<UUID> created = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            created.add(newCustomer(tag + " " + i));
        }

        List<Customer> first = customers.page(tag, false, null, 3);
        assertThat(first).hasSize(3);

        Customer last = first.get(1);
        List<Customer> second =
                customers.page(tag, false, new Cursor(last.getName(), last.getId()), 3);

        assertThat(second).extracting(Customer::getId).containsExactly(created.get(2));
    }

    @Test
    @DisplayName("orders placed in the same instant still page exactly once each")
    void ordersWithTiedTimestampsPageExactly() {
        UUID itemId = newItem(unique(), 50);
        UUID customerId = newCustomer(unique());

        List<UUID> created = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            created.add(
                    orders.create(
                                    new OrderRequest(
                                            customerId,
                                            locationId,
                                            List.of(new LineRequest(itemId, null, 1, null)),
                                            null,
                                            null,
                                            null),
                                    actor)
                            .getId());
        }

        // All five in one instant, in the past so nothing else in the database sorts among them.
        // A keyset on created_at alone would repeat or drop rows at every boundary inside a tie.
        Instant tie =
                Instant.parse("1990-01-01T00:00:00Z")
                        .plusSeconds(ThreadLocalRandom.current().nextLong(0, 300_000_000));
        for (UUID id : created) {
            jdbc.update(
                    "UPDATE sales_order SET created_at = ? WHERE id = ?", Timestamp.from(tie), id);
        }

        // Start just after the tie, so the walk sees exactly this block and then older rows.
        Cursor cursor = new Cursor(tie.plusNanos(1_000).toString(), new UUID(0L, 0L));
        Set<UUID> seen = new LinkedHashSet<>();
        for (int guard = 0; guard < 10; guard++) {
            List<SalesOrder> fetched = orders.page(null, null, cursor, 3);
            List<SalesOrder> rows = fetched.size() > 2 ? fetched.subList(0, 2) : fetched;
            List<SalesOrder> mine =
                    rows.stream().filter(order -> order.getCreatedAt().equals(tie)).toList();
            mine.forEach(order -> assertThat(seen.add(order.getId())).isTrue());
            if (mine.size() < rows.size() || fetched.size() <= 2) {
                break;
            }
            SalesOrder last = rows.get(rows.size() - 1);
            cursor = new Cursor(last.getCreatedAt().toString(), last.getId());
        }

        assertThat(seen).containsExactlyInAnyOrderElementsOf(created);
    }

    @Test
    @WithMockUser(roles = "STAFF")
    @DisplayName("stock by item pages over the catalogue and totals each item whole")
    void stockByItemPages() {
        String tag = unique();
        UUID a = newItem(tag + " a", 4);
        UUID b = newItem(tag + " b", 7);
        UUID c = newItem(tag + " c", 9);

        PagedResponse<StockByItemResponse> first = inventory.listStockByItem(tag, null, null, 2);
        assertThat(first.data()).extracting(StockByItemResponse::itemId).containsExactly(a, b);
        assertThat(first.data()).extracting(StockByItemResponse::onHand).containsExactly(4, 7);
        assertThat(first.nextCursor()).isNotNull();

        PagedResponse<StockByItemResponse> second =
                inventory.listStockByItem(tag, null, first.nextCursor(), 2);
        assertThat(second.data()).extracting(StockByItemResponse::itemId).containsExactly(c);
        assertThat(second.data().get(0).onHand()).isEqualTo(9);
        assertThat(second.nextCursor()).isNull();
    }

    @Test
    @DisplayName("a category filter narrows the catalogue page to that category")
    void categoryFilterNarrows() {
        String tag = unique();
        UUID chairs =
                jdbc.queryForObject(
                        "INSERT INTO category (code, name) VALUES (?, ?) RETURNING id",
                        UUID.class,
                        tag,
                        tag + " chairs");
        UUID inCategory = newItem(tag + " in", 1);
        newItem(tag + " out", 1);
        jdbc.update("UPDATE item SET category_id = ? WHERE id = ?", chairs, inCategory);

        assertThat(itemService.page(true, tag, chairs, null, 10))
                .extracting(Item::getId)
                .containsExactly(inCategory);
        assertThat(itemService.page(true, tag, null, null, 10)).hasSize(2);
    }

    @Test
    @DisplayName("an order search finds orders by buyer name and by order number")
    void orderSearchMatchesBuyerAndNumber() {
        String tag = unique();
        UUID itemId = newItem(tag, 10);
        UUID buyer = newCustomer(tag + " buyer");
        SalesOrder order =
                orders.create(
                        new OrderRequest(
                                buyer,
                                locationId,
                                List.of(new LineRequest(itemId, null, 1, null)),
                                null,
                                null,
                                null),
                        actor);

        assertThat(orders.page(null, tag, null, 10))
                .extracting(SalesOrder::getId)
                .containsExactly(order.getId());
        assertThat(orders.page(null, order.getOrderNumber(), null, 10))
                .extracting(SalesOrder::getId)
                .contains(order.getId());
        assertThat(orders.page("cancelled", tag, null, 10)).isEmpty();
    }

    // ==============================================================================
    // Fixtures
    // ==============================================================================

    private UUID newItem(String name, int openingStock) {
        Item item = new Item();
        item.setSku("PAGE-" + UUID.randomUUID());
        item.setName(name);
        item.setUnitCost(BigDecimal.ONE);
        item.setSellingPrice(new BigDecimal("100.00"));
        item.setReorderLevel(0);
        item.setActive(true);
        UUID itemId = items.saveAndFlush(item).getId();
        ledger.post(StockPosting.openingBalance(itemId, locationId, openingStock, actor));
        return itemId;
    }

    private UUID newCustomer(String name) {
        return customers
                .create(
                        "CUS-" + UUID.randomUUID(),
                        new CustomerService.CustomerDetails(
                                name, null, null, null, "Colombo", null, null),
                        actor)
                .getId();
    }

    private UUID newUser() {
        AppUser user = new AppUser();
        user.setEmail("paging-" + UUID.randomUUID() + "@test.local");
        user.setFullName("Pagination fixture");
        user.setPasswordHash(passwordEncoder.encode(UUID.randomUUID().toString()));
        user.setStatusValue(UserStatus.ACTIVE);
        return users.saveAndFlush(user).getId();
    }

    private static String unique() {
        return "PG-" + UUID.randomUUID().toString().substring(0, 8);
    }
}
