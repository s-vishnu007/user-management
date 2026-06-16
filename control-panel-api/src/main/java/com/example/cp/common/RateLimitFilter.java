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
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Per-client-IP token-bucket rate limiter for the sensitive auth endpoints (login, the two-step MFA
 * login, and password-reset). Wired into the Spring Security chain (before {@code JwtAuthFilter}) by
 * {@code SecurityConfig}.
 *
 * <p>Uses the bucket4j core API ({@link io.github.bucket4j.Bucket}) with an in-memory, <b>bounded</b>
 * access-ordered LRU map of per-IP buckets ({@link #MAX_BUCKETS} entries, evicting the
 * least-recently-used). Returns a {@code 429} ProblemDetail ({@code application/problem+json}) with a
 * {@code Retry-After} header when a bucket is empty.</p>
 *
 * <p>The client IP is resolved via {@link TrustedProxyResolver#resolveClientIp(HttpServletRequest)},
 * so behind the documented TLS-terminating proxy each end-user gets their own bucket rather than the
 * whole user base collapsing into the single proxy-IP bucket (which would let 10 unauthenticated
 * POSTs/min lock out login for everyone).</p>
 *
 * <p><b>Multi-instance note (follow-up #27):</b> this limiter is per-instance/in-memory. A
 * Redis-backed bucket4j proxy-manager is the horizontal-scaling follow-up; the cluster-wide
 * brute-force lockout is already handled by {@code LoginRateLimiter} (Redis-backed in prod).</p>
 */
public class RateLimitFilter extends OncePerRequestFilter {

    /** Upper bound on tracked per-IP buckets; protects against unbounded memory growth / IP-spray. */
    static final int MAX_BUCKETS = 50_000;

    private static final Set<String> PROTECTED_PATHS = Set.of(
            "/api/v1/auth/login",
            "/api/v1/auth/mfa/login",
            "/api/v1/auth/register",
            "/api/v1/auth/verify-email",
            "/api/v1/auth/verify-email/resend",
            "/api/v1/auth/password-reset/request",
            "/api/v1/auth/password-reset/confirm");

    private final ObjectMapper objectMapper;
    private final TrustedProxyResolver proxyResolver;
    private final int capacity;
    private final int refillPerMinute;

    /**
     * Access-ordered LRU bounded to {@link #MAX_BUCKETS}. Guarded by its own monitor; lookups are
     * brief (map get/put), so contention on the auth endpoints is negligible compared to the
     * downstream bcrypt/Redis work.
     */
    private final Map<String, Bucket> buckets;

    public RateLimitFilter(ObjectMapper objectMapper, TrustedProxyResolver proxyResolver,
                           int capacity, int refillPerMinute) {
        this.objectMapper = objectMapper;
        this.proxyResolver = proxyResolver;
        this.capacity = capacity;
        this.refillPerMinute = refillPerMinute;
        this.buckets = new LinkedHashMap<>(256, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, Bucket> eldest) {
                return size() > MAX_BUCKETS;
            }
        };
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
        return path == null || !PROTECTED_PATHS.contains(path);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        // Resolve the real client IP (honors X-Forwarded-For only from a configured trusted proxy),
        // so each end-user gets their own bucket rather than sharing the fronting-proxy IP.
        String key = proxyResolver.resolveClientIp(request);
        if (key == null || key.isBlank()) {
            key = request.getRemoteAddr();
        }
        // Separate token buckets per endpoint-CLASS (login vs signup vs reset) so a burst on one class
        // from a shared-NAT/proxy IP cannot starve another class for co-located users (re-audit #9).
        key = key + '|' + bucketGroup(requestPath(request));
        Bucket bucket;
        synchronized (buckets) {
            bucket = buckets.computeIfAbsent(key, k -> newBucket());
        }
        if (bucket.tryConsume(1)) {
            chain.doFilter(request, response);
            return;
        }
        writeTooManyRequests(response);
    }

    /** The servlet path (falls back to the request URI), mirroring {@link #shouldNotFilter}. */
    private static String requestPath(HttpServletRequest req) {
        String path = req.getServletPath();
        if (path == null || path.isEmpty()) {
            path = req.getRequestURI();
        }
        return path == null ? "" : path;
    }

    /** Coarse endpoint class so signup/reset traffic does not consume the login bucket and vice-versa. */
    private static String bucketGroup(String path) {
        if (path.contains("/password-reset/")) {
            return "reset";
        }
        if (path.equals("/api/v1/auth/login") || path.equals("/api/v1/auth/mfa/login")) {
            return "login";
        }
        return "signup";
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
