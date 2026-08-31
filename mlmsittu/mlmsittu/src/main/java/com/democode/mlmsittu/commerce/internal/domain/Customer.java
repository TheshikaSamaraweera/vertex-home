package com.democode.mlmsittu.commerce.internal.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

/** Who is buying (P5-01). */
@Entity
@Table(name = "customer")
@Getter
@Setter
public class Customer {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "code", nullable = false, unique = true, length = 64)
    private String code;

    @Column(name = "name", nullable = false, length = 255)
    private String name;

    @Column(name = "email", length = 320)
    private String email;

    @Column(name = "phone", length = 32)
    private String phone;

    @Column(name = "address", length = 500)
    private String address;

    @Column(name = "city", length = 120)
    private String city;

    /**
     * The distributor who introduced them. Referenced by id — a cross-module JPA association is
     * prohibited (§1.3), and this is attribution data, not an ownership relationship.
     */
    @Column(name = "distributor_id")
    private UUID distributorId;

    @Column(name = "note", length = 1000)
    private String note;

    @Column(name = "is_active", nullable = false)
    private boolean active = true;

    @Column(name = "created_by", nullable = false, updatable = false)
    private UUID createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    @PreUpdate
    void touch() {
        this.updatedAt = Instant.now();
    }
}
