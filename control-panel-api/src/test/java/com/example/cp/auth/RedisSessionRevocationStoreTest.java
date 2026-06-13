package com.example.cp.auth;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link RedisSessionRevocationStore} fail modes (finding P2):
 * <ul>
 *   <li>{@code denylistJti} must PROPAGATE a {@link RevocationStoreException} on a Redis error so a
 *       logout cannot falsely report success while leaving the token valid;</li>
 *   <li>{@code isJtiDenylisted} must FAIL CLOSED (return {@code true}) on a Redis error.</li>
 * </ul>
 */
class RedisSessionRevocationStoreTest {

    private StringRedisTemplate redis;
    private ValueOperations<String, String> valueOps;
    private RedisSessionRevocationStore store;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        redis = mock(StringRedisTemplate.class);
        valueOps = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(valueOps);
        store = new RedisSessionRevocationStore(redis);
    }

    @Test
    void denylistJti_propagatesOnRedisError() {
        doThrow(new RuntimeException("redis down"))
                .when(valueOps).set(anyString(), eq("1"), any(Duration.class));

        assertThatThrownBy(() -> store.denylistJti("jti-1", Duration.ofMinutes(5)))
                .isInstanceOf(RevocationStoreException.class);
    }

    @Test
    void denylistJti_ignoresNonPositiveTtl_withoutTouchingRedis() {
        // No exception and no throw: nothing to revoke for an already-expired token.
        store.denylistJti("jti-1", Duration.ZERO);
        store.denylistJti("jti-1", Duration.ofSeconds(-1));
        store.denylistJti(null, Duration.ofMinutes(5));
    }

    @Test
    void isJtiDenylisted_failsClosedOnRedisError() {
        when(redis.hasKey(anyString())).thenThrow(new RuntimeException("redis down"));
        assertThat(store.isJtiDenylisted("jti-1")).isTrue();
    }
}
