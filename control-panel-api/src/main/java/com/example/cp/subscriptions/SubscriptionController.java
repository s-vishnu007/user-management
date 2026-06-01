package com.example.cp.subscriptions;

import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1")
public class SubscriptionController {

    private final SubscriptionService subService;

    public SubscriptionController(SubscriptionService subService) {
        this.subService = subService;
    }

    @GetMapping("/orgs/{orgId}/subscriptions")
    @PreAuthorize("@subAccess.isOrgMember(#orgId) or hasAuthority('subscription.read')")
    public List<SubscriptionDto> listByOrg(@PathVariable UUID orgId) {
        return subService.listByOrg(orgId).stream().map(subService::toDto).toList();
    }

    @PostMapping("/orgs/{orgId}/subscriptions")
    @PreAuthorize("hasAuthority('subscription.write')")
    public ResponseEntity<SubscriptionDto> create(@PathVariable UUID orgId,
                                                  @Valid @RequestBody CreateSubscriptionRequest body) {
        Subscription s = subService.createSubscription(
                orgId, body.planId(),
                body.startsAt(), body.endsAt(),
                body.seats(), body.notes(),
                body.overrides()
        );
        return ResponseEntity.status(201).body(subService.toDto(s));
    }

    @GetMapping("/subscriptions/{id}")
    @PreAuthorize("@subAccess.canReadSubscription(#id)")
    public SubscriptionDto get(@PathVariable UUID id) {
        return subService.toDto(subService.get(id));
    }

    @PostMapping("/subscriptions/{id}/suspend")
    @PreAuthorize("hasAuthority('subscription.write')")
    public SubscriptionDto suspend(@PathVariable UUID id, @RequestBody(required = false) ReasonRequest body) {
        return subService.toDto(subService.suspend(id, body == null ? null : body.reason()));
    }

    @PostMapping("/subscriptions/{id}/reactivate")
    @PreAuthorize("hasAuthority('subscription.write')")
    public SubscriptionDto reactivate(@PathVariable UUID id) {
        return subService.toDto(subService.reactivate(id));
    }

    @PostMapping("/subscriptions/{id}/cancel")
    @PreAuthorize("hasAuthority('subscription.write')")
    public SubscriptionDto cancel(@PathVariable UUID id, @RequestBody(required = false) ReasonRequest body) {
        return subService.toDto(subService.cancel(id, body == null ? null : body.reason()));
    }

    @PostMapping("/subscriptions/{id}/overrides")
    @PreAuthorize("hasAuthority('subscription.write')")
    public SubscriptionDto.OverrideDto addOverride(@PathVariable UUID id,
                                                    @Valid @RequestBody SubscriptionService.OverrideRequest body) {
        SubscriptionOverride ov = subService.addOverride(id, body);
        Object v = body.value();
        return new SubscriptionDto.OverrideDto(ov.getId(), ov.getType().name(), ov.getKey(), v);
    }

    @DeleteMapping("/subscriptions/{id}/overrides/{overrideId}")
    @PreAuthorize("hasAuthority('subscription.write')")
    public ResponseEntity<Void> removeOverride(@PathVariable UUID id, @PathVariable UUID overrideId) {
        subService.removeOverride(id, overrideId);
        return ResponseEntity.noContent().build();
    }

    public record CreateSubscriptionRequest(
            UUID planId,
            OffsetDateTime startsAt,
            OffsetDateTime endsAt,
            Integer seats,
            String notes,
            List<SubscriptionService.OverrideRequest> overrides
    ) {}

    public record ReasonRequest(String reason) {}
}
