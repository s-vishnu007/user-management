package com.example.cp.common;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * HTTP access-log filter (#62, #71) gated by {@code app.observability.access-log.enabled}.
 *
 * <p>Until now {@code app.observability.access-log.enabled} was read by no code, so the YAML
 * advertised an access log that did not exist. This filter implements it: one structured log line
 * per completed request carrying method, path, status, latency and the resolved client IP. Because
 * it runs just after {@link CorrelationIdFilter} (which is {@link Ordered#HIGHEST_PRECEDENCE}), the
 * {@code requestId} MDC field is already bound, so every access line correlates with the rest of the
 * request's logs (plain {@code [requestId]} in dev, a top-level JSON field in prod).</p>
 *
 * <p>Behaviour:</p>
 * <ul>
 *   <li>When {@code app.observability.access-log.enabled=false} the filter short-circuits via
 *       {@link #shouldNotFilter} and adds zero per-request overhead.</li>
 *   <li>Actuator probe traffic ({@code /actuator/health/**}) is excluded so liveness/readiness polls
 *       (typically every few seconds) do not flood the log.</li>
 *   <li>The line is emitted in a {@code finally} block so it is written even when a downstream filter
 *       or handler throws, and the exception is re-thrown unchanged.</li>
 *   <li>The client IP is resolved via {@link TrustedProxyResolver} so it matches the audit/auth view
 *       of the caller (honours {@code X-Forwarded-For} only behind a configured trusted proxy).</li>
 * </ul>
 *
 * <p>Registered as a {@link Component} so Spring Boot auto-registers it on the servlet filter chain.
 * It is intentionally ordered just after {@link CorrelationIdFilter} and ahead of the Spring Security
 * {@code FilterChainProxy} so the access line is produced for unauthenticated/rejected requests too
 * (e.g. a 401 from the auth filters), with the correlation id already in scope.</p>
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class AccessLogFilter extends OncePerRequestFilter {

    /** Dedicated logger so operators can route/level the access log independently of app logs. */
    private static final Logger log = LoggerFactory.getLogger("com.example.cp.access");

    private final TrustedProxyResolver clientIpResolver;
    private final boolean enabled;

    public AccessLogFilter(TrustedProxyResolver clientIpResolver,
                           @Value("${app.observability.access-log.enabled:true}") boolean enabled) {
        this.clientIpResolver = clientIpResolver;
        this.enabled = enabled;
    }

    /** Skip entirely when disabled, and never log the high-frequency health-probe endpoints. */
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        if (!enabled) {
            return true;
        }
        String path = path(request);
        return path != null && path.startsWith("/actuator/health");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        long startNanos = System.nanoTime();
        try {
            chain.doFilter(request, response);
        } finally {
            long latencyMs = (System.nanoTime() - startNanos) / 1_000_000L;
            // requestId is supplied by CorrelationIdFilter via MDC and surfaced by logback-spring.xml,
            // so it is intentionally not duplicated into the message itself.
            log.info("access method={} path={} status={} duration_ms={} client_ip={}",
                    request.getMethod(),
                    path(request),
                    response.getStatus(),
                    latencyMs,
                    clientIpResolver.resolveClientIp(request));
        }
    }

    /** Prefer the parsed servlet path; fall back to the raw URI for non-dispatcher mappings. */
    private static String path(HttpServletRequest request) {
        String path = request.getServletPath();
        if (path == null || path.isEmpty()) {
            path = request.getRequestURI();
        }
        return path;
    }
}
