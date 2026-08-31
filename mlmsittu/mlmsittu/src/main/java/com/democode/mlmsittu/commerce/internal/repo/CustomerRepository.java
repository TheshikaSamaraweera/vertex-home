package com.democode.mlmsittu.commerce.internal.repo;

import com.democode.mlmsittu.commerce.internal.domain.Customer;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CustomerRepository extends JpaRepository<Customer, UUID> {

    Optional<Customer> findByCode(String code);

    @Query("select c from Customer c where c.active = true or :includeInactive = true order by c.name")
    List<Customer> findAllOrdered(@Param("includeInactive") boolean includeInactive);

    /**
     * Name or code search, case-insensitive.
     *
     * <p>Phase 6 loads five hundred of these into a table and Phase 7 paginates it. Until then the
     * filter runs in the database rather than the browser, which is the part that survives both.
     */
    @Query(
            """
            select c from Customer c
            where (:includeInactive = true or c.active = true)
              and (lower(c.name) like lower(concat('%', :term, '%'))
                   or lower(c.code) like lower(concat('%', :term, '%')))
            order by c.name
            """)
    List<Customer> search(@Param("term") String term, @Param("includeInactive") boolean includeInactive);
}
