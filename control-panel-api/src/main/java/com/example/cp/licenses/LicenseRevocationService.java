package com.example.cp.licenses;

import com.example.cp.common.ApiException;
import com.example.cp.common.AuditContext;
import com.example.cp.subscriptions.OutboxPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
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
        t.setStatus(LicenseToken.Status.REVOKED);
        t.setRevokedAt(OffsetDateTime.now());
        t.setRevokeReason(reason);
        LicenseToken saved = repo.save(t);

        AuditContext.set("license.revoked");
        AuditContext.setTarget("license_token", jti);
        if (reason != null) AuditContext.putPayload("reason", reason);
        if (actorUserId != null) AuditContext.putPayload("revoked_by", actorUserId.toString());

        outbox.publish("license_token", jti, "LicenseRevoked",
                Map.of(
                        "jti", jti,
                        "subscription_id", saved.getSubscriptionId().toString(),
                        "reason", reason == null ? "" : reason,
                        "revoked_at", saved.getRevokedAt().toString()
                )
        );

        return saved;
    }

    @Transactional(readOnly = true)
    public List<LicenseToken> listRevokedSince(OffsetDateTime since) {
        if (since == null) {
            return repo.findByStatusOrderByRevokedAtAsc(LicenseToken.Status.REVOKED);
        }
        return repo.findByStatusAndRevokedAtAfterOrderByRevokedAtAsc(LicenseToken.Status.REVOKED, since);
    }
}
