package com.democode.mlmsittu.catalogue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.democode.mlmsittu.catalogue.internal.domain.Item;
import com.democode.mlmsittu.catalogue.internal.repo.ItemRepository;
import com.democode.mlmsittu.catalogue.internal.service.ItemProvisioningService;
import com.democode.mlmsittu.catalogue.internal.service.ItemProvisioningService.OpeningStock;
import com.democode.mlmsittu.catalogue.internal.service.ItemService.ItemDetails;
import com.democode.mlmsittu.identity.internal.domain.AppUser;
import com.democode.mlmsittu.identity.internal.domain.UserStatus;
import com.democode.mlmsittu.identity.internal.repo.AppUserRepository;
import com.democode.mlmsittu.inventory.api.LocationDirectory;
import com.democode.mlmsittu.shared.error.ApiException;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;

/**
 * Deleting an item is for mistakes only. One that was never stocked, sold or ordered goes cleanly;
 * one with any history is refused and left exactly as it was.
 */
@SpringBootTest
@ActiveProfiles("test")
@DisplayName("Item deletion")
class ItemDeletionTest {

    @Autowired private ItemProvisioningService provisioning;
    @Autowired private ItemRepository items;
    @Autowired private LocationDirectory locations;
    @Autowired private AppUserRepository users;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private JdbcTemplate jdbc;

    private UUID store;
    private UUID actor;

    @BeforeEach
    void setUp() {
        store = locations.defaultLocation().id();
        actor = newUser();
    }

    @Test
    @DisplayName("an item nobody has used is deleted, with the empty stock position it came with")
    void unusedItemDeletes() {
        UUID id = newItem(0);
        assertThat(stockRows(id)).isEqualTo(1);

        provisioning.deleteUnused(id);

        assertThat(items.findById(id)).isEmpty();
        assertThat(stockRows(id)).isZero();
    }

    @Test
    @DisplayName("an item with stock history is refused with ITEM_IN_USE and nothing changes")
    void stockedItemIsRefused() {
        UUID id = newItem(5);

        assertThatThrownBy(() -> provisioning.deleteUnused(id))
                .isInstanceOf(ApiException.class)
                .satisfies(
                        thrown ->
                                assertThat(((ApiException) thrown).getCode())
                                        .isEqualTo("ITEM_IN_USE"));

        assertThat(items.findById(id)).isPresent();
        assertThat(stockRows(id)).isEqualTo(1);
    }

    private long stockRows(UUID itemId) {
        Long count =
                jdbc.queryForObject(
                        "SELECT count(*) FROM stock_level WHERE item_id = ?", Long.class, itemId);
        return count == null ? 0 : count;
    }

    private UUID newItem(int openingQuantity) {
        Item item =
                provisioning.createWithOpeningStock(
                        "DEL-" + UUID.randomUUID(),
                        new ItemDetails(
                                "Deletion fixture",
                                null,
                                null,
                                new BigDecimal("10.00"),
                                new BigDecimal("20.00"),
                                null,
                                null,
                                0),
                        new OpeningStock(store, openingQuantity),
                        actor);
        return item.getId();
    }

    private UUID newUser() {
        AppUser user = new AppUser();
        user.setEmail("deletion-" + UUID.randomUUID() + "@test.local");
        user.setFullName("Deletion fixture");
        user.setPasswordHash(passwordEncoder.encode(UUID.randomUUID().toString()));
        user.setStatusValue(UserStatus.ACTIVE);
        return users.saveAndFlush(user).getId();
    }
}
