package com.democode.mlmsittu.commerce.internal.repo;

import com.democode.mlmsittu.commerce.internal.domain.Supplier;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface SupplierRepository extends JpaRepository<Supplier, UUID> {

    Optional<Supplier> findByCode(String code);

    @Query("select s from Supplier s where s.active = true order by s.name asc")
    List<Supplier> findAllActiveOrdered();

    @Query("select s from Supplier s order by s.name asc")
    List<Supplier> findAllOrdered();
}
