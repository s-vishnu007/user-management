package com.example.cp.auth;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;

/**
 * Provides exactly one {@link LoginRateLimiter} bean, chosen deterministically: the cluster-safe
 * Redis impl when a {@code RedisConnectionFactory} is present, the in-memory impl otherwise (test
 * profile / single instance). Uses {@link ObjectProvider} rather than {@code @ConditionalOnBean}
 * across user components (which is ordering-fragile and is intended for auto-configuration only).
 * Mirrors {@code SessionRevocationConfig}.
 */
@Configuration
public class LoginRateLimiterConfig {

    @Bean
    public LoginRateLimiter loginRateLimiter(
            ObjectProvider<RedisConnectionFactory> redisConnectionFactory,
            @Value("${app.auth.lockout.max-attempts:5}") int maxAttempts,
            @Value("${app.auth.lockout.window:PT5M}") Duration window,
            @Value("${app.auth.lockout.lockout:PT15M}") Duration lockoutDuration,
            @Value("${app.auth.lockout.per-ip-max:20}") int perIpMax) {
        RedisConnectionFactory cf = redisConnectionFactory.getIfAvailable();
        if (cf != null) {
            StringRedisTemplate template = new StringRedisTemplate(cf);
            template.afterPropertiesSet();
            return new RedisLoginRateLimiter(template, maxAttempts, window, lockoutDuration, perIpMax);
        }
        return new InMemoryLoginRateLimiter(maxAttempts, window, lockoutDuration, perIpMax);
    }
}
