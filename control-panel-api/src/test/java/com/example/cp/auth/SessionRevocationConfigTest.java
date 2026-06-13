package com.example.cp.auth;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.connection.RedisConnectionFactory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Verifies the {@link SessionRevocationConfig} selection logic that closes the production wiring trap
 * (finding P2): the Redis-backed store MUST be chosen whenever a {@link RedisConnectionFactory} is
 * available, and only the in-memory store when it is absent. The old
 * {@code @Component @ConditionalOnBean(RedisConnectionFactory)} on the Redis store evaluated before
 * {@code RedisAutoConfiguration} registered the factory, so it never matched and the in-memory store
 * was silently used even in prod. Resolving via {@link ObjectProvider} here fixes the ordering.
 */
class SessionRevocationConfigTest {

    private final SessionRevocationConfig config = new SessionRevocationConfig();

    @Test
    void selectsRedisStore_whenAConnectionFactoryIsPresent() {
        // StringRedisTemplate.afterPropertiesSet() only needs a non-null factory; it does not open a
        // connection, so a bare mock (no live Redis) is sufficient to prove the selection branch.
        RedisConnectionFactory cf = mock(RedisConnectionFactory.class);

        @SuppressWarnings("unchecked")
        ObjectProvider<RedisConnectionFactory> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(cf);

        SessionRevocationStore store = config.sessionRevocationStore(provider);

        assertThat(store).isInstanceOf(RedisSessionRevocationStore.class);
    }

    @Test
    void fallsBackToInMemory_whenNoConnectionFactory() {
        @SuppressWarnings("unchecked")
        ObjectProvider<RedisConnectionFactory> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(null);

        SessionRevocationStore store = config.sessionRevocationStore(provider);

        assertThat(store).isInstanceOf(InMemorySessionRevocationStore.class);
    }
}
