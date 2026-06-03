package com.example.cp.licenses;

import com.example.cp.common.AuditContext;
import com.example.cp.common.ApiException;
import com.example.cp.common.Ids;
import com.example.cp.keys.JwsSigner;
import com.example.cp.keys.KeyService;
import com.example.cp.subscriptions.OutboxPublisher;
import com.example.cp.subscriptions.Subscription;
import com.example.cp.subscriptions.SubscriptionService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Component
public class LicenseIssuer {

    private final KeyService keyService;
    private final JwsSigner jwsSigner;
    private final LicenseClaimsBuilder claimsBuilder;
    private final LicenseTokenRepository tokenRepo;
    private final SubscriptionService subService;
    private final OutboxPublisher outbox;
    private final int trialTtlDays;

    public LicenseIssuer(KeyService keyService,
                         JwsSigner jwsSigner,
                         LicenseClaimsBuilder claimsBuilder,
                         LicenseTokenRepository tokenRepo,
                         SubscriptionService subService,
                         OutboxPublisher outbox,
                         @Value("${app.licensing.trial-ttl-days:14}") int trialTtlDays) {
        this.keyService = keyService;
        this.jwsSigner = jwsSigner;
        this.claimsBuilder = claimsBuilder;
        this.tokenRepo = tokenRepo;
        this.subService = subService;
        this.outbox = outbox;
        this.trialTtlDays = trialTtlDays;
    }

    @Transactional
    public IssuedLicense issue(UUID subscriptionId, Integer ttlDaysOverride, List<String> audienceOverride) {
        return issue(subscriptionId, ttlDaysOverride, audienceOverride, LicenseToken.LicenseType.STANDARD);
    }

    /**
     * Mints a short-lived TRIAL license. The TTL is the explicit override when positive, otherwise
     * the configured {@code app.licensing.trial-ttl-days} (default 14d), so a trial never inherits
     * the plan's full default TTL.
     */
    @Transactional
    public IssuedLicense issueTrial(UUID subscriptionId, Integer ttlDaysOverride, List<String> audienceOverride) {
        int ttl = (ttlDaysOverride != null && ttlDaysOverride > 0) ? ttlDaysOverride : trialTtlDays;
        return issue(subscriptionId, ttl, audienceOverride, LicenseToken.LicenseType.TRIAL);
    }

    @Transactional
    public IssuedLicense issue(UUID subscriptionId, Integer ttlDaysOverride, List<String> audienceOverride,
                               LicenseToken.LicenseType licenseType) {
        Subscription sub = subService.get(subscriptionId);
        if (sub.getStatus() != Subscription.Status.ACTIVE) {
            throw ApiException.badRequest("Cannot issue license for subscription in status " + sub.getStatus());
        }

        LicenseClaimsBuilder.BuiltClaims built = claimsBuilder.build(sub, ttlDaysOverride, audienceOverride);

        KeyService.ActiveKey active = keyService.getActiveSigningKeyPair();
        String jwt = jwsSigner.sign(built.claims(), "license+jwt", active);
        String fingerprint = sha256TruncatedHex(jwt, 32);

        LicenseToken.LicenseType type = licenseType == null ? LicenseToken.LicenseType.STANDARD : licenseType;
        LicenseToken row = LicenseToken.builder()
                .id(Ids.newId())
                .jti(built.jti())
                .subscriptionId(sub.getId())
                .kid(active.kid())
                .issuedAt(built.issuedAt())
                .expiresAt(built.expiresAt())
                .fingerprint(fingerprint)
                .status(LicenseToken.Status.ACTIVE)
                .licenseType(type)
                .build();
        tokenRepo.save(row);

        AuditContext.set("license.issued");
        AuditContext.setTarget("license_token", built.jti());
        AuditContext.putPayload("subscription_id", sub.getId().toString());
        AuditContext.putPayload("plan_code", built.planCode());
        AuditContext.putPayload("kid", active.kid());
        AuditContext.putPayload("license_type", type.name());

        outbox.publish("license_token", built.jti(), "LicenseIssued",
                Map.of(
                        "jti", built.jti(),
                        "subscription_id", sub.getId().toString(),
                        "org_id", sub.getOrgId().toString(),
                        "plan_code", built.planCode(),
                        "kid", active.kid(),
                        "issued_at", built.issuedAt().toString(),
                        "expires_at", built.expiresAt().toString(),
                        "license_type", type.name()
                )
        );

        return new IssuedLicense(built.jti(), jwt, built.issuedAt(), built.expiresAt(),
                built.planCode(), built.orgName(), built.orgSlug(), active.kid());
    }

    private static String sha256TruncatedHex(String jwt, int hexChars) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(jwt.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            String hex = HexFormat.of().formatHex(hash);
            return hex.substring(0, Math.min(hexChars, hex.length()));
        } catch (Exception e) {
            return null;
        }
    }

    public record IssuedLicense(
            String jti,
            String jwt,
            OffsetDateTime issuedAt,
            OffsetDateTime expiresAt,
            String planCode,
            String orgName,
            String orgSlug,
            String kid
    ) {}
}
