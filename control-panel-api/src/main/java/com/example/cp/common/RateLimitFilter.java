package com.example.cp.common;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.Refill;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-client-IP token-bucket rate limiter for the sensitive auth endpoints (login + password-reset).
 * Wired into the Spring Security chain (before {@code JwtAuthFilter}) by {@code SecurityConfig}.
 *
 * <p>Uses the bucket4j core API ({@link io.github.bucket4j.Bucket}) with an in-memory
 * {@link ConcurrentHashMap} of per-IP buckets. Returns a {@code 429} ProblemDetail
 * ({@code application/problem+json}) with a {@code Retry-After} header when a bucket is empty.</p>
 *
 * <p><b>Multi-instance note (follow-up #27):</b> this limiter is per-instance/in-memory. A
 * Redis-backed bucket4j proxy-manager is the horizontal-scaling follow-up; an in-memory limiter is
 * acceptable for the P0 hardening pass.</p>
 */
public class RateLimitFilter extends OncePerRequestFilter {

    private final ObjectMapper objectMapper;
    private final ConcurrentHashMap<String, Bucket> buckets = new ConcurrentHashMap<>();
    private final int capacity;
    private final int refillPerMinute;

    public RateLimitFilter(ObjectMapper objectMapper, int capacity, int refillPerMinute) {
        this.objectMapper = objectMapper;
        this.capacity = capacity;
        this.refillPerMinute = refillPerMinute;
    }

    /**
     * The filter is added to the chain broadly, so it self-restricts here to only the protected
     * POST auth endpoints. Everything else is skipped.
     */
    @Override
    protected boolean shouldNotFilter(HttpServletRequest req) {
        if (!HttpMethod.POST.matches(req.getMethod())) {
            return true;
        }
        String path = req.getServletPath();
        if (path == null || path.isEmpty()) {
            path = req.getRequestURI();
        }
        boolean protectedPath = path != null && (
                path.equals("/api/v1/auth/login")
                        || path.equals("/api/v1/auth/password-reset/request")
                        || path.equals("/api/v1/auth/password-reset/confirm"));
        return !protectedPath;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        // Key off the direct socket peer. Do NOT trust X-Forwarded-For here unless a fronting proxy
        // is known-good; the fronting nginx sets X-Real-IP for that purpose (see admin-ui/nginx.conf).
        String key = request.getRemoteAddr();
        Bucket bucket = buckets.computeIfAbsent(key, k -> newBucket());
        if (bucket.tryConsume(1)) {
            chain.doFilter(request, response);
            return;
        }
        writeTooManyRequests(response);
    }

    private Bucket newBucket() {
        Bandwidth limit = Bandwidth.classic(capacity, Refill.greedy(refillPerMinute, Duration.ofMinutes(1)));
        return Bucket.builder().addLimit(limit).build();
    }

    private void writeTooManyRequests(HttpServletResponse response) throws IOException {
        // Conservative Retry-After: time to refill one token (in seconds, at least 1).
        long retryAfterSeconds = refillPerMinute > 0 ? Math.max(1, 60L / refillPerMinute) : 60L;
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.TOO_MANY_REQUESTS, "Rate limit exceeded");
        pd.setTitle("Too Many Requests");
        response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setHeader(HttpHeaders.RETRY_AFTER, Long.toString(retryAfterSeconds));
        objectMapper.writeValue(response.getOutputStream(), pd);
    }
}
