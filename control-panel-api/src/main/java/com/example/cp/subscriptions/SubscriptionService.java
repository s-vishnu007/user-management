package com.example.cp.subscriptions;

import com.example.cp.common.ApiException;
import com.example.cp.common.AuditContext;
import com.example.cp.common.Ids;
import com.example.cp.common.SecurityUtils;
import com.example.cp.licenses.LicenseRevocationService;
import com.example.cp.orgs.Organization;
import com.example.cp.orgs.OrganizationRepository;
import com.example.cp.plans.Plan;
import com.example.cp.plans.PlanRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
public class SubscriptionService {

    /**
     * Allowed subscription status transitions (P2 state machine). CANCELLED is terminal: it has no
     * outgoing transitions, so {@code cancel -> suspend -> reactivate} can no longer resurrect a
     * cancelled subscription. EXPIRED (set by the lifecycle sweeper) is also terminal. Any transition
     * not listed here is rejected with 409.
     */
    private static final Map<Subscription.Status, Set<Subscription.Status>> ALLOWED_TRANSITIONS;
    static {
        EnumMap<Subscription.Status, Set<Subscription.Status>> m = new EnumMap<>(Subscription.Status.class);
        m.put(Subscription.Status.ACTIVE,
                EnumSet.of(Subscription.Status.SUSPENDED, Subscription.Status.CANCELLED));
        m.put(Subscription.Status.SUSPENDED,
                EnumSet.of(Subscription.Status.ACTIVE, Subscription.Status.CANCELLED));
        m.put(Subscription.Status.CANCELLED, EnumSet.noneOf(Subscription.Status.class));
        m.put(Subscription.Status.EXPIRED, EnumSet.noneOf(Subscription.Status.class));
        ALLOWED_TRANSITIONS = m;
    }

    private final SubscriptionRepository subRepo;
    private final SubscriptionOverrideRepository overrideRepo;
    private final OrganizationRepository orgRepo;
    private final PlanRepository planRepo;
    private final OutboxPublisher outbox;
    private final LicenseRevocationService revocationService;
    private final ObjectMapper objectMapper;

    public SubscriptionService(SubscriptionRepository subRepo,
                               SubscriptionOverrideRepository overrideRepo,
                               OrganizationRepository orgRepo,
                               PlanRepository planRepo,
                               OutboxPublisher outbox,
                               LicenseRevocationService revocationService,
                               ObjectMapper objectMapper) {
        this.subRepo = subRepo;
        this.overrideRepo = overrideRepo;
        this.orgRepo = orgRepo;
        this.planRepo = planRepo;
        this.outbox = outbox;
        this.revocationService = revocationService;
        this.objectMapper = objectMapper;
    }

    /**
     * Validates a status transition against {@link #ALLOWED_TRANSITIONS}, throwing 409 on an illegal
     * move. A no-op transition (same status) is allowed and treated idempotently by callers.
     */
    private void assertTransitionAllowed(Subscription.Status from, Subscription.Status to) {
        if (from == to) {
            return;
        }
        Set<Subscription.Status> allowed = ALLOWED_TRANSITIONS.getOrDefault(from, Set.of());
        if (!allowed.contains(to)) {
            throw ApiException.conflict(
                    "Illegal subscription transition: " + from + " -> " + to);
        }
    }

    @Transactional
    public Subscription createSubscription(UUID orgId, UUID planId,
                                            OffsetDateTime startsAt, OffsetDateTime endsAt,
                                            Integer seats, String notes,
                                            List<OverrideRequest> overrides) {
        Organization org = orgRepo.findById(orgId)
                .orElseThrow(() -> ApiException.notFound("Organization not found"));
        Plan plan = planRepo.findById(planId)
                .orElseThrow(() -> ApiException.notFound("Plan not found"));
        if (!plan.isActive()) {
            throw ApiException.badRequest("Plan is not active");
        }
        OffsetDateTime now = OffsetDateTime.now();
        OffsetDateTime starts = startsAt == null ? now : startsAt;
        OffsetDateTime ends = endsAt == null ? starts.plusDays(plan.getDefaultTtlDays()) : endsAt;
        if (!ends.isAfter(starts)) {
            throw ApiException.badRequest("ends_at must be after starts_at");
        }
        UUID actorId = SecurityUtils.currentUser().map(u -> u.userId()).orElse(null);
        Subscription s = Subscription.builder()
                .id(Ids.newId())
                .orgId(org.getId())
                .planId(plan.getId())
                .status(Subscription.Status.ACTIVE)
                .startsAt(starts)
                .endsAt(ends)
                .seats(seats)
                .notes(notes)
                .createdBy(actorId)
                .createdAt(now)
                .build();
        Subscription saved = subRepo.save(s);

        if (overrides != null) {
            for (OverrideRequest or : overrides) {
                persistOverride(saved.getId(), or);
            }
        }

        AuditContext.set("subscription.created");
        AuditContext.setTarget("subscription", saved.getId().toString());
        AuditContext.putPayload("org_id", orgId.toString());
        AuditContext.putPayload("plan_code", plan.getCode());

        outbox.publish("subscription", saved.getId().toString(), "SubscriptionActivated",
                Map.of(
                        "subscription_id", saved.getId().toString(),
                        "org_id", saved.getOrgId().toString(),
                        "plan_id", saved.getPlanId().toString(),
                        "plan_code", plan.getCode(),
                        "starts_at", saved.getStartsAt().toString(),
                        "ends_at", saved.getEndsAt().toString(),
                        "seats", saved.getSeats() == null ? 0 : saved.getSeats()
                )
        );

        return saved;
    }

