package com.example.cp.auth;

import com.example.cp.common.RateLimitFilter;
import com.example.cp.common.TrustedProxyResolver;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Hermetic unit tests for {@link RateLimitFilter} driven through its public {@code doFilter}: the
 * MFA login endpoint is now throttled (finding P0-1) and buckets key on the resolved client IP so
 * clients behind a trusted proxy do not all share one bucket (finding P1-15). No Spring context.
 */
class RateLimitFilterTest {

    private TrustedProxyResolver proxyResolver;

    @BeforeEach
    void setUp() {
        proxyResolver = mock(TrustedProxyResolver.class);
    }

    /** capacity=2, refill=2/min so the third immediate POST against one bucket is throttled (429). */
    private RateLimitFilter filter() {
        return new RateLimitFilter(new ObjectMapper(), proxyResolver, 2, 2);
    }

    @Test
    void mfaLoginEndpoint_isThrottled() throws Exception {
        RateLimitFilter filter = filter();
        when(proxyResolver.resolveClientIp(any())).thenReturn("198.51.100.5");

        assertThat(passes(filter, "/api/v1/auth/mfa/login")).isTrue();
        assertThat(passes(filter, "/api/v1/auth/mfa/login")).isTrue();
        // Third immediate attempt exceeds capacity=2 -> 429, proving the endpoint is protected.
        assertThat(passes(filter, "/api/v1/auth/mfa/login")).isFalse();
    }

    @Test
    void unprotectedPath_isNeverThrottled() throws Exception {
        RateLimitFilter filter = filter();
        when(proxyResolver.resolveClientIp(any())).thenReturn("198.51.100.5");
        // Well past capacity=2, but this path is skipped entirely (shouldNotFilter -> true).
        for (int i = 0; i < 5; i++) {
            assertThat(passes(filter, "/api/v1/users")).isTrue();
        }
    }

    @Test
    void bucketsAreKeyedOnTheResolvedClientIp_notTheSharedProxyPeer() throws Exception {
        RateLimitFilter filter = filter();
        // The fronting-proxy socket peer is identical for everyone; the resolver returns the real,
        // distinct client IP per request. Bucketing on the resolved IP keeps them independent.
        when(proxyResolver.resolveClientIp(any())).thenReturn(
                "198.51.100.1", "198.51.100.1", "198.51.100.1", "198.51.100.2");

        assertThat(passes(filter, "/api/v1/auth/login")).isTrue();   // client .1
        assertThat(passes(filter, "/api/v1/auth/login")).isTrue();   // client .1
        assertThat(passes(filter, "/api/v1/auth/login")).isFalse();  // client .1 throttled
        // A DIFFERENT resolved client IP still has a full bucket despite the shared peer.
        assertThat(passes(filter, "/api/v1/auth/login")).isTrue();   // client .2
    }

    /** Drives the filter once against {@code servletPath}; true when the chain proceeded (not 429). */
    private boolean passes(RateLimitFilter filter, String servletPath) throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.setMethod("POST");
        req.setServletPath(servletPath);
        req.setRemoteAddr("10.0.0.9"); // shared fronting-proxy peer
        MockHttpServletResponse res = new MockHttpServletResponse();
        boolean[] proceeded = {false};
        FilterChain chain = (q, s) -> proceeded[0] = true;
        filter.doFilter(req, res, chain);
        return proceeded[0];
    }
}
