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

    @Column(name = "subscription_id", nullable = false)
    private UUID subscriptionId;

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
