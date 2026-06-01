package com.example.cp.licenses;

import java.time.OffsetDateTime;
import java.util.UUID;

public record LicenseDto(
        UUID id,
        String jti,
        UUID subscriptionId,
        String kid,
        OffsetDateTime issuedAt,
        OffsetDateTime expiresAt,
        OffsetDateTime revokedAt,
        String revokeReason,
        String fingerprint,
        OffsetDateTime lastSeenAt,
        String lastSeenIp,
        String status
) {
    public static LicenseDto from(LicenseToken t) {
        return new LicenseDto(
                t.getId(), t.getJti(), t.getSubscriptionId(), t.getKid(),
                t.getIssuedAt(), t.getExpiresAt(), t.getRevokedAt(), t.getRevokeReason(),
                t.getFingerprint(), t.getLastSeenAt(), t.getLastSeenIp(),
                t.getStatus().name()
        );
    }
}
