package com.democode.mlmsittu.rewards.internal.repo;

import com.democode.mlmsittu.rewards.internal.domain.RewardEntitlement;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RewardEntitlementRepository extends JpaRepository<RewardEntitlement, UUID> {

    Optional<RewardEntitlement> findByDistributorId(UUID distributorId);

    /**
     * Locked for the duration of an issue.
     *
     * <p>Two administrators issuing the same pack at once would otherwise both read {@code
     * eligible}, both post stock movements, and the distributor would receive two packs while the
     * row recorded one.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select e from RewardEntitlement e where e.id = :id")
    Optional<RewardEntitlement> lockForUpdate(@Param("id") UUID id);

    @Query("select e from RewardEntitlement e where e.status = :status order by e.becameEligibleAt asc")
    List<RewardEntitlement> findByStatus(@Param("status") String status);

    @Query("select e from RewardEntitlement e order by e.becameEligibleAt desc")
    List<RewardEntitlement> findAllNewestFirst();

    long countByStatus(String status);
}
