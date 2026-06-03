package com.example.cp.usage;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@Repository
public interface UsageEventRepository extends JpaRepository<UsageEvent, UUID> {

    @Query("""
            SELECT e FROM UsageEvent e
            WHERE e.subscriptionId = :subId
              AND (:from IS NULL OR e.occurredAt >= :from)
              AND (:to IS NULL OR e.occurredAt < :to)
            ORDER BY e.occurredAt DESC
            """)
    List<UsageEvent> findInRange(@Param("subId") UUID subscriptionId,
                                 @Param("from") OffsetDateTime from,
                                 @Param("to") OffsetDateTime to);

    boolean existsBySubscriptionIdAndJtiAndEventId(UUID subscriptionId, String jti, String eventId);
}
