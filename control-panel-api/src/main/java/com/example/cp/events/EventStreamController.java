package com.example.cp.events;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/events")
public class EventStreamController {

    private final OutboxEventRepository repo;

    public EventStreamController(OutboxEventRepository repo) {
        this.repo = repo;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('event.read')")
    public List<EventDto> list(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime since) {
        return repo.findSince(since).stream().map(EventDto::from).toList();
    }

    public record EventDto(UUID id, String aggregateType, String aggregateId, String eventType, String payloadJson, OffsetDateTime occurredAt, OffsetDateTime publishedAt) {
        static EventDto from(OutboxEvent e) {
            return new EventDto(e.getId(), e.getAggregateType(), e.getAggregateId(), e.getEventType(), e.getPayloadJson(), e.getOccurredAt(), e.getPublishedAt());
        }
    }
}
