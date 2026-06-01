package com.example.cp.audit;

import com.example.cp.common.Ids;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Types;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

@Component
public class AuditWriter {

    private static final Logger log = LoggerFactory.getLogger(AuditWriter.class);

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper = new ObjectMapper();

    public AuditWriter(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(UUID actorUserId, UUID actorOrgId, String action, String targetType, String targetId,
                       Map<String, Object> payload, String ip) {
        if (action == null || action.isBlank()) {
            return;
        }
        String payloadJson = null;
        if (payload != null && !payload.isEmpty()) {
            try {
                payloadJson = mapper.writeValueAsString(payload);
            } catch (JsonProcessingException e) {
                log.warn("Failed to serialize audit payload for action={}: {}", action, e.getMessage());
            }
        }
        final String payloadJsonFinal = payloadJson;
        try {
            jdbc.update(connection -> {
                var ps = connection.prepareStatement("""
                        INSERT INTO audit_log (id, actor_user_id, actor_org_id, action, target_type, target_id, payload_json, ip_address, occurred_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?::jsonb, ?::inet, ?)
                        """);
                ps.setObject(1, Ids.newId());
                if (actorUserId != null) ps.setObject(2, actorUserId); else ps.setNull(2, Types.OTHER);
                if (actorOrgId != null) ps.setObject(3, actorOrgId); else ps.setNull(3, Types.OTHER);
                ps.setString(4, action);
                ps.setString(5, targetType);
                ps.setString(6, targetId);
                ps.setString(7, payloadJsonFinal);
                ps.setString(8, ip);
                ps.setObject(9, OffsetDateTime.now());
                return ps;
            });
        } catch (Exception e) {
            log.error("Failed to write audit_log row for action={}: {}", action, e.getMessage());
        }
    }
}
