package com.democode.mlmsittu.inventory.internal.seed;

import com.democode.mlmsittu.catalogue.api.ItemCatalogue;
import com.democode.mlmsittu.catalogue.api.ItemRef;
import com.democode.mlmsittu.identity.api.UserDirectory;
import com.democode.mlmsittu.inventory.api.LocationDirectory;
import com.democode.mlmsittu.inventory.api.LocationDirectory.LocationRef;
import com.democode.mlmsittu.inventory.api.StockLedger;
import com.democode.mlmsittu.inventory.api.StockPosting;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Opening stock: 100 units of the shared component, 50 of everything else (development plan,
 * "Seed data").
 *
 * <p>Posted through {@link StockLedger} like any other movement, not inserted straight into
 * {@code stock_level}. Two reasons that matters: the seeded position is reconcilable like
 * everything else, and Gate 2's "ledger sum equals projection for every seeded item" would
 * otherwise fail on the very first check.
 *
 * <p>Runs last — it needs items from the catalogue seeder and a real user to attribute movements
 * to from the identity seeder.
 */
@Component
@Profile("seed")
@Order(40)
public class InventoryDevSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(InventoryDevSeeder.class);

    private static final String SHARED_COMPONENT_SKU = "SLV-001";
    private static final int SHARED_COMPONENT_QUANTITY = 100;
    private static final int DEFAULT_QUANTITY = 50;
    private static final String SEED_ACTOR_EMAIL = "inventory@mlmsittu.local";

    private final ItemCatalogue items;
    private final LocationDirectory locations;
    private final StockLedger ledger;
    private final UserDirectory users;

    public InventoryDevSeeder(
            ItemCatalogue items,
            LocationDirectory locations,
            StockLedger ledger,
            UserDirectory users) {
        this.items = items;
        this.locations = locations;
        this.ledger = ledger;
        this.users = users;
    }

    @Override
    public void run(ApplicationArguments args) {
        var actor = users.findByEmail(SEED_ACTOR_EMAIL);
        if (actor.isEmpty()) {
            log.warn(
                    "Seed: inventory skipped — no user {}. Has the identity seeder run?",
                    SEED_ACTOR_EMAIL);
            return;
        }

        LocationRef location = locations.defaultLocation();
        List<StockPosting> postings = new ArrayList<>();

        for (ItemRef item : items.findAll()) {
            // Idempotent: an item that already has a position is left exactly as it is, so running
            // the seed profile daily does not quietly inflate stock.
            boolean alreadyPositioned =
                    ledger.levelOf(item.id(), location.id())
                            .map(view -> view.onHand() != 0 || view.reserved() != 0)
                            .orElse(false);
            if (alreadyPositioned) {
                continue;
            }

            int quantity =
                    SHARED_COMPONENT_SKU.equals(item.sku())
                            ? SHARED_COMPONENT_QUANTITY
                            : DEFAULT_QUANTITY;

            postings.add(
                    StockPosting.openingBalance(
                            item.id(), location.id(), quantity, actor.get().id()));
        }

        if (postings.isEmpty()) {
            log.info("Seed: inventory — every item already has an opening position");
            return;
        }

        ledger.postAll(postings);
        log.info(
                "Seed: inventory — opening balances posted for {} items at {}",
                postings.size(),
                location.code());
    }
}
