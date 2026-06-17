package com.example.cp.licenses;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public record LicenseDto(
        UUID id,
        String jti,
        UUID subscriptionId,
        UUID orgId,
        UUID userId,
        String subjectEmail,
        List<String> permissions,
        List<String> roles,
        String kid,
        OffsetDateTime issuedAt,
        OffsetDateTime expiresAt,
        OffsetDateTime revokedAt,
        String revokeReason,
        String fingerprint,
        OffsetDateTime lastSeenAt,
        String lastSeenIp,
        String status,
        String licenseType,
        Long activeSeats
) {
    // The permission/role snapshots are written by us as JSON arrays of strings, so a tolerant local
    // mapper is sufficient (and keeps the static factory dependency-free). A malformed/legacy value
    // degrades to an empty list rather than failing the whole listing.
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {};

    public static LicenseDto from(LicenseToken t) {
        return from(t, null);
    }

    /** Variant that surfaces the current active-seat (node) count for the license. */
    public static LicenseDto from(LicenseToken t, Long activeSeats) {
        return new LicenseDto(
                t.getId(), t.getJti(), t.getSubscriptionId(),
                t.getOrgId(), t.getUserId(), t.getSubjectEmail(),
                parseJsonArray(t.getPermissions()), parseJsonArray(t.getRoles()),
                t.getKid(),
                t.getIssuedAt(), t.getExpiresAt(), t.getRevokedAt(), t.getRevokeReason(),
                t.getFingerprint(), t.getLastSeenAt(), t.getLastSeenIp(),
                t.getStatus().name(),
                t.getLicenseType() == null ? null : t.getLicenseType().name(),
                activeSeats
        );
    }

    private static List<String> parseJsonArray(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            List<String> parsed = MAPPER.readValue(json, STRING_LIST);
            return parsed == null ? List.of() : parsed;
        } catch (Exception e) {
            return List.of();
        }
    }
}
