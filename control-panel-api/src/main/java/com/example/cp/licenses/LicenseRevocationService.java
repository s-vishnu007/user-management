package com.example.cp.licenses;

import com.example.cp.common.ApiException;
import com.example.cp.common.AuditContext;
import com.example.cp.subscriptions.OutboxPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class LicenseRevocationService {

    private final LicenseTokenRepository repo;
    private final OutboxPublisher outbox;

    public LicenseRevocationService(LicenseTokenRepository repo, OutboxPublisher outbox) {
        this.repo = repo;
        this.outbox = outbox;
    }

    @Transactional
    public LicenseToken markRevoked(String jti, String reason, UUID actorUserId) {
        LicenseToken t = repo.findByJti(jti)
                .orElseThrow(() -> ApiException.notFound("License not found"));
        if (t.getStatus() == LicenseToken.Status.REVOKED) {
            return t;
        }
        OffsetDateTime revokedAt = OffsetDateTime.now();

        // Guarded conditional UPDATE (audit P1-8): only revokes if not already revoked, atomic
        // against a concurrent heartbeat's last-seen update so the jti reliably reaches the CRL.
        int updated = repo.revokeIfNotRevoked(jti, revokedAt, reason, LicenseToken.Status.REVOKED);
        if (updated == 0) {
            // Lost the race to another revoker — already revoked, nothing more to do.
            return repo.findByJti(jti).orElseThrow(() -> ApiException.notFound("License not found"));
        }

        AuditContext.set("license.revoked");
        AuditContext.setTarget("license_token", jti);
        if (reason != null) AuditContext.putPayload("reason", reason);
        if (actorUserId != null) AuditContext.putPayload("revoked_by", actorUserId.toString());

        // Carry whichever anchor the token has: subscription-anchored (legacy) tokens emit
        // subscription_id; per-user (org-anchored) tokens emit org_id/user_id. A null subscription
        // must NOT be .toString()'d (NPE) nor placed in a Map.of (rejects nulls), so build it
        // conditionally.
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("jti", jti);
        if (t.getSubscriptionId() != null) event.put("subscription_id", t.getSubscriptionId().toString());
        if (t.getOrgId() != null) event.put("org_id", t.getOrgId().toString());
        if (t.getUserId() != null) event.put("user_id", t.getUserId().toString());
        event.put("reason", reason == null ? "" : reason);
        event.put("revoked_at", revokedAt.toString());
        outbox.publish("license_token", jti, "LicenseRevoked", event);

        return repo.findByJti(jti).orElseThrow(() -> ApiException.notFound("License not found"));
    }

    /**
     * Cascades revocation to every ACTIVE license of a subscription (audit P1-5). Called when a
     * subscription is suspended/cancelled so the now-invalid offline licenses reach the CRL instead
     * of staying valid (and seat-holding) until natural expiry. Each token is revoked through
     * {@link #markRevoked} so it gets its own audit + {@code LicenseRevoked} outbox event.
     *
     * @return the number of licenses revoked.
     */
    @Transactional
    public int revokeAllActiveForSubscription(UUID subscriptionId, String reason, UUID actorUserId) {
        List<LicenseToken> active = repo.findBySubscriptionIdAndStatus(
                subscriptionId, LicenseToken.Status.ACTIVE);
        int revoked = 0;
        for (LicenseToken t : active) {
            markRevoked(t.getJti(), reason, actorUserId);
            revoked++;
        }
        return revoked;
    }

    @Transactional(readOnly = true)
    public List<LicenseToken> listRevokedSince(OffsetDateTime since) {
        if (since == null) {
            return repo.findByStatusOrderByRevokedAtAsc(LicenseToken.Status.REVOKED);
        }
        return repo.findByStatusAndRevokedAtAfterOrderByRevokedAtAsc(LicenseToken.Status.REVOKED, since);
    }

    /**
     * REVOKED tokens that are still unexpired as of {@code now}: the only jtis the signed CRL needs
     * to carry. Expired-but-revoked jtis are pruned because an offline verifier already rejects them
     * on expiry, so the CRL does not grow without bound (audit P3).
     */
    @Transactional(readOnly = true)
    public List<LicenseToken> listActiveRevocations(OffsetDateTime now) {
        return repo.findByStatusAndExpiresAtAfterOrderByRevokedAtAsc(LicenseToken.Status.REVOKED, now);
    }

    /**
     * Cheap cache key for the signed CRL: {@code <count>:<maxRevokedAtMillis>}. Changes whenever a
     * token is revoked, so the CRL controller can cache the signed JWS and only re-sign when the
     * revoked set actually changes (audit P2 — avoids re-scanning + Ed25519-signing on every hit).
     */
    @Transactional(readOnly = true)
    public String revocationStateKey() {
        long count = repo.countByStatus(LicenseToken.Status.REVOKED);
        OffsetDateTime max = repo.maxRevokedAt(LicenseToken.Status.REVOKED);
        long maxMillis = max == null ? 0L : max.toInstant().toEpochMilli();
        return count + ":" + maxMillis;
    }
}
