package com.democode.mlmsittu.catalogue.internal.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

/**
 * One component of a set.
 *
 * <p>Surrogate key rather than {@code (set_id, item_id)}: the parent owns {@code set_id} through
 * its {@code @JoinColumn}, so it cannot be insertable here, and a key column that is not
 * insertable gets written as NULL. See the note in {@code V9__item_sets.sql}.
 */
@Entity
@Table(name = "item_set_line")
@Getter
@Setter
public class ItemSetLine {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "item_id", nullable = false, updatable = false)
    private UUID itemId;

    @Column(name = "quantity", nullable = false)
    private int quantity;
}
