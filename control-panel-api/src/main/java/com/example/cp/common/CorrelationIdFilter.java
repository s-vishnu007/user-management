package com.example.cp.common;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Correlation-ID (request-ID) filter for distributed tracing across the HTTP -> DB -> outbox -> NOTIFY
 * hops (#71). Runs as the very first servlet filter on the global chain so every downstream log line
 * — including Spring Security, the JWT/API-key auth filters, and the access log — carries the same
 * {@code requestId}.
 *
 * <p>Behaviour per request:</p>
 * <ol>
 *   <li>Read the inbound {@code X-Request-Id} header; if present and well-formed, reuse it so a caller
 *       (gateway, load balancer, upstream service) can propagate its own trace identifier.</li>
 *   <li>Otherwise generate a fresh random UUID.</li>
 *   <li>Bind it to the SLF4J {@link MDC} under {@code "requestId"} (surfaced by {@code logback-spring.xml}
 *       in both the plain console pattern and the JSON encoder).</li>
 *   <li>Echo it back on the response {@code X-Request-Id} header so clients can correlate.</li>
 *   <li>Always clear the MDC key in a {@code finally} block so the value never leaks onto a pooled
 *       worker thread serving a later request.</li>
 * </ol>
 *
 * <p>Registered as a {@link Component}; Spring Boot auto-registers a bean of type
 * {@link OncePerRequestFilter} on the servlet container's filter chain. {@link Order} with
 * {@link Ordered#HIGHEST_PRECEDENCE} guarantees it executes ahead of the Spring Security
 * {@code FilterChainProxy} and the application's own auth filters.</p>
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CorrelationIdFilter extends OncePerRequestFilter {

    /** HTTP header carrying the inbound/outbound correlation identifier. */
    public static final String REQUEST_ID_HEADER = "X-Request-Id";

    /** SLF4J MDC key referenced by the logback pattern / JSON encoder. */
    public static final String MDC_KEY = "requestId";

    /** Upper bound to avoid unbounded attacker-controlled values entering logs / the MDC. */
    private static final int MAX_ID_LENGTH = 64;

    /** Conservative allow-list for a propagated id: identifiers, dashes, dots and colons only. */
    private static final Pattern SAFE_ID = Pattern.compile("[A-Za-z0-9._:-]{1," + MAX_ID_LENGTH + "}");

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String requestId = sanitize(request.getHeader(REQUEST_ID_HEADER));
        if (requestId == null) {
            requestId = UUID.randomUUID().toString();
        }
        MDC.put(MDC_KEY, requestId);
        // Echo before proceeding so the header is present even if a downstream filter commits the
        // response early (e.g. an auth filter returning 401).
        response.setHeader(REQUEST_ID_HEADER, requestId);
        try {
            chain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_KEY);
        }
    }

    /**
     * @return a trimmed, length-and-charset-validated id, or {@code null} when the header is absent,
     *         blank, or fails the safe-id allow-list (in which case a fresh UUID is generated).
     */
    private static String sanitize(String raw) {
        if (raw == null) {
            return null;
        }
        String trimmed = raw.trim();
        if (trimmed.isEmpty() || !SAFE_ID.matcher(trimmed).matches()) {
            return null;
        }
        return trimmed;
    }
}
