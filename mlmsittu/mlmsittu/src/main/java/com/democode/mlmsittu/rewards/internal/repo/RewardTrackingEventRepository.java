package com.democode.mlmsittu.rewards.internal.repo;

import com.democode.mlmsittu.rewards.internal.domain.RewardTrackingEvent;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RewardTrackingEventRepository extends JpaRepository<RewardTrackingEvent, UUID> {

    @Query(
            "select e from RewardTrackingEvent e where e.entitlementId = :entitlementId"
                    + " order by e.at asc")
    List<RewardTrackingEvent> history(@Param("entitlementId") UUID entitlementId);
}
