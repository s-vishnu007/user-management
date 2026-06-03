package com.example.cp.audit;

import com.example.cp.common.Ids;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Types;
import java.util.Map;
import java.util.UUID;

@Component
public class AuditWriter {

    private static final Logger log = LoggerFactory.getLogger(AuditWriter.class);

    private static final String INSERT_SQL = """
            INSERT INTO audit_log (id, actor_user_id, actor_org_id, action, target_type, target_id, payload_json, ip_address, occurred_at, outcome)
            VALUES (?, ?, ?, ?, ?, ?, ?::jsonb, ?::inet, now(), ?)
            """;

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper = new ObjectMapper();

    /**
     * Self-reference used so the fail-open path goes through the Spring proxy and the
     * {@code REQUIRES_NEW} propagation actually takes effect (self-invocation would bypass it).
     */
    private final AuditWriter self;

    public AuditWriter(JdbcTemplate jdbc, @Lazy AuditWriter self) {
        this.jdbc = jdbc;
        this.self = self;
    }

    /**
     * Legacy 7-arg signature, kept as a thin delegate so existing callers compile unchanged.
     * Records a fail-open SUCCESS row.
     */
    public void record(UUID actorUserId, UUID actorOrgId, String action, String targetType, String targetId,
                       Map<String, Object> payload, String ip) {
        record(actorUserId, actorOrgId, action, targetType, targetId, payload, ip, AuditOutcome.SUCCESS, false);
    }

    /**
     * Canonical record method.
     *
     * <p>When {@code failClosed} is {@code true} the INSERT runs INLINE in the caller's
     * transaction with no REQUIRES_NEW and no try/catch — any failure propagates and rolls back
     * the surrounding business transaction (atomic coupling for high-value actions).</p>
     *
     * <p>When {@code failClosed} is {@code false} the INSERT runs in a separate
     * {@code REQUIRES_NEW} transaction and failures are swallowed (best-effort forensics that
     * survives a business rollback).</p>
     */
    public void record(UUID actorUserId, UUID actorOrgId, String action, String targetType, String targetId,
                       Map<String, Object> payload, String ip, AuditOutcome outcome, boolean failClosed) {
        if (action == null || action.isBlank()) {
            return;
        }
        AuditOutcome effectiveOutcome = outcome == null ? AuditOutcome.SUCCESS : outcome;
        String payloadJson = serialize(action, payload);
        if (failClosed) {
            // Inline under the caller's transaction; exceptions propagate.
            insert(actorUserId, actorOrgId, action, targetType, targetId, payloadJson, ip, effectiveOutcome);
        } else {
            // Separate REQUIRES_NEW tx, best-effort; route through the proxy so propagation applies.
            self.writeFailOpen(actorUserId, actorOrgId, action, targetType, targetId, payloadJson, ip, effectiveOutcome);
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void writeFailOpen(UUID actorUserId, UUID actorOrgId, String action, String targetType, String targetId,
                              String payloadJson, String ip, AuditOutcome outcome) {
        try {
            insert(actorUserId, actorOrgId, action, targetType, targetId, payloadJson, ip, outcome);
        } catch (Exception e) {
            log.error("Failed to write audit_log row for action={}: {}", action, e.getMessage());
        }
    }

    private String serialize(String action, Map<String, Object> payload) {
        if (payload == null || payload.isEmpty()) {
            return null;
        }
        try {
            return mapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            log.warn("Failed to serialize audit payload for action={}: {}", action, e.getMessage());
            return null;
        }
    }

    private void insert(UUID actorUserId, UUID actorOrgId, String action, String targetType, String targetId,
                        String payloadJson, String ip, AuditOutcome outcome) {
        jdbc.update(connection -> {
            var ps = connection.prepareStatement(INSERT_SQL);
            ps.setObject(1, Ids.newId());
            if (actorUserId != null) ps.setObject(2, actorUserId); else ps.setNull(2, Types.OTHER);
            if (actorOrgId != null) ps.setObject(3, actorOrgId); else ps.setNull(3, Types.OTHER);
            ps.setString(4, action);
            ps.setString(5, targetType);
            ps.setString(6, targetId);
            ps.setString(7, payloadJson);
            ps.setString(8, ip);
            ps.setString(9, outcome.name());
            return ps;
        });
    }
}
