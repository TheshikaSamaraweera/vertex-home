package com.democode.mlmsittu.commerce.internal.seed;

import com.democode.mlmsittu.commerce.internal.domain.Customer;
import com.democode.mlmsittu.commerce.internal.repo.CustomerRepository;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Customers to sell to, so Phase 5 is testable from a fresh database.
 *
 * <p>Orders are deliberately <b>not</b> seeded. Every Gate 5 check — reservation, duplicate bank
 * reference, idempotency, rejection, fulfilment, invoice numbering — is about what happens as an
 * order moves, and a pre-made order in some middle state would only make it harder to tell a
 * seeded row from one the tester created. The customers are the boring part; the flow is the part
 * worth doing by hand.
 */
@Component
@Profile("seed")
@Order(60)
public class SalesDevSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(SalesDevSeeder.class);

    private record SeedCustomer(String code, String name, String city, String phone) {}

    private static final List<SeedCustomer> CUSTOMERS =
            List.of(
                    new SeedCustomer("CUS-001", "Nimal Fernando", "Colombo", "+94771234567"),
                    new SeedCustomer("CUS-002", "Kumari Jayasinghe", "Kandy", "+94772345678"),
                    new SeedCustomer("CUS-003", "Sunil Wickramasinghe", "Galle", "+94773456789"),
                    new SeedCustomer("CUS-004", "Priya Rajapaksa", "Negombo", "+94774567890"));

    private final CustomerRepository customers;
    private final JdbcTemplate jdbc;

    public SalesDevSeeder(CustomerRepository customers, JdbcTemplate jdbc) {
        this.customers = customers;
        this.jdbc = jdbc;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        UUID creator = anyAdmin();
        if (creator == null) {
            log.warn("Seed: sales — no user to own the records, skipping customers");
            return;
        }

        int created = 0;
        for (SeedCustomer seed : CUSTOMERS) {
            if (customers.findByCode(seed.code()).isPresent()) {
                continue;
            }
            Customer customer = new Customer();
            customer.setCode(seed.code());
            customer.setName(seed.name());
            customer.setCity(seed.city());
            customer.setPhone(seed.phone());
            customer.setEmail(seed.code().toLowerCase() + "@example.lk");
            customer.setAddress(seed.city() + ", Sri Lanka");
            customer.setCreatedBy(creator);
            customers.save(customer);
            created++;
        }

        log.info(
                "Seed: sales — {} customers created ({} already present)",
                created,
                CUSTOMERS.size() - created);
    }

    /**
     * Any active account will do; {@code created_by} is NOT NULL and dev fixtures need an owner.
     * Raw SQL rather than the identity module, for the same reason the hierarchy seeder does it:
     * dev-only fixture code should not earn a module boundary failure it does not deserve.
     */
    private UUID anyAdmin() {
        return jdbc
                .query(
                        "SELECT id FROM app_user WHERE status = 'active' ORDER BY created_at LIMIT 1",
                        (rs, rowNum) -> rs.getObject(1, UUID.class))
                .stream()
                .findFirst()
                .orElse(null);
    }
}
