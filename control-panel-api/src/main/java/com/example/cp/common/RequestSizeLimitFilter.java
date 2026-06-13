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
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.unit.DataSize;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Rejects oversized request bodies on the API surface before they are buffered/parsed.
 *
 * <p>The container's {@code spring.servlet.multipart.*} and {@code tomcat.max-http-form-post-size}
 * limits only bound multipart/form bodies — they do NOT cap a large {@code application/json} POST.
 * That left unauthenticated endpoints (notably {@code POST /api/v1/auth/login}) willing to fully
 * buffer and Jackson-parse a multi-megabyte JSON body, a cheap memory/CPU amplification vector.</p>
 *
 * <p>This filter enforces a small ceiling on the declared {@code Content-Length} for body-bearing
 * methods (POST/PUT/PATCH) and answers an oversized request with {@code 413 Payload Too Large}
 * (RFC-7807 problem JSON) <em>before</em> the body is read. It runs very early — right after the
 * correlation-id filter and ahead of the idempotency body-caching filter and Spring Security — so the
 * body is never buffered. Streaming requests that omit {@code Content-Length} (chunked) are allowed
 * through here and remain bounded by the container's own configured stream limits.</p>
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 5)
public class RequestSizeLimitFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(RequestSizeLimitFilter.class);

    private final long maxBytes;

    public RequestSizeLimitFilter(
            @Value("${app.request.max-body-size:256KB}") DataSize maxBodySize) {
        this.maxBytes = maxBodySize.toBytes();
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        if (isBodyBearing(request)) {
            long declared = request.getContentLengthLong();
            if (declared > maxBytes) {
                reject(request, response, declared);
                return;
            }
        }
        chain.doFilter(request, response);
    }

    private static boolean isBodyBearing(HttpServletRequest request) {
        String method = request.getMethod();
        return "POST".equalsIgnoreCase(method)
                || "PUT".equalsIgnoreCase(method)
                || "PATCH".equalsIgnoreCase(method);
    }

    private void reject(HttpServletRequest request, HttpServletResponse response, long declared)
            throws IOException {
        log.warn("Rejecting oversized request body: {} {} declared {} bytes (max {})",
                request.getMethod(), request.getRequestURI(), declared, maxBytes);
        response.setStatus(HttpStatus.PAYLOAD_TOO_LARGE.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        // RFC-7807 problem document. All fields are static / numeric here so hand-rolled JSON is safe.
        String json = "{\"type\":\"about:blank\",\"title\":\"Payload Too Large\",\"status\":"
                + HttpStatus.PAYLOAD_TOO_LARGE.value()
                + ",\"detail\":\"Request body exceeds the maximum allowed size of " + maxBytes + " bytes.\"}";
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        response.setContentLength(bytes.length);
        response.setHeader(HttpHeaders.CONNECTION, "close");
        response.getOutputStream().write(bytes);
    }
}
