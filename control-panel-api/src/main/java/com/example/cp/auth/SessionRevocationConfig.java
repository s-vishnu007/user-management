package com.example.cp.auth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * Provides exactly one {@link SessionRevocationStore} bean, chosen deterministically: the Redis impl
 * when a {@code RedisConnectionFactory} is present (so single-session logout is durable and shared
 * across instances), the in-memory impl otherwise (test profile / single instance / no Redis).
 *
 * <p>Uses {@link ObjectProvider} rather than a component-scanned
 * {@code @Component @ConditionalOnBean(RedisConnectionFactory)} on the Redis store: that condition is
 * evaluated before {@code RedisAutoConfiguration} registers the factory, so it silently never matched
 * and the in-memory store was always selected even in production (finding P2). Mirrors
 * {@code LoginRateLimiterConfig}.</p>
 */
@Configuration
public class SessionRevocationConfig {

    private static final Logger log = LoggerFactory.getLogger(SessionRevocationConfig.class);

    @Bean
    public SessionRevocationStore sessionRevocationStore(
            ObjectProvider<RedisConnectionFactory> redisConnectionFactory) {
        RedisConnectionFactory cf = redisConnectionFactory.getIfAvailable();
        if (cf != null) {
            StringRedisTemplate template = new StringRedisTemplate(cf);
            template.afterPropertiesSet();
            log.info("Session revocation store: Redis-backed (durable single-session logout)");
            return new RedisSessionRevocationStore(template);
        }
        log.info("Session revocation store: in-memory (no RedisConnectionFactory; per-JVM, "
                + "non-durable single-session logout). Bulk revocation via users.token_version is unaffected.");
        return new InMemorySessionRevocationStore();
    }
}
