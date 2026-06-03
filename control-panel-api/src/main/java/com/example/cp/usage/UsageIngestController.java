package com.example.cp.usage;

import com.example.cp.common.AuditContext;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1")
public class UsageIngestController {

    private final UsageIngestService service;

    public UsageIngestController(UsageIngestService service) {
        this.service = service;
    }

    // Ingest is bound to the caller's org via the license jti: an API key (or user) for org A cannot
    // ingest usage against a jti that resolves to org B's subscription. usage.ingest scope required.
    @PostMapping("/usage/ingest")
    @PreAuthorize("hasAuthority('usage.ingest') and @tenantAccess.canIngestUsageForJti(#body.jti())")
    public ResponseEntity<IngestResponse> ingest(@Valid @RequestBody IngestRequest body) {
        List<UsageIngestService.IngestEvent> events = body.events().stream()
                .map(e -> new UsageIngestService.IngestEvent(
                        e.eventId(),
                        e.featureKey(),
                        e.quantity(),
                        e.occurredAt(),
                        e.metadata()))
                .toList();
        UsageIngestService.IngestResult result = service.ingest(body.jti(), events);
        AuditContext.set("usage.ingested");
        AuditContext.setTarget("subscription", result.subscriptionId().toString());
        AuditContext.putPayload("events_count", result.eventsAccepted());
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(new IngestResponse(result.eventsAccepted(), result.subscriptionId()));
    }

    @GetMapping("/subscriptions/{subId}/usage")
    @PreAuthorize("hasAuthority('subscription.read') or hasAuthority('usage.read')")
    public UsageReport listUsage(@PathVariable UUID subId,
                                 @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime from,
                                 @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime to,
                                 @RequestParam(required = false) String featureKey) {
        List<UsageEvent> events = service.listEvents(subId, from, to);
        List<UsageQuota> quotas = service.getQuotaStatus(subId, featureKey);
        return new UsageReport(
                events.stream().map(UsageReport.EventDto::from).toList(),
                quotas.stream().map(UsageReport.QuotaDto::from).toList());
    }

    public record IngestRequest(
            @NotBlank String jti,
            @NotEmpty List<EventDto> events
    ) {}

    public record EventDto(
            String eventId,
            @NotBlank String featureKey,
            @NotNull(message = "quantity is required")
            @DecimalMin(value = "0", inclusive = false, message = "quantity must be greater than 0")
            BigDecimal quantity,
            OffsetDateTime occurredAt,
            Map<String, Object> metadata
    ) {}

    public record IngestResponse(int eventsAccepted, UUID subscriptionId) {}

    public record UsageReport(List<EventDto> events, List<QuotaDto> quotas) {
        public record EventDto(UUID id, String featureKey, BigDecimal quantity, OffsetDateTime occurredAt, String jti) {
            static EventDto from(UsageEvent e) {
                return new EventDto(e.getId(), e.getFeatureKey(), e.getQuantity(), e.getOccurredAt(), e.getJti());
            }
        }
        public record QuotaDto(String featureKey, OffsetDateTime periodStart, OffsetDateTime periodEnd, BigDecimal limit, BigDecimal consumed) {
            static QuotaDto from(UsageQuota q) {
                return new QuotaDto(q.getFeatureKey(), q.getPeriodStart(), q.getPeriodEnd(), q.getLimitValue(), q.getConsumedValue());
            }
        }
    }
}
