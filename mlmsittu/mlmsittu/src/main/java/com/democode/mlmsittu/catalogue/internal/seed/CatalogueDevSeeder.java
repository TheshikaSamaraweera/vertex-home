package com.democode.mlmsittu.catalogue.internal.seed;

import com.democode.mlmsittu.catalogue.internal.domain.Category;
import com.democode.mlmsittu.catalogue.internal.domain.Item;
import com.democode.mlmsittu.catalogue.internal.domain.ItemSet;
import com.democode.mlmsittu.catalogue.internal.domain.ItemSetLine;
import com.democode.mlmsittu.catalogue.internal.repo.CategoryRepository;
import com.democode.mlmsittu.catalogue.internal.repo.ItemRepository;
import com.democode.mlmsittu.catalogue.internal.repo.ItemSetRepository;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Twenty items across four categories (development plan, "Seed data").
 *
 * <p>{@code SLV-001} is the designated <b>shared component</b>: Phase 3 puts it in two different
 * item sets on purpose, so there is a permanent fixture for the overlapping-set contention that
 * §4.2 warns about. It is seeded with 100 units where everything else gets 50, per the plan.
 *
 * <p>Runs after the identity seeder — items need no user, but keeping the order explicit means the
 * log reads in a sensible sequence and later seeders can depend on earlier ones.
 */