    @Transactional
    public Subscription suspend(UUID id, String reason) {
        Subscription s = get(id);
        assertTransitionAllowed(s.getStatus(), Subscription.Status.SUSPENDED);
        s.setStatus(Subscription.Status.SUSPENDED);
        Subscription saved = subRepo.save(s);

        // P1-5: cascade revocation to all ACTIVE licenses of this subscription so suspending a
        // customer actually invalidates their offline licenses (they land on the CRL) instead of
        // leaving them valid + seat-holding until natural expiry.
        UUID actorId = SecurityUtils.currentUser().map(u -> u.userId()).orElse(null);
        int revoked = revocationService.revokeAllActiveForSubscription(
                id, "subscription suspended" + (reason == null ? "" : ": " + reason), actorId);

        AuditContext.set("subscription.suspended");
        AuditContext.setTarget("subscription", id.toString());
        if (reason != null) AuditContext.putPayload("reason", reason);
        AuditContext.putPayload("licenses_revoked", revoked);
        outbox.publish("subscription", id.toString(), "SubscriptionSuspended",
                Map.of("subscription_id", id.toString(), "reason", reason == null ? "" : reason));
        return saved;
    }

    @Transactional
    public Subscription reactivate(UUID id) {
        Subscription s = get(id);
        // CANCELLED/EXPIRED are terminal: assertTransitionAllowed rejects reactivation (409).
        assertTransitionAllowed(s.getStatus(), Subscription.Status.ACTIVE);
        s.setStatus(Subscription.Status.ACTIVE);
        Subscription saved = subRepo.save(s);
        AuditContext.set("subscription.reactivated");
        AuditContext.setTarget("subscription", id.toString());
        outbox.publish("subscription", id.toString(), "SubscriptionReactivated",
                Map.of("subscription_id", id.toString()));
        return saved;
    }

    @Transactional
    public Subscription cancel(UUID id, String reason) {
        Subscription s = get(id);
        assertTransitionAllowed(s.getStatus(), Subscription.Status.CANCELLED);
        s.setStatus(Subscription.Status.CANCELLED);
        Subscription saved = subRepo.save(s);

        // P1-5: cancelling likewise cascades revocation to the subscription's ACTIVE licenses.
        UUID actorId = SecurityUtils.currentUser().map(u -> u.userId()).orElse(null);
        int revoked = revocationService.revokeAllActiveForSubscription(
                id, "subscription cancelled" + (reason == null ? "" : ": " + reason), actorId);

        AuditContext.set("subscription.cancelled");
        AuditContext.setTarget("subscription", id.toString());
        if (reason != null) AuditContext.putPayload("reason", reason);
        AuditContext.putPayload("licenses_revoked", revoked);
        outbox.publish("subscription", id.toString(), "SubscriptionCancelled",
                Map.of("subscription_id", id.toString(), "reason", reason == null ? "" : reason));
        return saved;
    }

    @Transactional(readOnly = true)
    public Subscription get(UUID id) {
        return subRepo.findById(id).orElseThrow(() -> ApiException.notFound("Subscription not found"));
    }

    @Transactional(readOnly = true)
    public List<Subscription> listByOrg(UUID orgId) {
        return subRepo.findByOrgId(orgId);
    }

    @Transactional(readOnly = true)
    public List<SubscriptionOverride> listOverrides(UUID subscriptionId) {
        return overrideRepo.findBySubscriptionId(subscriptionId);
    }

    @Transactional
    public SubscriptionOverride addOverride(UUID subscriptionId, OverrideRequest req) {
        get(subscriptionId);
        SubscriptionOverride saved = persistOverride(subscriptionId, req);
        AuditContext.set("subscription.override.added");
        AuditContext.setTarget("subscription", subscriptionId.toString());
        AuditContext.putPayload("override_id", saved.getId().toString());
        return saved;
    }

