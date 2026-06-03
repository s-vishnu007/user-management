package com.example.cp.auth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.UUID;

/**
 * Redis-backed {@link SessionRevocationStore} using the auto-configured spring-boot-starter-data-redis.
 * Active only when a {@link RedisConnectionFactory} bean exists (the prod path).
 *
 * <p>Keys:</p>
 * <ul>
 *   <li>denylist: {@code cp:sess:denylist:jti:{jti}}="1" with PX = remaining token life.</li>
 *   <li>token-version: {@code cp:sess:tokenver:{userId}} as a Long, no TTL.</li>
 * </ul>
 *
 * <p>Fail modes (documented intentionally): {@link #isJtiDenylisted(String)} FAILS CLOSED on a Redis
 * error (treat an outage as "cannot confirm not-revoked"). {@link #currentTokenVersion(UUID)} returns
 * {@code -1} on error so the filter falls back to the durable DB token-version compare.</p>
 */
@Component
@ConditionalOnBean(RedisConnectionFactory.class)
public class RedisSessionRevocationStore implements SessionRevocationStore {

    private static final Logger log = LoggerFactory.getLogger(RedisSessionRevocationStore.class);

    private static final String DENYLIST_PREFIX = "cp:sess:denylist:jti:";
    private static final String TOKENVER_PREFIX = "cp:sess:tokenver:";

    private final StringRedisTemplate redis;

    public RedisSessionRevocationStore(StringRedisTemplate redis) {
        this.redis = redis;
    }

    private static String denylistKey(String jti) {
        return DENYLIST_PREFIX + jti;
    }

    private static String tokenVerKey(UUID userId) {
        return TOKENVER_PREFIX + userId;
    }

    @Override
    public void denylistJti(String jti, Duration ttl) {
        if (jti == null || ttl == null || ttl.isZero() || ttl.isNegative()) {
            return;
        }
        try {
            redis.opsForValue().set(denylistKey(jti), "1", ttl);
        } catch (Exception e) {
            log.warn("Redis denylistJti failed for jti (suppressed value): {}", e.getMessage());
        }
    }

    @Override
    public boolean isJtiDenylisted(String jti) {
        if (jti == null) {
            return false;
        }
        try {
            return Boolean.TRUE.equals(redis.hasKey(denylistKey(jti)));
        } catch (Exception e) {
            // Fail closed: an outage means we cannot confirm the session is NOT revoked.
            log.warn("Redis isJtiDenylisted failed; failing closed: {}", e.getMessage());
            return true;
        }
    }

    @Override
    public void setTokenVersion(UUID userId, long version) {
        if (userId == null) {
            return;
        }
        try {
            redis.opsForValue().set(tokenVerKey(userId), Long.toString(version));
        } catch (Exception e) {
            log.warn("Redis setTokenVersion failed for user {}: {}", userId, e.getMessage());
        }
    }

    @Override
    public long currentTokenVersion(UUID userId) {
        if (userId == null) {
            return -1L;
        }
        try {
            String v = redis.opsForValue().get(tokenVerKey(userId));
            return v == null ? -1L : Long.parseLong(v);
        } catch (Exception e) {
            // Fall back to DB compare on error.
            log.warn("Redis currentTokenVersion failed for user {}: {}", userId, e.getMessage());
            return -1L;
        }
    }

    @Override
    public long bumpTokenVersion(UUID userId) {
        try {
            Long v = redis.opsForValue().increment(tokenVerKey(userId));
            return v == null ? -1L : v;
        } catch (Exception e) {
            log.warn("Redis bumpTokenVersion failed for user {}: {}", userId, e.getMessage());
            return -1L;
        }
    }
}
