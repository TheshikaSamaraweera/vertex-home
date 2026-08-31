package com.democode.mlmsittu.commerce.internal.service;

import com.democode.mlmsittu.commerce.internal.domain.Customer;
import com.democode.mlmsittu.commerce.internal.repo.CustomerRepository;
import com.democode.mlmsittu.hierarchy.api.ReferralHierarchy;
import com.democode.mlmsittu.shared.audit.api.AuditContext;
import com.democode.mlmsittu.shared.audit.api.Audited;
import com.democode.mlmsittu.shared.error.ConflictException;
import com.democode.mlmsittu.shared.error.NotFoundException;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Customer records (development plan P5-01). */
@Service
public class CustomerService {

    private final CustomerRepository customers;
    private final ReferralHierarchy distributors;

    public CustomerService(CustomerRepository customers, ReferralHierarchy distributors) {
        this.customers = customers;
        this.distributors = distributors;
    }

    /** Everything a caller may set. Grouped so create and update cannot drift apart. */
    public record CustomerDetails(
            String name,
            String email,
            String phone,
            String address,
            String city,
            UUID distributorId,
            String note) {}

    @Transactional
    @Audited(action = "CUSTOMER_CREATED", entityType = "customer", auditFailures = true)
    public Customer create(String code, CustomerDetails details, UUID actorId) {
        Customer customer = new Customer();
        customer.setCode(code.trim().toUpperCase(Locale.ROOT));
        customer.setCreatedBy(actorId);
        apply(customer, details);

        try {
            Customer saved = customers.saveAndFlush(customer);
            AuditContext.record(saved.getId(), null, snapshot(saved));
            return saved;
        } catch (DataIntegrityViolationException e) {
            throw ConflictException.ifConstraintIs(
                    e,
                    "customer_code_key",
                    "DUPLICATE_CUSTOMER_CODE",
                    "A customer with that code already exists.");
        }
    }

    @Transactional
    @Audited(action = "CUSTOMER_UPDATED", entityType = "customer", auditFailures = true)
    public Customer update(UUID id, CustomerDetails details) {
        Customer customer = require(id);
        Map<String, Object> before = snapshot(customer);

        apply(customer, details);

        Customer saved = customers.save(customer);
        AuditContext.record(id, before, snapshot(saved));
        return saved;
    }

    /**
     * Deactivating stops new orders and nothing else. Orders already placed stay valid — a customer
     * you no longer sell to is not a customer who never bought anything.
     */
    @Transactional
    @Audited(action = "CUSTOMER_ACTIVATION_CHANGED", entityType = "customer", auditFailures = true)
    public Customer setActive(UUID id, boolean active) {
        Customer customer = require(id);
        Map<String, Object> before = snapshot(customer);
        customer.setActive(active);
        Customer saved = customers.save(customer);
        AuditContext.record(id, before, snapshot(saved));
        return saved;
    }

    @Transactional(readOnly = true)
    public List<Customer> list(String search, boolean includeInactive) {
        if (search == null || search.isBlank()) {
            return customers.findAllOrdered(includeInactive);
        }
        return customers.search(search.trim(), includeInactive);
    }

    @Transactional(readOnly = true)
    public Customer get(UUID id) {
        return require(id);
    }

    /** Used by order creation. A deactivated customer cannot be sold to. */
    @Transactional(readOnly = true)
    public Customer requireSelectable(UUID id) {
        Customer customer = require(id);
        if (!customer.isActive()) {
            throw new ConflictException(
                    "CUSTOMER_INACTIVE",
                    "That customer is deactivated and cannot be given a new order.");
        }
        return customer;
    }

    // ------------------------------------------------------------------ helpers

    private void apply(Customer customer, CustomerDetails details) {
        customer.setName(details.name().trim());
        customer.setEmail(blankToNull(details.email()));
        customer.setPhone(blankToNull(details.phone()));
        customer.setAddress(blankToNull(details.address()));
        customer.setCity(blankToNull(details.city()));
        customer.setNote(blankToNull(details.note()));

        if (details.distributorId() != null) {
            // Checked here rather than left to the foreign key, so a mistyped id is a 404 naming
            // the distributor instead of a 500 wrapping a constraint name. get() throws
            // DISTRIBUTOR_NOT_FOUND, which is exactly the answer wanted.
            distributors.get(details.distributorId());
        }
        customer.setDistributorId(details.distributorId());
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private Customer require(UUID id) {
        return customers
                .findById(id)
                .orElseThrow(
                        () ->
                                new NotFoundException(
                                        "CUSTOMER_NOT_FOUND", "No customer with that id."));
    }

    private Map<String, Object> snapshot(Customer customer) {
        // HashMap, not Map.of: the nullable fields would throw on a null value.
        Map<String, Object> snapshot = new HashMap<>();
        snapshot.put("code", customer.getCode());
        snapshot.put("name", customer.getName());
        snapshot.put("phone", customer.getPhone());
        snapshot.put("distributorId", customer.getDistributorId());
        snapshot.put("active", customer.isActive());
        return snapshot;
    }
}
