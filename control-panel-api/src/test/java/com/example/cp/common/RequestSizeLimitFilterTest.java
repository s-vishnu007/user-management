package com.example.cp.common;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.util.unit.DataSize;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Unit tests for {@link RequestSizeLimitFilter}: an oversized {@code Content-Length} on a body-bearing
 * method is rejected with {@code 413} before the chain proceeds; everything within the limit (and any
 * non-body method, and chunked requests without a declared length) passes through untouched.
 */
class RequestSizeLimitFilterTest {

    private final RequestSizeLimitFilter filter = new RequestSizeLimitFilter(DataSize.ofKilobytes(256));

    @Test
    void oversizedJsonPost_rejectedWith413_withoutInvokingChain() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/v1/auth/login");
        req.setContentType("application/json");
        // MockHttpServletRequest derives getContentLengthLong() from the body bytes, so set a body
        // that exceeds the 256 KB limit (it is never read — the filter rejects on declared length).
        req.setContent(new byte[3 * 1024 * 1024]); // 3 MB
        MockHttpServletResponse resp = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(req, resp, chain);

        assertThat(resp.getStatus()).isEqualTo(413);
        assertThat(resp.getContentType()).isEqualTo("application/problem+json");
        assertThat(resp.getContentAsString()).contains("Payload Too Large");
        verify(chain, never()).doFilter(req, resp);
    }

    @Test
    void withinLimitPost_passesThrough() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/v1/auth/login");
        req.setContentType("application/json");
        req.setContent("{\"email\":\"a@b.c\",\"password\":\"x\"}".getBytes());
        MockHttpServletResponse resp = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(req, resp, chain);

        // Chain proceeded (request reached the downstream filter/servlet).
        assertThat(chain.getRequest()).isSameAs(req);
        assertThat(resp.getStatus()).isEqualTo(200);
    }

    @Test
    void getRequest_isNeverSizeChecked() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/v1/events");
        req.setContent(new byte[512 * 1024]); // over the 256 KB limit, but GET is not body-bearing so it is never checked
        MockHttpServletResponse resp = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(req, resp, chain);

        assertThat(chain.getRequest()).isSameAs(req);
        assertThat(resp.getStatus()).isEqualTo(200);
    }

    @Test
    void chunkedPostWithoutContentLength_passesThrough() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/v1/auth/login");
        req.setContentType("application/json");
        // No content set => getContentLengthLong() returns -1 (unknown), so the declared-length guard
        // does not trip; such streaming requests are bounded by the container's own limits.
        MockHttpServletResponse resp = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(req, resp, chain);

        assertThat(chain.getRequest()).isSameAs(req);
    }
}
