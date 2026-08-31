package com.democode.mlmsittu.catalogue.internal.repo;

import com.democode.mlmsittu.catalogue.internal.domain.Category;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface CategoryRepository extends JpaRepository<Category, UUID> {

    Optional<Category> findByCode(String code);

    boolean existsByCode(String code);

    @Query("select c from Category c order by c.name asc, c.id asc")
    List<Category> findAllOrdered();
}
