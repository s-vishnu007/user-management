package com.example.cp.auth;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Fallback {@link LoginRateLimiter} backed by in-process maps. Correct for a single instance / the
 * test profile only — under a multi-instance deploy the counters are not shared, so the Redis impl
 * ({@link RedisLoginRateLimiter}) is preferred when available.
 *
 * <p>Registered as a bean only when no other {@link LoginRateLimiter} exists (see
 * {@link LoginRateLimiterConfig}); intentionally NOT a {@code @Component} so it never collides with
 * the Redis impl.</p>
 *
 * <p>Per-account counters support a hard lockout (account stays locked for {@code lockoutDuration}
 * once {@code maxAttempts} failures accrue within {@code window}). The per-IP counter is a rolling
 * failure count within the window used only to apply the {@code perIpMax} ceiling (spraying
 * mitigation); it has no separate lockout timer.</p>
 */
public class InMemoryLoginRateLimiter implements LoginRateLimiter {

    private final int maxAttempts;
    private final Duration window;
    private final Duration lockoutDuration;
    private final int perIpMax;

    private final ConcurrentHashMap<String, Counter> accounts = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Counter> ips = new ConcurrentHashMap<>();

    public InMemoryLoginRateLimiter(int maxAttempts, Duration window, Duration lockoutDuration, int perIpMax) {
        this.maxAttempts = maxAttempts;
        this.window = window;
        this.lockoutDuration = lockoutDuration;
        this.perIpMax = perIpMax;
    }

    @Override
    public boolean isLocked(String email, String ip) {
        Instant now = Instant.now();
        if (accountLocked(email, now)) {
            return true;
        }
        return ipExceeded(ip, now);
    }

    @Override
    public void recordFailure(String email, String ip) {
        Instant now = Instant.now();
        if (email != null && !email.isBlank()) {
            accounts.compute(key(email), (k, c) -> recordOn(c, now, true));
        }
        if (ip != null && !ip.isBlank()) {
            ips.compute(ip, (k, c) -> recordOn(c, now, false));
        }
    }

    @Override
    public void recordSuccess(String email, String ip) {
        if (email != null && !email.isBlank()) {
            accounts.remove(key(email));
        }
    }

    private boolean accountLocked(String email, Instant now) {
        if (email == null || email.isBlank()) {
            return false;
        }
        Counter c = accounts.get(key(email));
        return c != null && c.lockedUntil != null && now.isBefore(c.lockedUntil);
    }

    private boolean ipExceeded(String ip, Instant now) {
        if (ip == null || ip.isBlank()) {
            return false;
        }
        Counter c = ips.get(ip);
        if (c == null || c.windowStart == null) {
            return false;
        }
        if (c.windowStart.plus(window).isBefore(now)) {
            return false; // window elapsed
        }
        return c.failures >= perIpMax;
    }

    /** Increments a counter within the sliding window; for accounts, sets the lockout on overflow. */
    private Counter recordOn(Counter c, Instant now, boolean account) {
        if (c == null || c.windowStart == null || c.windowStart.plus(window).isBefore(now)) {
            Counter nc = new Counter();
            nc.windowStart = now;
            nc.failures = 1;
            return nc;
        }
        c.failures++;
        if (account && c.failures >= maxAttempts) {
            c.lockedUntil = now.plus(lockoutDuration);
        }
        return c;
    }

    private static String key(String email) {
        return email.trim().toLowerCase();
    }

    private static final class Counter {
        Instant windowStart;
        int failures;
        Instant lockedUntil;
    }
}
