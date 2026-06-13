package com.example.cp.common;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Hermetic unit tests for {@link AccessLogFilter}: the access log is gated by
 * {@code app.observability.access-log.enabled} (previously dead config), emits one line per request
 * carrying method/path/status/latency/client-IP, correlates via the {@code requestId} MDC, excludes
 * health-probe traffic, and is written even when the chain throws. No Spring context — a logback
 * {@link ListAppender} captures the structured access line.
 */
class AccessLogFilterTest {

    private TrustedProxyResolver proxyResolver;
    private ListAppender<ILoggingEvent> appender;
    private Logger accessLogger;

    @BeforeEach
    void setUp() {
        proxyResolver = mock(TrustedProxyResolver.class);
        when(proxyResolver.resolveClientIp(any())).thenReturn("203.0.113.7");

        accessLogger = (Logger) LoggerFactory.getLogger("com.example.cp.access");
        appender = new ListAppender<>();
        appender.start();
        accessLogger.addAppender(appender);
        accessLogger.setLevel(Level.INFO);
        MDC.clear();
    }

    @AfterEach
    void tearDown() {
        accessLogger.detachAppender(appender);
        MDC.clear();
    }

    private AccessLogFilter filter(boolean enabled) {
        return new AccessLogFilter(proxyResolver, enabled);
    }

    @Test
    @DisplayName("emits one access line with method/path/status/client-ip when enabled")
    void emitsAccessLineWhenEnabled() throws Exception {
        AccessLogFilter filter = filter(true);
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/v1/orgs");
        req.setServletPath("/api/v1/orgs");
        MockHttpServletResponse res = new MockHttpServletResponse();
        res.setStatus(200);
        FilterChain chain = mock(FilterChain.class);

        // shouldNotFilter governs OncePerRequestFilter; assert it permits this request, then run the body.
        assertThat(filter.shouldNotFilter(req)).isFalse();
        filter.doFilterInternal(req, res, chain);

        verify(chain, times(1)).doFilter(req, res);
        assertThat(appender.list).hasSize(1);
        String msg = appender.list.get(0).getFormattedMessage();
        assertThat(msg).contains("method=GET")
                .contains("path=/api/v1/orgs")
                .contains("status=200")
                .contains("client_ip=203.0.113.7")
                .contains("duration_ms=");
    }

    @Test
    @DisplayName("disabled flag short-circuits via shouldNotFilter (no overhead, no line)")
    void skipsEntirelyWhenDisabled() {
        AccessLogFilter filter = filter(false);
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/v1/orgs");
        req.setServletPath("/api/v1/orgs");

        assertThat(filter.shouldNotFilter(req)).isTrue();
        assertThat(appender.list).isEmpty();
    }

    @Test
    @DisplayName("health-probe traffic is excluded even when enabled")
    void excludesHealthProbes() {
        AccessLogFilter filter = filter(true);
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/actuator/health/readiness");
        req.setServletPath("/actuator/health/readiness");

        assertThat(filter.shouldNotFilter(req)).isTrue();
    }

    @Test
    @DisplayName("the access line carries the requestId bound in the MDC by CorrelationIdFilter")
    void includesRequestIdFromMdc() throws Exception {
        AccessLogFilter filter = filter(true);
        MDC.put(CorrelationIdFilter.MDC_KEY, "test-request-id-123");
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/v1/auth/login");
        req.setServletPath("/api/v1/auth/login");
        MockHttpServletResponse res = new MockHttpServletResponse();
        res.setStatus(401);
        FilterChain chain = mock(FilterChain.class);

        filter.doFilterInternal(req, res, chain);

        assertThat(appender.list).hasSize(1);
        ILoggingEvent event = appender.list.get(0);
        assertThat(event.getMDCPropertyMap()).containsEntry(CorrelationIdFilter.MDC_KEY, "test-request-id-123");
        assertThat(event.getFormattedMessage()).contains("status=401").contains("path=/api/v1/auth/login");
    }

    @Test
    @DisplayName("still logs the access line and re-throws when the chain throws")
    void logsAndRethrowsOnDownstreamFailure() throws Exception {
        AccessLogFilter filter = filter(true);
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/v1/orgs");
        req.setServletPath("/api/v1/orgs");
        MockHttpServletResponse res = new MockHttpServletResponse();
        res.setStatus(500);
        FilterChain chain = mock(FilterChain.class);
        doThrow(new ServletException("boom")).when(chain).doFilter(any(), any());

        assertThatThrownBy(() -> filter.doFilterInternal(req, res, chain))
                .isInstanceOf(ServletException.class)
                .hasMessage("boom");

        // finally-block guarantees the access line is emitted despite the failure.
        assertThat(appender.list).hasSize(1);
        assertThat(appender.list.get(0).getFormattedMessage()).contains("status=500");
    }

    @Test
    @DisplayName("falls back to the request URI when the servlet path is empty")
    void fallsBackToRequestUri() throws IOException, ServletException {
        AccessLogFilter filter = filter(true);
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/raw/uri");
        req.setServletPath("");
        MockHttpServletResponse res = new MockHttpServletResponse();
        res.setStatus(200);
        FilterChain chain = mock(FilterChain.class);

        filter.doFilterInternal(req, res, chain);

        assertThat(appender.list).hasSize(1);
        assertThat(appender.list.get(0).getFormattedMessage()).contains("path=/raw/uri");
    }
}
