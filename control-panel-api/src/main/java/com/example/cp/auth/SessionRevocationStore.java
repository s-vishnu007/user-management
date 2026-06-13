package com.example.cp.auth;

import java.time.Duration;
import java.util.UUID;

/**
 * Abstraction over the per-session jti denylist and the per-user token-version fast-path so that
 * {@link JwtAuthFilter} and tests do not depend directly on Redis.
 *
 * <p>A Redis-backed impl is selected in prod (when a {@code RedisConnectionFactory} bean exists);
 * an in-memory impl is the fallback for tests/local dev without Redis.</p>
 *
 * <p>Contract notes:</p>
 * <ul>
 *   <li>{@link #denylistJti(String, Duration)} ttl MUST equal the remaining token life (exp - now)
 *       so the key self-expires; never store with infinite TTL.</li>
 *   <li>{@link #currentTokenVersion(UUID)} returns {@code -1} when the version is not cached so
 *       callers can fall back to the durable DB value (users.token_version).</li>
 * </ul>
 */
public interface SessionRevocationStore {

    void denylistJti(String jti, Duration ttl);

    boolean isJtiDenylisted(String jti);

    void setTokenVersion(UUID userId, long version);

    /** @return cached token version, or {@code -1} on cache miss / error so callers fall back to the DB. */
    long currentTokenVersion(UUID userId);
}
