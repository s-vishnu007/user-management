package com.example.cp.licenses;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * A single node/seat activation against a license, keyed by {@code (jti, node_id)}.
 *
 * <p>The heartbeat endpoint upserts one of these per phone-home: {@code first_seen_at} is set once
 * on the initial activation and {@code last_seen_at}/{@code last_seen_ip} are refreshed on every
 * subsequent beat. "Active" seats are counted as the rows whose {@code last_seen_at} falls within
 * the configurable lease window ({@code app.licensing.lease-window}); rows older than the window are
 * treated as released seats and do not count against the seat limit.
 */
@Entity
@Table(name = "license_activations")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class LicenseActivation {

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    /** License this activation belongs to (references {@code license_tokens.jti}). */
    @Column(name = "jti", nullable = false, length = 64)
    private String jti;

    /** App-reported node identifier (host id / instance id); unique per jti. */
    @Column(name = "node_id", nullable = false, length = 190)
    private String nodeId;

    @Column(name = "first_seen_at", nullable = false)
    private OffsetDateTime firstSeenAt;

    @Column(name = "last_seen_at", nullable = false)
    private OffsetDateTime lastSeenAt;

    @Column(name = "last_seen_ip", length = 45)
    private String lastSeenIp;
}
