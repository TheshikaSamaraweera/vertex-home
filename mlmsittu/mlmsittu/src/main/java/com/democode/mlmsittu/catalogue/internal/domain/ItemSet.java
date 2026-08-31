package com.democode.mlmsittu.catalogue.internal.domain;

import com.democode.mlmsittu.catalogue.api.ItemSetRef;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToMany;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "item_set")
@Getter
@Setter
public class ItemSet {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "code", nullable = false, unique = true, length = 64)
    private String code;

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "description", length = 1000)
    private String description;

    @Column(name = "set_price", nullable = false, precision = 14, scale = 2)
    private BigDecimal setPrice;

    @Column(name = "is_active", nullable = false)
    private boolean active = true;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    @OneToMany(cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.EAGER)
    @JoinColumn(name = "set_id", nullable = false)
    private List<ItemSetLine> lines = new ArrayList<>();

    @PreUpdate
    void touch() {
        this.updatedAt = Instant.now();
    }

    public Map<UUID, Integer> componentMap() {
        Map<UUID, Integer> components = new LinkedHashMap<>();
        lines.forEach(line -> components.merge(line.getItemId(), line.getQuantity(), Integer::sum));
        return components;
    }

    public ItemSetRef toRef() {
        return new ItemSetRef(id, code, name, setPrice, active, componentMap());
    }
}
