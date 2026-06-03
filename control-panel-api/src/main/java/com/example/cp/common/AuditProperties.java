package com.example.cp.common;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Configuration bound to the {@code app.audit} prefix.
 *
 * <ul>
 *   <li>{@code trusted-proxies} — list of CIDR blocks whose {@code X-Forwarded-For} header is
 *       trusted. Empty (the default) means XFF is never trusted and the direct socket peer
 *       ({@code getRemoteAddr()}) is always used. Consumed by {@link TrustedProxyResolver}.</li>
 *   <li>{@code fail-closed-actions} — allowlist of action codes for which audit writes must be
 *       transactional/fail-closed (a write failure aborts the business transaction). Consumed by
 *       {@code AuditInterceptor} and high-value service call sites.</li>
 * </ul>
 *
 * <p>Self-registered as a {@link Component} so it is bound without requiring
 * {@code @EnableConfigurationProperties} / {@code @ConfigurationPropertiesScan} on the
 * application class.</p>
 */
@Component
@ConfigurationProperties(prefix = "app.audit")
public class AuditProperties {

    /** CIDR blocks whose X-Forwarded-For header is trusted. Empty => never trust XFF. */
    private List<String> trustedProxies = new ArrayList<>();

    /** Action codes whose audit writes must be transactional (fail-closed). */
    private List<String> failClosedActions = new ArrayList<>();

    public List<String> getTrustedProxies() {
        return trustedProxies;
    }

    public void setTrustedProxies(List<String> trustedProxies) {
        this.trustedProxies = trustedProxies != null ? trustedProxies : new ArrayList<>();
    }

    public List<String> getFailClosedActions() {
        return failClosedActions;
    }

    public void setFailClosedActions(List<String> failClosedActions) {
        this.failClosedActions = failClosedActions != null ? failClosedActions : new ArrayList<>();
    }
}
