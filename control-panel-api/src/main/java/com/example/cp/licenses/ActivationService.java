package com.example.cp.licenses;

import com.example.cp.common.ApiException;
import com.example.cp.common.AuditContext;
import com.example.cp.common.Ids;
import com.example.cp.plans.PlanService;
import com.example.cp.subscriptions.Subscription;
import com.example.cp.subscriptions.SubscriptionRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Heartbeat / lease + seat-counting service backing the licensing phone-home.
 *
 * <p>A heartbeat does two things atomically:
 * <ol>
 *   <li>refreshes {@code last_seen_at}/{@code last_seen_ip} on the {@link LicenseToken} (so the
 *       control panel knows the license is alive); and</li>
 *   <li>upserts a {@link LicenseActivation} row keyed by {@code (jti, node_id)} so every distinct
 *       node/seat is tracked with its own {@code first_seen_at}/{@code last_seen_at}.</li>
 * </ol>
 *
 * <p><b>Seat enforcement.</b> A seat is "active" when its activation's {@code last_seen_at} is within
 * the configurable lease window ({@code app.licensing.lease-window}, default {@code PT24H}). When a
 * <em>new</em> node beats in and the count of active seats already meets/exceeds the license's seat
 * limit, the activation is rejected with {@code 409 Conflict} (over-limit). Heartbeats from an
 * already-known node always succeed (they renew the lease, never grow the count). When no seat limit
 * is configured (null/&lt;=0) activation is unlimited.
 */
@Service
public class ActivationService {

    private final LicenseTokenRepository tokenRepo;
    private final LicenseActivationRepository activationRepo;
    private final SubscriptionRepository subRepo;
    private final PlanService planService;
    private final Duration leaseWindow;

    public ActivationService(LicenseTokenRepository tokenRepo,
                             LicenseActivationRepository activationRepo,
                             SubscriptionRepository subRepo,
                             PlanService planService,
                             @Value("${app.licensing.lease-window:PT24H}") Duration leaseWindow) {
        this.tokenRepo = tokenRepo;
        this.activationRepo = activationRepo;
        this.subRepo = subRepo;
        this.planService = planService;
        this.leaseWindow = leaseWindow;
    }

