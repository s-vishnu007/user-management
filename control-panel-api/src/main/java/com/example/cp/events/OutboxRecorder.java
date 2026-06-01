package com.example.cp.events;

import com.example.cp.common.ApiException;
import com.example.cp.common.Ids;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

@Component
public class OutboxRecorder {

    private final OutboxEventRepository repo;
    private final ObjectMapper mapper = new ObjectMapper();

    public OutboxRecorder(OutboxEventRepository repo) {
        this.repo = repo;
    }

    @Transactional(propagation = Propagation.REQUIRED)
    public UUID record(String aggregateType, String aggregateId, String eventType, Map<String, Object> payload) {
        String json;
        try {
            json = mapper.writeValueAsString(payload == null ? Map.of() : payload);
        } catch (JsonProcessingException e) {
            throw ApiException.badRequest("Invalid outbox payload: " + e.getMessage());
        }
        OutboxEvent e = OutboxEvent.builder()
                .id(Ids.newId())
                .aggregateType(aggregateType)
                .aggregateId(aggregateId)
                .eventType(eventType)
                .payloadJson(json)
                .occurredAt(OffsetDateTime.now())
                .build();
        return repo.save(e).getId();
    }

    @Transactional
    public UUID recordInNewTx(String aggregateType, String aggregateId, String eventType, Map<String, Object> payload) {
        return record(aggregateType, aggregateId, eventType, payload);
    }
}
