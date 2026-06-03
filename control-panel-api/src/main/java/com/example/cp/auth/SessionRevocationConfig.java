package com.example.cp.auth;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Provides the fallback {@link SessionRevocationStore} bean. This guarantees exactly one
 * {@link SessionRevocationStore} bean exists: the Redis impl when a {@code RedisConnectionFactory}
 * is present, the in-memory impl otherwise. Avoids {@code NoSuchBeanDefinitionException} in tests
 * (no live Redis) and bean ambiguity in prod.
 */
@Configuration
public class SessionRevocationConfig {

    @Bean
    @ConditionalOnMissingBean(SessionRevocationStore.class)
    public SessionRevocationStore inMemorySessionRevocationStore() {
        return new InMemorySessionRevocationStore();
    }
}
