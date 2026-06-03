package com.example.licenseverifier;

import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * Immutable parsed representation of a signed CRL: the issuer, when it was issued, when it next
 * needs to be refreshed, and the set of revoked license jtis. Returned by
 * {@link CrlVerifier#verify(String)} and held by the starter's revocation-checker cache.
 */
public final class RevocationList {

    private final String issuer;
    private final Instant issuedAt;
    private final Instant nextUpdate;
    private final Set<String> revokedJtis;

    public RevocationList(String issuer, Instant issuedAt, Instant nextUpdate, Set<String> revokedJtis) {
        this.issuer = issuer;
        this.issuedAt = issuedAt;
        this.nextUpdate = nextUpdate;
        this.revokedJtis = revokedJtis == null
                ? Collections.emptySet()
                : Collections.unmodifiableSet(new HashSet<>(revokedJtis));
    }

    public String issuer() {
        return issuer;
    }

    public Instant issuedAt() {
        return issuedAt;
    }

    public Instant nextUpdate() {
        return nextUpdate;
    }

    public Set<String> revokedJtis() {
        return revokedJtis;
    }

    public boolean isRevoked(String jti) {
        return jti != null && revokedJtis.contains(jti);
    }

    /**
     * @return {@code true} if this list should be treated as stale at {@code now}, i.e. it has no
     *         {@code nextUpdate} or {@code now} is past {@code nextUpdate + maxStale}.
     */
    public boolean isStale(Instant now, Duration maxStale) {
        return nextUpdate == null || now.isAfter(nextUpdate.plus(maxStale));
    }
}
