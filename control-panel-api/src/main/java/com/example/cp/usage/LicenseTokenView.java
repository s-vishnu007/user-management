package com.example.cp.usage;

import java.time.OffsetDateTime;
import java.util.UUID;

public record LicenseTokenView(
        UUID id,
        String jti,
        UUID subscriptionId,
        String status,
        OffsetDateTime expiresAt,
        OffsetDateTime revokedAt
) {
    public boolean isActive() {
        return "ACTIVE".equals(status)
                && revokedAt == null
                && (expiresAt == null || expiresAt.isAfter(OffsetDateTime.now()));
    }
}
