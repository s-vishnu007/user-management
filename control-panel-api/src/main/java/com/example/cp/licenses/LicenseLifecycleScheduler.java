package com.example.cp.licenses;

import com.example.cp.subscriptions.OutboxPublisher;
import com.example.cp.subscriptions.Subscription;
import com.example.cp.subscriptions.SubscriptionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
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
    private final JdbcTemplate jdbc;
    private final Duration expiryWarning;

    public LicenseLifecycleScheduler(LicenseTokenRepository tokenRepo,
                                     SubscriptionRepository subRepo,
                                     OutboxPublisher outbox,
                                     JdbcTemplate jdbc,
                                     @Value("${app.licensing.expiry-warning:P14D}") Duration expiryWarning) {
        this.tokenRepo = tokenRepo;
        this.subRepo = subRepo;
        this.outbox = outbox;
        this.jdbc = jdbc;
        this.expiryWarning = expiryWarning;
    }

    @Scheduled(
            fixedDelayString = "${app.licensing.lifecycle.fixed-delay:PT5M}",
            initialDelayString = "${app.licensing.lifecycle.initial-delay:PT1M}")
    public void sweep() {
        try {
            int expired = expirePastDue();
            int warned = warnExpiring();
            if (expired > 0 || warned > 0) {
                log.info("License lifecycle sweep: expired={} expiring-warned={}", expired, warned);
            }
        } catch (RuntimeException e) {
            log.error("License lifecycle sweep failed", e);
        }
    }

    /** Transitions ACTIVE tokens past their expiry to EXPIRED and emits a license.expired event each. */
    @Transactional
    public int expirePastDue() {
        OffsetDateTime now = OffsetDateTime.now();
        List<LicenseToken> overdue =
                tokenRepo.findByStatusAndExpiresAtLessThanEqual(LicenseToken.Status.ACTIVE, now);
        for (LicenseToken token : overdue) {
            token.setStatus(LicenseToken.Status.EXPIRED);
            tokenRepo.save(token);
            outbox.publish("license_token", token.getJti(), "license.expired",
                    eventPayload(token, now));
        }
        return overdue.size();
    }

    /**
     * Emits a license.expiring warning for ACTIVE tokens expiring within the warning window that
     * have not already been warned. Does NOT change token status.
     */
    @Transactional
    public int warnExpiring() {
        OffsetDateTime now = OffsetDateTime.now();
        OffsetDateTime windowEnd = now.plus(expiryWarning);
        List<LicenseToken> expiring = tokenRepo
                .findByStatusAndExpiresAtGreaterThanAndExpiresAtLessThanEqual(
                        LicenseToken.Status.ACTIVE, now, windowEnd);
        int warned = 0;
        for (LicenseToken token : expiring) {
            if (alreadyWarned(token.getJti())) {
                continue;
            }
            outbox.publish("license_token", token.getJti(), "license.expiring",
                    eventPayload(token, now));
            warned++;
        }
        return warned;
    }

    private boolean alreadyWarned(String jti) {
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM outbox_events WHERE aggregate_type = ? AND aggregate_id = ? "
                        + "AND event_type = ?",
                Long.class, "license_token", jti, "license.expiring");
        return count != null && count > 0;
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
