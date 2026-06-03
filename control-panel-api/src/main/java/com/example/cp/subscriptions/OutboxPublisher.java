package com.example.cp.subscriptions;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Thin transactional-outbox helper used by subscription/license/key services
 * to enqueue domain events. Agent 7 owns the delivery / NOTIFY side.
 */
@Component("subscriptionOutboxPublisher")
public class OutboxPublisher {

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public OutboxPublisher(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    public void publish(String aggregateType, String aggregateId, String eventType, Map<String, Object> payload) {
        try {
            String json = objectMapper.writeValueAsString(payload == null ? Map.of() : payload);
            jdbc.update(
                    "INSERT INTO outbox_events (aggregate_type, aggregate_id, event_type, payload_json) " +
                            "VALUES (?, ?, ?, ?::jsonb)",
                    aggregateType, aggregateId, eventType, json
            );
        } catch (Exception e) {
            throw new RuntimeException("Failed to publish outbox event " + eventType, e);
        }
    }
}
