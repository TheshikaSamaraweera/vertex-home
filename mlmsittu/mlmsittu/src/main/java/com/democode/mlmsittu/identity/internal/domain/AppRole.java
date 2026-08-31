package com.democode.mlmsittu.identity.internal.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

/** One of the six administrative roles of architecture §8.1. Reference data, not user data. */
@Entity
@Table(name = "app_role")
@Getter
@Setter
public class AppRole {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    /** Stable identifier used in {@code @PreAuthorize}, e.g. {@code FINANCE_OFFICER}. */
    @Column(name = "code", nullable = false, unique = true, length = 48)
    private String code;

    @Column(name = "description", nullable = false)
    private String description;

    /** Holders must pass TOTP before a session is issued (Gate 1). */
    @Column(name = "requires_mfa", nullable = false)
    private boolean requiresMfa = true;
}
