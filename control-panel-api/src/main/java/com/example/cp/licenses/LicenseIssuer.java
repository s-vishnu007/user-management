package com.example.cp.licenses;

import com.example.cp.common.AuditContext;
import com.example.cp.common.ApiException;
import com.example.cp.common.Ids;
import com.example.cp.keys.KeyService;
import com.example.cp.subscriptions.OutboxPublisher;
import com.example.cp.subscriptions.Subscription;
import com.example.cp.subscriptions.SubscriptionService;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.Ed25519Signer;
import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.OctetKeyPair;
import com.nimbusds.jose.util.Base64URL;
import com.nimbusds.jwt.SignedJWT;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.security.MessageDigest;
import java.security.interfaces.EdECPrivateKey;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Component
public class LicenseIssuer {

    private final KeyService keyService;
    private final LicenseClaimsBuilder claimsBuilder;
    private final LicenseTokenRepository tokenRepo;
    private final SubscriptionService subService;
    private final OutboxPublisher outbox;

    public LicenseIssuer(KeyService keyService,
                         LicenseClaimsBuilder claimsBuilder,
                         LicenseTokenRepository tokenRepo,
                         SubscriptionService subService,
                         OutboxPublisher outbox) {
        this.keyService = keyService;
        this.claimsBuilder = claimsBuilder;
        this.tokenRepo = tokenRepo;
        this.subService = subService;
        this.outbox = outbox;
    }

    @Transactional
    public IssuedLicense issue(UUID subscriptionId, Integer ttlDaysOverride, List<String> audienceOverride) {
        Subscription sub = subService.get(subscriptionId);
        if (sub.getStatus() != Subscription.Status.ACTIVE) {
            throw ApiException.badRequest("Cannot issue license for subscription in status " + sub.getStatus());
        }

        LicenseClaimsBuilder.BuiltClaims built = claimsBuilder.build(sub, ttlDaysOverride, audienceOverride);

        KeyService.ActiveKey active = keyService.getActiveSigningKeyPair();
        String jwt = signJwt(built, active);
        String fingerprint = sha256TruncatedHex(jwt, 32);

        LicenseToken row = LicenseToken.builder()
                .id(Ids.newId())
                .jti(built.jti())
                .subscriptionId(sub.getId())
                .kid(active.kid())
                .issuedAt(built.issuedAt())
                .expiresAt(built.expiresAt())
                .fingerprint(fingerprint)
                .status(LicenseToken.Status.ACTIVE)
                .build();
        tokenRepo.save(row);

        AuditContext.set("license.issued");
        AuditContext.setTarget("license_token", built.jti());
        AuditContext.putPayload("subscription_id", sub.getId().toString());
        AuditContext.putPayload("plan_code", built.planCode());
        AuditContext.putPayload("kid", active.kid());

        outbox.publish("license_token", built.jti(), "LicenseIssued",
                Map.of(
                        "jti", built.jti(),
                        "subscription_id", sub.getId().toString(),
                        "org_id", sub.getOrgId().toString(),
                        "plan_code", built.planCode(),
                        "kid", active.kid(),
                        "issued_at", built.issuedAt().toString(),
                        "expires_at", built.expiresAt().toString()
                )
        );

        return new IssuedLicense(built.jti(), jwt, built.issuedAt(), built.expiresAt(),
                built.planCode(), built.orgName(), built.orgSlug(), active.kid());
    }

    private String signJwt(LicenseClaimsBuilder.BuiltClaims built, KeyService.ActiveKey active) {
        try {
            byte[] rawPub = KeyService.extractRawEd25519PublicBytes(active.publicKey());
            byte[] rawPriv = extractRawEd25519PrivateBytes(active.privateKey());

            OctetKeyPair okp = new OctetKeyPair.Builder(Curve.Ed25519, Base64URL.encode(rawPub))
                    .d(Base64URL.encode(rawPriv))
                    .keyID(active.kid())
                    .algorithm(JWSAlgorithm.EdDSA)
                    .build();

            JWSHeader header = new JWSHeader.Builder(JWSAlgorithm.EdDSA)
                    .keyID(active.kid())
                    .type(new JOSEObjectType("license+jwt"))
                    .build();

            SignedJWT signedJwt = new SignedJWT(header, built.claims());
            signedJwt.sign(new Ed25519Signer(okp));
            return signedJwt.serialize();
        } catch (Exception e) {
            throw new RuntimeException("Failed to sign license JWT", e);
        }
    }

    /**
     * Java's PKCS8-encoded Ed25519 private key wraps a 32-byte CurvePrivateKey OCTET STRING.
     * The standard SunEC PKCS8 layout is 48 bytes total: header (16) + OCTET STRING (2 header + 32 key).
     * We look for a 0x04 0x20 marker and take the next 32 bytes.
     */
    static byte[] extractRawEd25519PrivateBytes(java.security.PrivateKey priv) {
        byte[] enc = priv.getEncoded();
        // search for the inner CurvePrivateKey marker: 0x04 0x20 followed by 32 bytes
        for (int i = 0; i + 33 < enc.length; i++) {
            if (enc[i] == 0x04 && enc[i + 1] == 0x20 && i + 2 + 32 <= enc.length) {
                byte[] raw = new byte[32];
                System.arraycopy(enc, i + 2, raw, 0, 32);
                return raw;
            }
        }
        if (priv instanceof EdECPrivateKey ed) {
            return ed.getBytes().orElseThrow(() ->
                    new IllegalStateException("EdECPrivateKey has no exportable raw bytes")
            );
        }
        throw new IllegalStateException("Could not extract raw Ed25519 private key bytes");
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
