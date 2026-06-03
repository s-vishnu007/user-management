package com.example.cp.auth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

/**
 * Cluster-safe {@link LoginRateLimiter} backed by Redis so failed-attempt counters and account
 * lockouts are shared across every control-panel instance. Active only when a
 * {@link RedisConnectionFactory} bean exists (the prod path); otherwise
 * {@link InMemoryLoginRateLimiter} is used.
 *
 * <p>Keys (all self-expiring — never stored without a TTL):</p>
 * <ul>
 *   <li>per-account failures: {@code cp:login:fail:acct:{email}} (INCR, EXPIRE = window)</li>
 *   <li>per-account lockout:  {@code cp:login:lock:acct:{email}}  (set on overflow, EXPIRE = lockout)</li>
 *   <li>per-IP failures:      {@code cp:login:fail:ip:{ip}}       (INCR, EXPIRE = window)</li>
 * </ul>
 *
 * <p>Fail mode: every Redis call is wrapped so a transient outage degrades to "not locked / not
 * recorded" (fail-open) rather than locking everyone out — login availability is preserved and the
 * in-memory fallback never applies once Redis is wired.</p>
 */
// Instantiated by LoginRateLimiterConfig only when a RedisConnectionFactory is present (deterministic
// ObjectProvider wiring), so no class-level @Component/@ConditionalOnBean is used here.
public class RedisLoginRateLimiter implements LoginRateLimiter {

    private static final Logger log = LoggerFactory.getLogger(RedisLoginRateLimiter.class);

    private static final String ACCT_FAIL = "cp:login:fail:acct:";
    private static final String ACCT_LOCK = "cp:login:lock:acct:";
    private static final String IP_FAIL = "cp:login:fail:ip:";

    private final StringRedisTemplate redis;
    private final int maxAttempts;
    private final Duration window;
    private final Duration lockoutDuration;
    private final int perIpMax;

    public RedisLoginRateLimiter(
            StringRedisTemplate redis,
            @Value("${app.auth.lockout.max-attempts:5}") int maxAttempts,
            @Value("${app.auth.lockout.window:PT5M}") Duration window,
            @Value("${app.auth.lockout.lockout:PT15M}") Duration lockoutDuration,
            @Value("${app.auth.lockout.per-ip-max:20}") int perIpMax) {
        this.redis = redis;
        this.maxAttempts = maxAttempts;
        this.window = window;
        this.lockoutDuration = lockoutDuration;
        this.perIpMax = perIpMax;
    }

    @Override
    public boolean isLocked(String email, String ip) {
        try {
            if (email != null && !email.isBlank()
                    && Boolean.TRUE.equals(redis.hasKey(ACCT_LOCK + key(email)))) {
                return true;
            }
            if (ip != null && !ip.isBlank()) {
                String v = redis.opsForValue().get(IP_FAIL + ip);
                if (v != null && parse(v) >= perIpMax) {
                    return true;
                }
            }
            return false;
        } catch (Exception e) {
            // Fail open: a Redis outage must not lock every user out of login.
            log.warn("Redis isLocked failed; failing open: {}", e.getMessage());
            return false;
        }
    }

    @Override
    public void recordFailure(String email, String ip) {
        try {
            if (email != null && !email.isBlank()) {
                String k = ACCT_FAIL + key(email);
                Long count = redis.opsForValue().increment(k);
                if (count != null && count == 1L) {
                    redis.expire(k, window.toMillis(), TimeUnit.MILLISECONDS);
                }
                if (count != null && count >= maxAttempts) {
                    redis.opsForValue().set(ACCT_LOCK + key(email), "1", lockoutDuration);
                }
            }
            if (ip != null && !ip.isBlank()) {
                String k = IP_FAIL + ip;
                Long count = redis.opsForValue().increment(k);
                if (count != null && count == 1L) {
                    redis.expire(k, window.toMillis(), TimeUnit.MILLISECONDS);
                }
            }
        } catch (Exception e) {
            log.warn("Redis recordFailure failed: {}", e.getMessage());
        }
    }

    @Override
    public void recordSuccess(String email, String ip) {
        try {
            if (email != null && !email.isBlank()) {
                redis.delete(ACCT_FAIL + key(email));
                redis.delete(ACCT_LOCK + key(email));
            }
        } catch (Exception e) {
            log.warn("Redis recordSuccess failed: {}", e.getMessage());
        }
    }

    private static long parse(String v) {
        try {
            return Long.parseLong(v);
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    private static String key(String email) {
        return email.trim().toLowerCase();
    }
}