    /**
     * Records a heartbeat for {@code (jti, nodeId)}: refreshes the token's last-seen, upserts the
     * activation row, enforces the seat limit for NEW nodes, and returns the resulting lease state.
     *
     * @throws ApiException notFound when the jti is unknown, badRequest when the license is not
     *         ACTIVE or nodeId is blank, conflict (409) when a new node would exceed the seat limit.
     */
    @Transactional
    public HeartbeatResult heartbeat(String jti, String nodeId, String clientIp) {
        if (nodeId == null || nodeId.isBlank()) {
            throw ApiException.badRequest("node_id is required");
        }
        String node = nodeId.trim();

        LicenseToken token = tokenRepo.findByJti(jti)
                .orElseThrow(() -> ApiException.notFound("License not found"));

        OffsetDateTime now = OffsetDateTime.now();
        if (token.getStatus() == LicenseToken.Status.REVOKED) {
            throw ApiException.badRequest("License is revoked");
        }
        if (token.getStatus() == LicenseToken.Status.EXPIRED
                || (token.getExpiresAt() != null && !token.getExpiresAt().isAfter(now))) {
            throw ApiException.badRequest("License is expired");
        }

        OffsetDateTime leaseFloor = now.minus(leaseWindow);
        Optional<LicenseActivation> existing = activationRepo.findByJtiAndNodeId(jti, node);

        if (existing.isEmpty()) {
            // New node: enforce the seat limit against the currently-active seat count.
            Integer seatLimit = resolveSeatLimit(token.getSubscriptionId());
            if (seatLimit != null && seatLimit > 0) {
                long activeSeats = activationRepo.countByJtiAndLastSeenAtAfter(jti, leaseFloor);
                if (activeSeats >= seatLimit) {
                    AuditContext.set("license.activation.over_limit");
                    AuditContext.setTarget("license_token", jti);
                    AuditContext.putPayload("node_id", node);
                    AuditContext.putPayload("seat_limit", seatLimit);
                    AuditContext.putPayload("active_seats", activeSeats);
                    throw ApiException.conflict(
                            "Seat limit reached (" + activeSeats + "/" + seatLimit + " active seats)");
                }
            }
            LicenseActivation activation = LicenseActivation.builder()
                    .id(Ids.newId())
                    .jti(jti)
                    .nodeId(node)
                    .firstSeenAt(now)
                    .lastSeenAt(now)
                    .lastSeenIp(clientIp)
                    .build();
            try {
                activationRepo.saveAndFlush(activation);
            } catch (DataIntegrityViolationException raceLost) {
                // Concurrent first beat for the same node: fall back to an update of the row the
                // other request inserted (idempotent renew, never a new seat).
                LicenseActivation race = activationRepo.findByJtiAndNodeId(jti, node)
                        .orElseThrow(() -> raceLost);
                race.setLastSeenAt(now);
                race.setLastSeenIp(clientIp);
                activationRepo.save(race);
            }
        } else {
            LicenseActivation activation = existing.get();
            activation.setLastSeenAt(now);
            activation.setLastSeenIp(clientIp);
            activationRepo.save(activation);
        }

        token.setLastSeenAt(now);
        token.setLastSeenIp(clientIp);
        tokenRepo.save(token);

        long activeSeats = activationRepo.countByJtiAndLastSeenAtAfter(jti, leaseFloor);
        Integer seatLimit = resolveSeatLimit(token.getSubscriptionId());

        AuditContext.set("license.heartbeat");
        AuditContext.setTarget("license_token", jti);
        AuditContext.putPayload("node_id", node);
        AuditContext.putPayload("active_seats", activeSeats);

        return new HeartbeatResult(jti, node, now, activeSeats, seatLimit,
                token.getLicenseType().name(), token.getExpiresAt());
    }

    /** Current count of active seats (activations seen within the lease window) for a license. */
    @Transactional(readOnly = true)
    public long activeSeatCount(String jti) {
        OffsetDateTime leaseFloor = OffsetDateTime.now().minus(leaseWindow);
        return activationRepo.countByJtiAndLastSeenAtAfter(jti, leaseFloor);
    }

    /** All activations for a license, most-recent first. */
    @Transactional(readOnly = true)
    public List<LicenseActivation> listActivations(String jti) {
        return activationRepo.findByJtiOrderByLastSeenAtDesc(jti);
    }

    /** Activations currently holding a seat (last seen within the lease window). */
    @Transactional(readOnly = true)
    public List<LicenseActivation> listActiveActivations(String jti) {
        OffsetDateTime leaseFloor = OffsetDateTime.now().minus(leaseWindow);
        return activationRepo.findByJtiAndLastSeenAtAfterOrderByLastSeenAtDesc(jti, leaseFloor);
    }

    public Duration getLeaseWindow() {
        return leaseWindow;
    }

    /**
     * Seat limit for a subscription: the explicit {@code subscriptions.seats} column when set,
     * otherwise the plan feature {@code seats} if numeric, otherwise {@code null} (unlimited).
     */
    Integer resolveSeatLimit(UUID subscriptionId) {
        Subscription sub = subRepo.findById(subscriptionId).orElse(null);
        if (sub == null) return null;
        if (sub.getSeats() != null && sub.getSeats() > 0) {
            return sub.getSeats();
        }
        try {
            Object feature = planService.getFeatures(sub.getPlanId()).get("seats");
            if (feature instanceof Number n) {
                return n.intValue();
            }
            if (feature instanceof String s && !s.isBlank()) {
                return Integer.parseInt(s.trim());
            }
        } catch (RuntimeException ignored) {
            // Non-numeric / absent seats feature => treat as unlimited.
        }
        return null;
    }

    public record HeartbeatResult(
            String jti,
            String nodeId,
            OffsetDateTime lastSeenAt,
            long activeSeats,
            Integer seatLimit,
            String licenseType,
            OffsetDateTime expiresAt
    ) {}
}
