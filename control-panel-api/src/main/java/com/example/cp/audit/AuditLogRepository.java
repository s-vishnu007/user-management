package com.example.cp.audit;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.UUID;

@Repository
public interface AuditLogRepository extends JpaRepository<AuditLog, UUID> {

    @Query("""
            SELECT a FROM AuditLog a
            WHERE (:action IS NULL OR a.action = :action)
              AND (:actor IS NULL OR a.actorUserId = :actor)
              AND (:targetType IS NULL OR a.targetType = :targetType)
              AND (:targetId IS NULL OR a.targetId = :targetId)
              AND (:from IS NULL OR a.occurredAt >= :from)
              AND (:to IS NULL OR a.occurredAt < :to)
            """)
    Page<AuditLog> search(@Param("action") String action,
                          @Param("actor") UUID actor,
                          @Param("targetType") String targetType,
                          @Param("targetId") String targetId,
                          @Param("from") OffsetDateTime from,
                          @Param("to") OffsetDateTime to,
                          Pageable pageable);

    @Query("""
            SELECT a FROM AuditLog a
            WHERE a.actorOrgId = :orgId
              AND (:action IS NULL OR a.action = :action)
              AND (:actor IS NULL OR a.actorUserId = :actor)
              AND (:targetType IS NULL OR a.targetType = :targetType)
              AND (:targetId IS NULL OR a.targetId = :targetId)
              AND (:from IS NULL OR a.occurredAt >= :from)
              AND (:to IS NULL OR a.occurredAt < :to)
            """)
    Page<AuditLog> searchForOrg(@Param("orgId") UUID orgId,
                                @Param("action") String action,
                                @Param("actor") UUID actor,
                                @Param("targetType") String targetType,
                                @Param("targetId") String targetId,
                                @Param("from") OffsetDateTime from,
                                @Param("to") OffsetDateTime to,
                                Pageable pageable);
}
