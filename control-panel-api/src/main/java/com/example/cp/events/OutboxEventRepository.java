package com.example.cp.events;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@Repository
public interface OutboxEventRepository extends JpaRepository<OutboxEvent, UUID> {

    @Query("""
            SELECT e FROM OutboxEvent e
            WHERE (:since IS NULL OR e.occurredAt >= :since)
            ORDER BY e.occurredAt ASC
            """)
    List<OutboxEvent> findSince(@Param("since") OffsetDateTime since);
}
