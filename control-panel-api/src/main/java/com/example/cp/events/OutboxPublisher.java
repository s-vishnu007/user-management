package com.example.cp.events;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@Component("eventsOutboxPublisher")
public class OutboxPublisher {

    private static final Logger log = LoggerFactory.getLogger(OutboxPublisher.class);
    private static final int BATCH_SIZE = 100;
    private static final String CHANNEL = "cp_events";

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper = new ObjectMapper();

    public OutboxPublisher(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Scheduled(fixedDelay = 5000L)
    @Transactional
    public void publishBatch() {
        try {
            List<UnpublishedRow> rows = jdbc.query(
                    "SELECT id, aggregate_type, aggregate_id, event_type FROM outbox_events " +
                            "WHERE published_at IS NULL ORDER BY occurred_at ASC LIMIT " + BATCH_SIZE,
                    (rs, n) -> new UnpublishedRow(
                            rs.getObject("id", UUID.class),
                            rs.getString("aggregate_type"),
                            rs.getString("aggregate_id"),
                            rs.getString("event_type"))
            );
            if (rows.isEmpty()) return;
            OffsetDateTime now = OffsetDateTime.now();
            for (UnpublishedRow row : rows) {
                try {
                    notify(row);
                    jdbc.update("UPDATE outbox_events SET published_at = ? WHERE id = ?",
                            Timestamp.from(now.toInstant()), row.id);
                } catch (Exception ex) {
                    log.warn("Failed to publish outbox event id={} type={}: {}", row.id, row.eventType, ex.getMessage());
                }
            }
        } catch (Exception e) {
            log.error("Outbox publisher batch failed: {}", e.getMessage());
        }
    }

    private void notify(UnpublishedRow row) {
        ObjectNode payload = mapper.createObjectNode();
        payload.put("eventId", row.id.toString());
        payload.put("eventType", row.eventType);
        payload.put("aggregateType", row.aggregateType);
        payload.put("aggregateId", row.aggregateId);
        String json = payload.toString();
        // Parameterized pg_notify avoids SQL string interpolation of the channel/payload (no
        // injection surface, no manual quote-escaping). pg_notify(text, text) is the function form
        // of the NOTIFY statement.
        jdbc.update("SELECT pg_notify(?, ?)", CHANNEL, json);
    }

    private record UnpublishedRow(UUID id, String aggregateType, String aggregateId, String eventType) {}
}
