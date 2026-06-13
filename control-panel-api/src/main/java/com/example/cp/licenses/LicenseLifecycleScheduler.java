package com.example.cp.licenses;

import com.example.cp.subscriptions.OutboxPublisher;
import com.example.cp.subscriptions.Subscription;
import com.example.cp.subscriptions.SubscriptionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Scheduled license lifecycle sweeper.
 *
 * <ul>
 *   <li><b>Expiry transition:</b> moves {@code ACTIVE} tokens whose {@code expires_at} has passed to
 *       {@code EXPIRED} and emits a {@code license.expired} domain event per token.</li>
 *   <li><b>Expiry warning:</b> for {@code ACTIVE} tokens expiring within
 *       {@code app.licensing.expiry-warning} (default {@code P14D}) — but not yet expired — emits a
 *       {@code license.expiring} domain event so downstream notifiers can act. Each token warns at
 *       most once: a token already carrying a {@code license.expiring} outbox row is skipped.</li>
 * </ul>
 *
 * <p>Both events are enqueued via the subscription-scoped {@code subscriptionOutboxPublisher}. The
 * job runs on a fixed delay ({@code app.licensing.lifecycle.fixed-delay}, default 5m) and is a
 * no-op when there is nothing to do.
 */
@Component
public class LicenseLifecycleScheduler {

    private static final Logger log = LoggerFactory.getLogger(LicenseLifecycleScheduler.class);

    private final LicenseTokenRepository tokenRepo;
    private final SubscriptionRepository subRepo;
    private final OutboxPublisher outbox;
    private final Duration expiryWarning;

    /**
     * Self-reference (Spring proxy) so the {@code @Scheduled} entry point invokes the
     * {@code @Transactional} sweep methods THROUGH the proxy. A plain self-invocation would bypass
     * the transactional advice, so the expiry transition and its outbox inserts would not commit
     * atomically (audit P2 proxy-bypass). Injected {@code @Lazy} to break the self-cycle, mirroring
     * {@code AuditWriter}.
     */
    private final LicenseLifecycleScheduler self;

    public LicenseLifecycleScheduler(LicenseTokenRepository tokenRepo,
                                     SubscriptionRepository subRepo,
                                     OutboxPublisher outbox,
                                     @Lazy LicenseLifecycleScheduler self,
                                     @Value("${app.licensing.expiry-warning:P14D}") Duration expiryWarning) {
        this.tokenRepo = tokenRepo;
        this.subRepo = subRepo;
        this.outbox = outbox;
        this.self = self;
        this.expiryWarning = expiryWarning;
    }

    @Scheduled(
            fixedDelayString = "${app.licensing.lifecycle.fixed-delay:PT5M}",
            initialDelayString = "${app.licensing.lifecycle.initial-delay:PT1M}")
    public void sweep() {
        try {
            // Route through the proxy so the @Transactional boundaries actually apply.
            int expired = self.expirePastDue();
            int warned = self.warnExpiring();
            if (expired > 0 || warned > 0) {
                log.info("License lifecycle sweep: expired={} expiring-warned={}", expired, warned);
            }
        } catch (RuntimeException e) {
            log.error("License lifecycle sweep failed", e);
        }
    }

    /**
     * Transitions ACTIVE tokens past their expiry to EXPIRED and emits one {@code license.expired}
     * event each. The transition and the outbox inserts run in a SINGLE transaction so they commit
     * atomically. The payloads are gathered before the set-based UPDATE; the UPDATE itself is one
     * guarded statement so concurrent sweeps cannot double-transition (audit P2).
     */
    @Transactional
    public int expirePastDue() {
        OffsetDateTime now = OffsetDateTime.now();
        // Snapshot the overdue tokens first (for the per-token event payloads) ...
        List<LicenseToken> overdue =
                tokenRepo.findByStatusAndExpiresAtLessThanEqual(LicenseToken.Status.ACTIVE, now);
        if (overdue.isEmpty()) {
            return 0;
        }
        // ... then transition them atomically in one guarded set-based UPDATE.
        int transitioned = tokenRepo.markExpiredPastDue(
                now, LicenseToken.Status.ACTIVE, LicenseToken.Status.EXPIRED);
        for (LicenseToken token : overdue) {
            outbox.publish("license_token", token.getJti(), "license.expired",
                    eventPayload(token, now));
        }
        return transitioned;
    }

    /**
     * Emits a {@code license.expiring} warning for ACTIVE tokens expiring within the warning window
     * that have not already been warned. Dedup is durable: each token's warning is CLAIMED via a
     * conditional UPDATE on {@code expiring_warned_at} (NULL -> now), so a token is warned at most
     * once even across concurrent/duplicate sweeps and after the outbox is purged (audit P2). Does
     * NOT change token status.
     */
    @Transactional
    public int warnExpiring() {
        OffsetDateTime now = OffsetDateTime.now();
        OffsetDateTime windowEnd = now.plus(expiryWarning);
        List<LicenseToken> expiring = tokenRepo
                .findExpiringNotYetWarned(LicenseToken.Status.ACTIVE, now, windowEnd);
        int warned = 0;
        for (LicenseToken token : expiring) {
            // Claim the warning atomically; only the winner emits the event.
            if (tokenRepo.claimExpiringWarning(token.getJti(), now) == 0) {
                continue;
            }
            outbox.publish("license_token", token.getJti(), "license.expiring",
                    eventPayload(token, now));
            warned++;
        }
        return warned;
    }

    private Map<String, Object> eventPayload(LicenseToken token, OffsetDateTime now) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("jti", token.getJti());
        payload.put("subscription_id", token.getSubscriptionId().toString());
        Subscription sub = subRepo.findById(token.getSubscriptionId()).orElse(null);
        if (sub != null) {
            payload.put("org_id", sub.getOrgId().toString());
        }
        payload.put("license_type", token.getLicenseType().name());
        if (token.getExpiresAt() != null) {
            payload.put("expires_at", token.getExpiresAt().toString());
        }
        payload.put("evaluated_at", now.toString());
        return payload;
    }
}
