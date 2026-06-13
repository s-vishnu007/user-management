package com.example.cp.events;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@Repository
public interface OutboxEventRepository extends JpaRepository<OutboxEvent, UUID> {

    // Native query with an explicit cast: a bare nullable parameter used as `:since IS NULL` in JPQL
    // gives Postgres no type to infer ("could not determine data type of parameter $1").
    //
    // The {@link Pageable} bounds the result set: Spring Data appends LIMIT/OFFSET (and the ORDER BY)
    // so the {@code event.read} feed can never pull the entire outbox_events table in one call. The
    // server enforces an upper bound on the page size in the controller (PageRequestParams.MAX_SIZE).
    @Query(value = """
            SELECT * FROM outbox_events
            WHERE (CAST(:since AS timestamptz) IS NULL OR occurred_at >= :since)
            ORDER BY occurred_at ASC
            """, nativeQuery = true)
    List<OutboxEvent> findSince(@Param("since") OffsetDateTime since, Pageable pageable);
}
