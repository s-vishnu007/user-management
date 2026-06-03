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

    // Native query with explicit casts: in JPQL a bare nullable parameter used as `:from IS NULL`
    // gives Postgres no type to infer ("could not determine data type of parameter"). CAST(... AS
    // timestamptz) fixes it while keeping the optional-range semantics.
    @Query(value = """
            SELECT * FROM usage_events
            WHERE subscription_id = :subId
              AND (CAST(:from AS timestamptz) IS NULL OR occurred_at >= :from)
              AND (CAST(:to   AS timestamptz) IS NULL OR occurred_at <  :to)
            ORDER BY occurred_at DESC
            """, nativeQuery = true)
    List<UsageEvent> findInRange(@Param("subId") UUID subscriptionId,
                                 @Param("from") OffsetDateTime from,
                                 @Param("to") OffsetDateTime to);

    boolean existsBySubscriptionIdAndJtiAndEventId(UUID subscriptionId, String jti, String eventId);
}
