package com.example.cp.licenses;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "license_tokens")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class LicenseToken {

    public enum Status { ACTIVE, REVOKED, EXPIRED }

    /** Distinguishes a first-class trial license from a standard (paid) one. */
    public enum LicenseType { STANDARD, TRIAL }

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    /**
     * Optimistic-locking version. Prevents a heartbeat's last-seen flush from silently rewriting a
     * revocation/expiry committed by a concurrent transaction back to ACTIVE (lost update, P1-8).
     */
    @Version
    @Column(name = "version", nullable = false)
    private long version;

    @Column(name = "jti", nullable = false, unique = true, length = 64)
    private String jti;

    /**
     * Legacy/back-compat anchor: set only for licenses minted from a subscription
     * ({@code POST /subscriptions/{id}/licenses}). Per-user licenses ({@code POST /orgs/{id}/licenses})
     * leave this NULL and anchor on {@link #orgId} instead (migration 20 dropped the NOT NULL).
     */
    @Column(name = "subscription_id")
    private UUID subscriptionId;

    /**
     * Owning organization. The direct tenant anchor for per-user licenses (no subscription hop);
     * backfilled from the subscription for legacy rows so {@code TenantAccessChecker.resolveOrgForJti}
     * can resolve any token's org uniformly.
     */
    @Column(name = "org_id")
    private UUID orgId;

    /** The user this license was issued to (the JWT subject). NULL for legacy subscription licenses. */
    @Column(name = "user_id")
    private UUID userId;

    /** Durable display snapshot of the subject's email (survives a later user delete). */
    @Column(name = "subject_email", length = 320)
    private String subjectEmail;

    /** JSON-array snapshot of the permission codes baked into the JWT (for the Licenses list / audit). */
    @Builder.Default
    @Column(name = "permissions", nullable = false, columnDefinition = "text")
    private String permissions = "[]";

    /** JSON-array snapshot of the role codes chosen as presets when issuing. */
    @Builder.Default
    @Column(name = "roles", nullable = false, columnDefinition = "text")
    private String roles = "[]";

    @Column(name = "kid", nullable = false, length = 64)
    private String kid;

    @Column(name = "issued_at", nullable = false)
    private OffsetDateTime issuedAt;

    @Column(name = "expires_at", nullable = false)
    private OffsetDateTime expiresAt;

    @Column(name = "revoked_at")
    private OffsetDateTime revokedAt;

    @Column(name = "revoke_reason")
    private String revokeReason;

    @Column(name = "fingerprint", length = 128)
    private String fingerprint;

    @Column(name = "last_seen_at")
    private OffsetDateTime lastSeenAt;

    @Column(name = "last_seen_ip", length = 45)
    private String lastSeenIp;

    /**
     * When the lifecycle sweeper emitted the (single) {@code license.expiring} warning for this
     * token. Durable dedup marker so a token is warned at most once even across concurrent sweeps
     * and after the outbox is purged. NULL = never warned.
     */
    @Column(name = "expiring_warned_at")
    private OffsetDateTime expiringWarnedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private Status status;

    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(name = "license_type", nullable = false, length = 20)
    private LicenseType licenseType = LicenseType.STANDARD;
}
