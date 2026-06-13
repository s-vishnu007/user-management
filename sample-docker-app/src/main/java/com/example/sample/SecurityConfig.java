package com.example.sample;

import org.springframework.boot.actuate.autoconfigure.security.servlet.EndpointRequest;
import org.springframework.boot.actuate.health.HealthEndpoint;
import org.springframework.boot.actuate.info.InfoEndpoint;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Locks down the actuator surface for the reference app.
 *
 * <p>The starter publishes a custom {@code /actuator/license} endpoint that exposes the loaded
 * license's plan, (truncated) jti, permission/feature counts and signing kid, and the full
 * {@code /actuator/health} body can carry component-level internals. Exposing those anonymously
 * leaks operational and entitlement detail, so every actuator endpoint <em>except</em> the basic
 * liveness/readiness {@code health} probe and {@code info} requires authentication (HTTP Basic).
 *
 * <p>The functional {@code /api/**} endpoints are intentionally left open at the HTTP layer: their
 * access control is the license-verifier {@code @RequiresPermission} aspect (the whole point of the
 * demo), not authentication. {@code health} stays open so container orchestrators can probe it;
 * detailed health is still gated by {@code management.endpoint.health.show-details=when-authorized},
 * so an anonymous caller only sees the top-level UP/DOWN status.
 */
@Configuration
public class SecurityConfig {

    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE)
    public SecurityFilterChain actuatorSecurity(HttpSecurity http) throws Exception {
        http
                .securityMatcher(EndpointRequest.toAnyEndpoint())
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(EndpointRequest.to(HealthEndpoint.class, InfoEndpoint.class))
                        .permitAll()
                        .anyRequest().authenticated())
                .httpBasic(Customizer.withDefaults())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .csrf(csrf -> csrf.disable());
        return http.build();
    }

    @Bean
    @Order(Ordered.LOWEST_PRECEDENCE)
    public SecurityFilterChain appSecurity(HttpSecurity http) throws Exception {
        http
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .csrf(csrf -> csrf.disable());
        return http.build();
    }
}
