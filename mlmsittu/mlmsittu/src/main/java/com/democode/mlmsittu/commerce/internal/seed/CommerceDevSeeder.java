package com.democode.mlmsittu.commerce.internal.seed;

import com.democode.mlmsittu.commerce.internal.domain.Supplier;
import com.democode.mlmsittu.commerce.internal.repo.SupplierRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** One active supplier, plus a deactivated one so P2-06's guard can be tested without setup. */
@Component
@Profile("seed")
@Order(30)
public class CommerceDevSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(CommerceDevSeeder.class);

    private final SupplierRepository suppliers;

    public CommerceDevSeeder(SupplierRepository suppliers) {
        this.suppliers = suppliers;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        int created = 0;
        created += seed("SUP-001", "Lanka Distributors (Pvt) Ltd", "Ruwan Perera", true);
        created += seed("SUP-002", "Former Supplier Ltd", "Nadeeka Silva", false);
        log.info("Seed: commerce — {} suppliers created", created);
    }

    private int seed(String code, String name, String contact, boolean active) {
        if (suppliers.findByCode(code).isPresent()) {
            return 0;
        }
        Supplier supplier = new Supplier();
        supplier.setCode(code);
        supplier.setName(name);
        supplier.setContactName(contact);
        supplier.setEmail(code.toLowerCase() + "@example.lk");
        supplier.setPhone("+94112000000");
        supplier.setAddress("Colombo, Sri Lanka");
        supplier.setActive(active);
        suppliers.save(supplier);
        return 1;
    }
}
