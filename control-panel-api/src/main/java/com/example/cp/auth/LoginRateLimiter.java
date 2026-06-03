package com.example.cp.auth;

/**
 * Cluster-safe login brute-force throttle. Tracks failed login attempts per-account (email) AND
 * per-source-IP within a sliding window and locks out once the configured ceiling is reached.
 *
 * <p>A Redis-backed impl ({@link RedisLoginRateLimiter}) is selected when a
 * {@code RedisConnectionFactory} bean exists so counters are shared across all instances; an
 * in-memory impl ({@link InMemoryLoginRateLimiter}) is the fallback for the test profile / single
 * instance. {@link LoginRateLimiterConfig} guarantees exactly one bean is present.</p>
 *
 * <p>Contract: {@link #isLocked(String, String)} is consulted BEFORE checking credentials;
 * {@link #recordFailure(String, String)} is called on every failed attempt (unknown user, bad
 * password, inactive account); {@link #recordSuccess(String, String)} clears the account counter
 * on a successful login.</p>
 */
public interface LoginRateLimiter {

    /**
     * @return true if the account (email) is currently locked OR the source IP has exceeded the
     *         per-IP failure ceiling within the window.
     */
    boolean isLocked(String email, String ip);

    /** Records one failed attempt against both the account and the IP counters. */
    void recordFailure(String email, String ip);

    /** Clears the per-account counter (and lock) after a successful authentication. */
    void recordSuccess(String email, String ip);
}
