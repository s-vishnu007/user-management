package com.example.cp.usage;

import com.example.cp.common.AuditContext;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
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
        UsageIngestService.IngestResult result;
        try {
            result = service.ingest(body.jti(), events);
        } catch (UsageIngestService.DedupCollisionException collision) {
            // A concurrent request won the dedup race and already persisted these events (the @Transactional
            // ingest rolled back on this exception). Return idempotently with zero newly-accepted rather
            // than a raw 500 — no work is lost. (The events' eventIds are the idempotency keys.)
            result = new UsageIngestService.IngestResult(0, collision.subscriptionId);
        }
        AuditContext.set("usage.ingested");
        AuditContext.setTarget("subscription", result.subscriptionId().toString());
        AuditContext.putPayload("events_count", result.eventsAccepted());
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(new IngestResponse(result.eventsAccepted(), result.subscriptionId()));
    }

    // Tenant-scoped: the global subscription.read/usage.read authority is no longer a cross-org bypass.
    // @tenantAccess.canReadSubscription resolves the TARGET subscription's owning org and authorizes
    // against it (super-admin; api-key bound to that org; or an org member), mirroring LicenseController.list.
    @GetMapping("/subscriptions/{subId}/usage")
    @PreAuthorize("(hasAuthority('subscription.read') or hasAuthority('usage.read')) "
            + "and @tenantAccess.canReadSubscription(#subId)")
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
            // jti is the license id (license_tokens.jti VARCHAR(64)); cap to the column width so an
            // oversized value is a 400 here rather than a downstream DataIntegrityViolation -> 500.
            @NotBlank @Size(max = 64, message = "jti must be at most 64 characters") String jti,
            @NotEmpty List<@Valid EventDto> events
    ) {}

    public record EventDto(
            // event_id VARCHAR(120): bound the optional idempotency key to the column width (400, not 500).
            @Size(max = 120, message = "eventId must be at most 120 characters") String eventId,
            // feature_key VARCHAR(64): bound to the column width.
            @NotBlank @Size(max = 64, message = "featureKey must be at most 64 characters") String featureKey,
            // quantity NUMERIC: cap precision/scale so an absurd value is a 400, not a DB-cast 500.
            @NotNull(message = "quantity is required")
            @DecimalMin(value = "0", inclusive = false, message = "quantity must be greater than 0")
            @Digits(integer = 19, fraction = 6, message = "quantity has too many digits")
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