@Component
@Profile("seed")
@Order(20)
public class CatalogueDevSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(CatalogueDevSeeder.class);

    /** The item Phase 3 will place in two overlapping sets. */
    public static final String SHARED_COMPONENT_SKU = "SLV-001";

    private record SeedItem(
            String sku, String name, String categoryCode, String cost, String price, int reorder) {}

    private static final Map<String, String> CATEGORIES =
            Map.of(
                    "BEVERAGES", "Beverages",
                    "PERSONAL_CARE", "Personal Care",
                    "HOUSEHOLD", "Household",
                    "WELLNESS", "Wellness");

    private static final List<SeedItem> ITEMS =
            List.of(
                    new SeedItem(SHARED_COMPONENT_SKU, "Ceylon Black Tea 200g", "BEVERAGES", "420.00", "690.00", 20),
                    new SeedItem("SLV-002", "Ceylon Green Tea 200g", "BEVERAGES", "460.00", "750.00", 20),
                    new SeedItem("SLV-003", "Cinnamon Tea 100g", "BEVERAGES", "380.00", "620.00", 15),
                    new SeedItem("SLV-004", "Ginger Tea 100g", "BEVERAGES", "350.00", "580.00", 15),
                    new SeedItem("SLV-005", "Instant Coffee 100g", "BEVERAGES", "540.00", "880.00", 10),

                    new SeedItem("SLV-006", "Herbal Soap 100g", "PERSONAL_CARE", "120.00", "220.00", 30),
                    new SeedItem("SLV-007", "Coconut Shampoo 250ml", "PERSONAL_CARE", "460.00", "790.00", 20),
                    new SeedItem("SLV-008", "Aloe Face Wash 100ml", "PERSONAL_CARE", "390.00", "660.00", 20),
                    new SeedItem("SLV-009", "Herbal Toothpaste 100g", "PERSONAL_CARE", "210.00", "360.00", 25),
                    new SeedItem("SLV-010", "Body Lotion 200ml", "PERSONAL_CARE", "520.00", "870.00", 15),

                    new SeedItem("SLV-011", "Dish Wash Liquid 500ml", "HOUSEHOLD", "280.00", "470.00", 25),
                    new SeedItem("SLV-012", "Floor Cleaner 1L", "HOUSEHOLD", "410.00", "690.00", 20),
                    new SeedItem("SLV-013", "Laundry Powder 1kg", "HOUSEHOLD", "620.00", "1010.00", 15),
                    new SeedItem("SLV-014", "Glass Cleaner 500ml", "HOUSEHOLD", "310.00", "520.00", 10),
                    new SeedItem("SLV-015", "Air Freshener 300ml", "HOUSEHOLD", "340.00", "570.00", 10),

                    new SeedItem("SLV-016", "Moringa Capsules 60s", "WELLNESS", "780.00", "1290.00", 12),
                    new SeedItem("SLV-017", "Turmeric Capsules 60s", "WELLNESS", "740.00", "1220.00", 12),
                    new SeedItem("SLV-018", "Honey 500g", "WELLNESS", "890.00", "1450.00", 10),
                    new SeedItem("SLV-019", "Coconut Oil 400ml", "WELLNESS", "520.00", "860.00", 15),
                    new SeedItem("SLV-020", "Multivitamin 30s", "WELLNESS", "980.00", "1590.00", 8));

    private final CategoryRepository categories;
    private final ItemRepository items;
    private final ItemSetRepository sets;

    public CatalogueDevSeeder(
            CategoryRepository categories, ItemRepository items, ItemSetRepository sets) {
        this.categories = categories;
        this.items = items;
        this.sets = sets;
    }

    /**
     * Three sets, where <b>SET-A and SET-B deliberately share {@code SLV-001}</b> — the permanent
     * contention fixture the development plan asks for. SET-C overlaps with nothing, so there is
     * always a clean control to compare against.
     */
    private record SeedSet(String code, String name, String price, Map<String, Integer> components) {}

    private static final List<SeedSet> SETS =
            List.of(
                    new SeedSet(
                            "SET-A",
                            "Morning Essentials",
                            "2190.00",
                            Map.of("SLV-001", 2, "SLV-006", 1, "SLV-009", 1)),
                    new SeedSet(
                            "SET-B",
                            "Tea Lover Pack",
                            "2790.00",
                            Map.of("SLV-001", 3, "SLV-002", 1)),
                    new SeedSet(
                            "SET-C",
                            "Home Care Bundle",
                            "2090.00",
                            Map.of("SLV-011", 1, "SLV-012", 1, "SLV-013", 1)));

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        Map<String, UUID> categoryIds = seedCategories();

        int created = 0;
        for (SeedItem seed : ITEMS) {
            if (items.existsBySku(seed.sku())) {
                continue;
            }
            Item item = new Item();
            item.setSku(seed.sku());
            item.setName(seed.name());
            item.setCategoryId(categoryIds.get(seed.categoryCode()));
            item.setUnitCost(new BigDecimal(seed.cost()));
            item.setSellingPrice(new BigDecimal(seed.price()));
            item.setReorderLevel(seed.reorder());
            item.setActive(true);
            items.save(item);
            created++;
        }

        int setsCreated = seedSets();

        log.info(
                "Seed: catalogue — {} categories, {} items created ({} already present), {} sets"
                    + " created",
                categoryIds.size(),
                created,
                ITEMS.size() - created,
                setsCreated);
    }

    private int seedSets() {
        Map<String, UUID> itemIdsBySku = new HashMap<>();
        items.findAllOrdered().forEach(item -> itemIdsBySku.put(item.getSku(), item.getId()));

        int created = 0;
        for (SeedSet seed : SETS) {
            if (sets.existsByCode(seed.code())) {
                continue;
            }

            ItemSet set = new ItemSet();
            set.setCode(seed.code());
            set.setName(seed.name());
            set.setSetPrice(new BigDecimal(seed.price()));
            set.setActive(true);

            seed.components()
                    .forEach(
                            (sku, quantity) -> {
                                UUID itemId = itemIdsBySku.get(sku);
                                if (itemId == null) {
                                    throw new IllegalStateException(
                                            "Seed set " + seed.code() + " references unknown SKU "
                                                    + sku);
                                }
                                ItemSetLine line = new ItemSetLine();
                                line.setItemId(itemId);
                                line.setQuantity(quantity);
                                set.getLines().add(line);
                            });

            sets.save(set);
            created++;
        }
        return created;
    }

    private Map<String, UUID> seedCategories() {
        CATEGORIES.forEach(
                (code, name) -> {
                    if (categories.existsByCode(code)) {
                        return;
                    }
                    Category category = new Category();
                    category.setCode(code);
                    category.setName(name);
                    categories.save(category);
                });

        return categories.findAllOrdered().stream()
                .collect(Collectors.toMap(Category::getCode, Category::getId, (a, b) -> a));
    }
}
