package com.example.cp.auth;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Fallback {@link SessionRevocationStore} backed by in-process maps. Registered as a bean only when
 * no other {@link SessionRevocationStore} exists (see {@link SessionRevocationConfig}); intentionally
 * NOT a {@code @Component} so it does not collide with the Redis impl when Redis is present.
 *
 * <p>Guarantees the auth path never NPEs and lets tests run without a live Redis.</p>
 */
public class InMemorySessionRevocationStore implements SessionRevocationStore {

    private final ConcurrentHashMap<String, Instant> denylist = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, AtomicLong> versions = new ConcurrentHashMap<>();

    @Override
    public void denylistJti(String jti, Duration ttl) {
        if (jti == null || ttl == null || ttl.isZero() || ttl.isNegative()) {
            return;
        }
        denylist.put(jti, Instant.now().plus(ttl));
    }

    @Override
    public boolean isJtiDenylisted(String jti) {
        if (jti == null) {
            return false;
        }
        Instant expiry = denylist.get(jti);
        if (expiry == null) {
            return false;
        }
        if (!expiry.isAfter(Instant.now())) {
            denylist.remove(jti, expiry);
            return false;
        }
        return true;
    }

    @Override
    public void setTokenVersion(UUID userId, long version) {
        if (userId == null) {
            return;
        }
        versions.put(userId, new AtomicLong(version));
    }

    @Override
    public long currentTokenVersion(UUID userId) {
        if (userId == null) {
            return -1L;
        }
        AtomicLong v = versions.get(userId);
        return v == null ? -1L : v.get();
    }

    @Override
    public long bumpTokenVersion(UUID userId) {
        return versions.computeIfAbsent(userId, k -> new AtomicLong(0)).incrementAndGet();
    }
}