    @Transactional
    public void removeOverride(UUID subscriptionId, UUID overrideId) {
        SubscriptionOverride ov = overrideRepo.findById(overrideId)
                .orElseThrow(() -> ApiException.notFound("Override not found"));
        if (!ov.getSubscriptionId().equals(subscriptionId)) {
            throw ApiException.badRequest("Override does not belong to that subscription");
        }
        overrideRepo.delete(ov);
        AuditContext.set("subscription.override.removed");
        AuditContext.setTarget("subscription", subscriptionId.toString());
        AuditContext.putPayload("override_id", overrideId.toString());
    }

    @Transactional(readOnly = true)
    public SubscriptionDto toDto(Subscription s) {
        Plan plan = planRepo.findById(s.getPlanId()).orElse(null);
        List<SubscriptionOverride> ovs = overrideRepo.findBySubscriptionId(s.getId());
        List<SubscriptionDto.OverrideDto> overrideDtos = new ArrayList<>(ovs.size());
        for (SubscriptionOverride ov : ovs) {
            Object v = parseValue(ov.getValueJson());
            overrideDtos.add(new SubscriptionDto.OverrideDto(ov.getId(), ov.getType().name(), ov.getKey(), v));
        }
        return new SubscriptionDto(
                s.getId(), s.getOrgId(), s.getPlanId(),
                plan == null ? null : plan.getCode(),
                s.getStatus().name(),
                s.getStartsAt(), s.getEndsAt(),
                s.getSeats(), s.getNotes(),
                s.getCreatedBy(), s.getCreatedAt(),
                overrideDtos
        );
    }

    private SubscriptionOverride persistOverride(UUID subscriptionId, OverrideRequest req) {
        SubscriptionOverride.Type t;
        try {
            t = SubscriptionOverride.Type.valueOf(req.type().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest("Invalid override type: " + req.type());
        }
        if (req.key() == null || req.key().isBlank()) {
            throw ApiException.badRequest("Override key is required");
        }
        String json;
        try {
            json = objectMapper.writeValueAsString(req.value());
        } catch (JsonProcessingException e) {
            throw ApiException.badRequest("Invalid override value");
        }
        // Upsert on the (subscription, type, key) unique key (P3): a repeated override for the same
        // key updates the existing row's value rather than inserting a duplicate (which the new
        // UNIQUE constraint would reject) — keeping entitlement resolution deterministic.
        SubscriptionOverride ov = overrideRepo
                .findBySubscriptionIdAndTypeAndKey(subscriptionId, t, req.key())
                .orElseGet(() -> SubscriptionOverride.builder()
                        .id(Ids.newId())
                        .subscriptionId(subscriptionId)
                        .type(t)
                        .key(req.key())
                        .build());
        ov.setValueJson(json);
        return overrideRepo.save(ov);
    }

    private Object parseValue(String json) {
        if (json == null) return null;
        try {
            return objectMapper.readValue(json, Object.class);
        } catch (Exception e) {
            return json;
        }
    }

    /**
     * Builds the merged permissions + features view from plan + overrides for the JWT issuer.
     */
    @Transactional(readOnly = true)
    public ResolvedEntitlements resolveEntitlements(UUID subscriptionId, List<String> planPermissions, Map<String, Object> planFeatures) {
        // Start with plan baseline
        java.util.LinkedHashSet<String> perms = new java.util.LinkedHashSet<>(planPermissions);
        Map<String, Object> features = new LinkedHashMap<>(planFeatures);

        // Deterministic order (type, key) so resolution is independent of row insertion order (P3).
        for (SubscriptionOverride ov : overrideRepo.findBySubscriptionIdOrderByTypeAscKeyAsc(subscriptionId)) {
            Object val = parseValue(ov.getValueJson());
            switch (ov.getType()) {
                case PERMISSION -> {
                    boolean grant = isTruthy(val);
                    if (grant) perms.add(ov.getKey());
                    else perms.remove(ov.getKey());
                }
                case FEATURE -> features.put(ov.getKey(), val);
            }
        }
        return new ResolvedEntitlements(new ArrayList<>(perms), features);
    }

    private boolean isTruthy(Object val) {
        if (val == null) return true; // absence treated as grant
        if (val instanceof Boolean b) return b;
        if (val instanceof Number n) return n.intValue() != 0;
        if (val instanceof String s) {
            String t = s.trim().toLowerCase();
            return !(t.equals("false") || t.equals("0") || t.equals("no") || t.equals("revoke") || t.equals("deny"));
        }
        return true;
    }

    public record OverrideRequest(String type, String key, Object value) {}

    public record ResolvedEntitlements(List<String> permissions, Map<String, Object> features) {}
}
