package com.democode.mlmsittu.inventory.internal.location;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface LocationRepository extends JpaRepository<Location, UUID> {

    Optional<Location> findByDefaultLocationTrue();

    Optional<Location> findByCode(String code);

    @Query("select l from Location l where l.active = true order by l.name asc")
    List<Location> findAllActiveOrdered();

    @Query("select l from Location l order by l.name asc")
    List<Location> findAllOrdered();
}
