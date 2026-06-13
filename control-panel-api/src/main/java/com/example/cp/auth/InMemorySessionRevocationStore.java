package com.example.cp.auth;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Fallback {@link SessionRevocationStore} backed by in-process maps. Selected by
 * {@link SessionRevocationConfig} only when no {@code RedisConnectionFactory} is available;
 * intentionally NOT a {@code @Component} so it does not collide with the Redis impl when Redis is
 * present.
 *
 * <p>Guarantees the auth path never NPEs and lets tests run without a live Redis. The jti denylist
 * is bounded ({@link #MAX_DENYLIST}) and prunes expired entries on write so it cannot grow without
 * limit.</p>
 */
public class InMemorySessionRevocationStore implements SessionRevocationStore {

    /**
     * Upper bound on tracked denylist entries; protects against unbounded growth if a flood of
     * logouts arrives faster than entries self-expire. Entries are pruned on write and, once at the
     * cap, the oldest-expiring entry is evicted to make room (it would self-expire on read anyway).
     */
    static final int MAX_DENYLIST = 100_000;

    private final ConcurrentHashMap<String, Instant> denylist = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, AtomicLong> versions = new ConcurrentHashMap<>();

    @Override
    public void denylistJti(String jti, Duration ttl) {
        if (jti == null || ttl == null || ttl.isZero() || ttl.isNegative()) {
            return;
        }
        Instant now = Instant.now();
        // Opportunistically drop entries that have already expired so the map tracks live tokens only.
        denylist.values().removeIf(expiry -> !expiry.isAfter(now));
        if (denylist.size() >= MAX_DENYLIST && !denylist.containsKey(jti)) {
            denylist.entrySet().stream()
                    .min(java.util.Map.Entry.comparingByValue())
                    .map(java.util.Map.Entry::getKey)
                    .ifPresent(denylist::remove);
        }
        denylist.put(jti, now.plus(ttl));
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
}
