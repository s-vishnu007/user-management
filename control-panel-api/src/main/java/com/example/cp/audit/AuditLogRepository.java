package com.example.cp.audit;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.UUID;

@Repository
public interface AuditLogRepository extends JpaRepository<AuditLog, UUID> {

    /**
     * GDPR Art. 17 erasure helper (NOT part of the general audit write path). Scrubs the PII-bearing,
     * free-form columns ({@code payload_json}, {@code ip_address}) from every {@code audit_log} row
     * authored by the erased subject, while leaving the identity/integrity columns (id, actor_user_id,
     * action, target, occurred_at, outcome) intact so the security trail and its tamper-evidence
     * survive. The replacement payload is a small PII-free marker.
     *
     * <p>The {@code audit_log} is otherwise immutable (a DB trigger rejects UPDATE/DELETE). This UPDATE
     * is permitted ONLY because the surrounding erasure transaction sets the session GUC
     * {@code app.audit_redaction = 'on'} (see {@code ErasureService}); the trigger additionally verifies
     * no identity column changes. The native UPDATE bypasses the {@code @Immutable} entity mapping
     * deliberately — this is the one sanctioned mutation of the trail.
     *
     * @return the number of audit rows redacted.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = "UPDATE audit_log "
            + "SET payload_json = '{\"redacted\":true,\"reason\":\"gdpr_erasure\"}'::jsonb, ip_address = NULL "
            + "WHERE actor_user_id = :actorId",
            nativeQuery = true)
    int redactPiiForActor(@Param("actorId") UUID actorId);

    // Native queries with explicit casts on the optional filters: in JPQL a bare nullable parameter
    // used as `:p IS NULL` gives Postgres no type to infer ("could not determine data type of
    // parameter"). ORDER BY is fixed here (occurred_at DESC), so callers MUST pass an UNSORTED
    // Pageable (a native query cannot translate a Sort on the entity property name to a column, and a
    // Pageable Sort would also append a second ORDER BY).
    String SEARCH_FILTER = """
              (CAST(:action AS text) IS NULL OR action = :action)
              AND (CAST(:actor AS uuid) IS NULL OR actor_user_id = :actor)
              AND (CAST(:targetType AS text) IS NULL OR target_type = :targetType)
              AND (CAST(:targetId AS text) IS NULL OR target_id = :targetId)
              AND (CAST(:from AS timestamptz) IS NULL OR occurred_at >= :from)
              AND (CAST(:to AS timestamptz) IS NULL OR occurred_at < :to)
            """;

    @Query(value = "SELECT * FROM audit_log WHERE " + SEARCH_FILTER + " ORDER BY occurred_at DESC",
            countQuery = "SELECT count(*) FROM audit_log WHERE " + SEARCH_FILTER,
            nativeQuery = true)
    Page<AuditLog> search(@Param("action") String action,
                          @Param("actor") UUID actor,
                          @Param("targetType") String targetType,
                          @Param("targetId") String targetId,
                          @Param("from") OffsetDateTime from,
                          @Param("to") OffsetDateTime to,
                          Pageable pageable);

    @Query(value = "SELECT * FROM audit_log WHERE actor_org_id = :orgId AND " + SEARCH_FILTER
                    + " ORDER BY occurred_at DESC",
            countQuery = "SELECT count(*) FROM audit_log WHERE actor_org_id = :orgId AND " + SEARCH_FILTER,
            nativeQuery = true)
    Page<AuditLog> searchForOrg(@Param("orgId") UUID orgId,
                                @Param("action") String action,
                                @Param("actor") UUID actor,
                                @Param("targetType") String targetType,
                                @Param("targetId") String targetId,
                                @Param("from") OffsetDateTime from,
                                @Param("to") OffsetDateTime to,
                                Pageable pageable);
}
