package com.example.cp.auth;

import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Lightweight in-memory rate limiter for login attempts per email.
 * Locks out for {@link #LOCKOUT_SECONDS} after {@link #MAX_ATTEMPTS} failures within {@link #WINDOW_SECONDS}.
 */
@Component
public class LoginAttempt {

    private static final int MAX_ATTEMPTS = 5;
    private static final int WINDOW_SECONDS = 5 * 60;
    private static final int LOCKOUT_SECONDS = 15 * 60;

    private final ConcurrentHashMap<String, Counter> counters = new ConcurrentHashMap<>();

    public boolean isLocked(String email) {
        if (email == null) {
            return false;
        }
        Counter c = counters.get(email.toLowerCase());
        if (c == null) {
            return false;
        }
        if (c.lockedUntil != null && Instant.now().isBefore(c.lockedUntil)) {
            return true;
        }
        return false;
    }

    public void recordFailure(String email) {
        if (email == null) return;
        String key = email.toLowerCase();
        counters.compute(key, (k, c) -> {
            Instant now = Instant.now();
            if (c == null || c.windowStart == null || c.windowStart.plusSeconds(WINDOW_SECONDS).isBefore(now)) {
                Counter nc = new Counter();
                nc.windowStart = now;
                nc.failures = 1;
                return nc;
            }
            c.failures++;
            if (c.failures >= MAX_ATTEMPTS) {
                c.lockedUntil = now.plusSeconds(LOCKOUT_SECONDS);
            }
            return c;
        });
    }

    public void recordSuccess(String email) {
        if (email == null) return;
        counters.remove(email.toLowerCase());
    }

    private static class Counter {
        Instant windowStart;
        int failures;
        Instant lockedUntil;
    }
}
