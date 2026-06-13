package com.example.cp.events;

import com.example.cp.common.PageRequestParams;
import org.springframework.data.domain.PageRequest;
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

    /**
     * Streams outbox events ordered by {@code occurred_at}. Server-side paginated: {@code page}/{@code
     * size} are clamped to sane bounds ({@code size} capped at {@link PageRequestParams#MAX_SIZE},
     * default {@link PageRequestParams#DEFAULT_SIZE}) so a single {@code event.read} call can never
     * pull the entire {@code outbox_events} table — the underlying query is always LIMITed. The native
     * query carries its own {@code ORDER BY occurred_at}, so the Pageable is intentionally unsorted
     * (Spring Data does not inject a sort into native SQL).
     */
    @GetMapping
    @PreAuthorize("hasAuthority('event.read')")
    public List<EventDto> list(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime since,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        int p = page == null || page < 0 ? 0 : page;
        int s = size == null || size <= 0 ? PageRequestParams.DEFAULT_SIZE
                : Math.min(size, PageRequestParams.MAX_SIZE);
        PageRequest pageable = PageRequest.of(p, s);
        return repo.findSince(since, pageable).stream().map(EventDto::from).toList();
    }

    public record EventDto(UUID id, String aggregateType, String aggregateId, String eventType, String payloadJson, OffsetDateTime occurredAt, OffsetDateTime publishedAt) {
        static EventDto from(OutboxEvent e) {
            return new EventDto(e.getId(), e.getAggregateType(), e.getAggregateId(), e.getEventType(), e.getPayloadJson(), e.getOccurredAt(), e.getPublishedAt());
        }
    }
}
