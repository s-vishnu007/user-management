package com.example.cp.common;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.util.ContentCachingResponseWrapper;
import org.springframework.web.util.WebUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;

/**
 * Cross-cutting {@code Idempotency-Key} support for mutating HTTP endpoints (#81).
 *
 * <p>Active only when a request carries a non-blank {@code Idempotency-Key} header on a POST/PUT/PATCH
 * (zero behaviour change for every other request). Behaviour:</p>
 * <ol>
 *   <li><b>preHandle</b> — resolve the caller, hash the request body, and look up
 *       {@code (idem_key, method, path, actor)}:
 *     <ul>
 *       <li>a <em>completed</em> record with a matching body hash → replay its stored status + body
 *           (with {@code Idempotency-Replayed: true}) and short-circuit the handler;</li>
 *       <li>a completed record with a DIFFERENT body hash → {@code 422} (key reused for a different
 *           request);</li>
 *       <li>an <em>in-flight</em> record (originating request not finished) → {@code 409};</li>
 *       <li>nothing yet → insert an in-flight row (the UNIQUE constraint makes a concurrent duplicate
 *           lose the race and fall back to the replay/409 path) and let the handler run.</li>
 *     </ul></li>
 *   <li><b>afterCompletion</b> — capture the real response status + body and persist them onto the
 *       in-flight row so a later retry replays the same result. If the handler threw (no usable
 *       response) the in-flight row is deleted so the operation can be retried cleanly.</li>
 * </ol>
 *
 * <p>The interceptor itself holds no per-request mutable state; the in-flight row id is stashed as a
 * request attribute so the same interceptor instance is safe across concurrent requests. Persistence
 * is delegated to {@link Store} (its own transactional bean) so each idempotency write commits
 * independently of the handler's business transaction.</p>
 */
public class IdempotencyInterceptor implements HandlerInterceptor {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyInterceptor.class);

    /** Request header carrying the client-supplied idempotency key. */
    public static final String HEADER = "Idempotency-Key";

    /** Response header set to {@code true} on a replayed response. */
    public static final String REPLAYED_HEADER = "Idempotency-Replayed";

    /** Max accepted key length (matches the {@code idem_key} column). */
    private static final int MAX_KEY_LENGTH = 255;

    /** Request attribute holding the in-flight {@link IdempotencyKey} row id created in preHandle. */
    private static final String ATTR_ROW_ID = IdempotencyInterceptor.class.getName() + ".rowId";

    private final Store store;
    private final Clock clock;
    private final java.time.Duration ttl;

    public IdempotencyInterceptor(Store store, Clock clock, java.time.Duration ttl) {
        this.store = store;
        this.clock = clock;
        this.ttl = ttl != null ? ttl : java.time.Duration.ofDays(1);
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws Exception {
        if (!isMutating(request)) {
            return true;
        }
        String key = normalizeKey(request.getHeader(HEADER));
        if (key == null) {
            return true; // No header (or blank/over-long) => idempotency OFF for this request.
        }

        String method = request.getMethod();
        String path = pathOf(request);
        String actor = resolveActor();
        String requestHash = sha256Hex(readBody(request));

        Optional<IdempotencyKey> existing = store.find(key, method, path, actor);
        if (existing.isPresent() && !isExpired(existing.get())) {
            return handleExisting(existing.get(), requestHash, response);
        }
        if (existing.isPresent()) {
            // The prior record outlived its TTL: drop it so this request can re-claim the key.
            store.delete(existing.get().getId());
        }

        // No live record: try to claim the key by inserting an in-flight row.
        try {
            UUID rowId = store.insertInFlight(key, method, path, actor, requestHash, now());
            request.setAttribute(ATTR_ROW_ID, rowId);
            return true; // Proceed to the handler; afterCompletion will store the response.
        } catch (DataIntegrityViolationException raceLost) {
            // A concurrent duplicate won the UNIQUE race. Re-read and replay/conflict accordingly.
            Optional<IdempotencyKey> winner = store.find(key, method, path, actor);
            if (winner.isPresent() && !isExpired(winner.get())) {
                return handleExisting(winner.get(), requestHash, response);
            }
            // The winner rolled back between our re-read and its commit; treat as transient conflict.
            writeConflict(response);
            return false;
        }
    }

    /** A record is expired once it has lived longer than the configured TTL. */
    private boolean isExpired(IdempotencyKey record) {
        OffsetDateTime created = record.getCreatedAt();
        return created != null && created.plus(ttl).isBefore(now());
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                                Object handler, Exception ex) {
        Object attr = request.getAttribute(ATTR_ROW_ID);
        if (!(attr instanceof UUID rowId)) {
            return; // Not an idempotent request we claimed (no header, or a replay short-circuit).
        }
        try {
            int status = response.getStatus();
            // A server-side failure (unhandled exception, or any 5xx the advice rendered) is treated as
            // transient: release the claim so a retry can genuinely re-execute the mutation rather than
            // being pinned forever to a one-off error. 2xx/3xx and deterministic 4xx client errors are
            // cached so a retry replays the identical, reproducible outcome.
            if (ex != null || status >= 500) {
                store.delete(rowId);
                return;
            }
            ContentCachingResponseWrapper cached = WebUtils.getNativeResponse(response,
                    ContentCachingResponseWrapper.class);
            String body = cached == null ? null
                    : new String(cached.getContentAsByteArray(), StandardCharsets.UTF_8);
            store.complete(rowId, status, body);
        } catch (Exception persistFailure) {
            // Never let an idempotency bookkeeping failure mask the real response; just release the
            // claim so the in-flight row cannot wedge future retries.
            log.warn("Failed to persist idempotency result for {}: {}", rowId, persistFailure.getMessage());
            try {
                store.delete(rowId);
            } catch (Exception ignored) {
                // best-effort
            }
        }
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    private boolean handleExisting(IdempotencyKey record, String requestHash, HttpServletResponse response)
            throws Exception {
        if (!record.isCompleted()) {
            // The originating request is still running (or crashed mid-flight): refuse the duplicate.
            writeConflict(response);
            return false;
        }
        if (record.getRequestHash() != null && requestHash != null
                && !record.getRequestHash().equals(requestHash)) {
            // Same key, different payload: a client bug we must surface rather than silently replay.
            writeProblem(response, HttpStatus.UNPROCESSABLE_ENTITY, "Idempotency-Key reused",
                    "This Idempotency-Key was already used for a request with a different body.");
            return false;
        }
        replay(record, response);
        return false;
    }

    private void replay(IdempotencyKey record, HttpServletResponse response) throws Exception {
        response.setStatus(record.getResponseStatus());
        response.setHeader(REPLAYED_HEADER, "true");
        String body = record.getResponseBody();
        if (body != null && !body.isEmpty()) {
            // Replays of our JSON APIs are JSON; default to JSON when we cannot know the original type.
            if (response.getContentType() == null) {
                response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            }
            // Write through the (caching) response; the body-caching filter copies it to the real
            // response (and sets Content-Length) in its finally block, so do NOT flush/commit here.
            response.getOutputStream().write(body.getBytes(StandardCharsets.UTF_8));
        }
    }

    private void writeConflict(HttpServletResponse response) throws Exception {
        writeProblem(response, HttpStatus.CONFLICT, "Idempotency conflict",
                "A request with this Idempotency-Key is already in progress.");
    }

    private void writeProblem(HttpServletResponse response, HttpStatus status, String title, String detail)
            throws Exception {
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        // Minimal hand-rolled RFC-7807 JSON to avoid coupling the interceptor to an ObjectMapper; all
        // fields are static, properly-escaped strings here so this is safe.
        String json = "{\"type\":\"about:blank\",\"title\":" + jsonString(title)
                + ",\"status\":" + status.value()
                + ",\"detail\":" + jsonString(detail) + "}";
        // Written through the caching response wrapper; the filter's copyBodyToResponse() commits it.
        response.getOutputStream().write(json.getBytes(StandardCharsets.UTF_8));
    }

    private static String jsonString(String s) {
        StringBuilder sb = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.append('"').toString();
    }

    private static boolean isMutating(HttpServletRequest request) {
        return HttpMethod.POST.matches(request.getMethod())
                || HttpMethod.PUT.matches(request.getMethod())
                || HttpMethod.PATCH.matches(request.getMethod());
    }

    private static String normalizeKey(String raw) {
        if (raw == null) {
            return null;
        }
        String trimmed = raw.trim();
        if (trimmed.isEmpty() || trimmed.length() > MAX_KEY_LENGTH) {
            return null;
        }
        return trimmed;
    }

    private static String pathOf(HttpServletRequest request) {
        String path = request.getServletPath();
        if (path == null || path.isEmpty()) {
            path = request.getRequestURI();
        }
        return path;
    }

    /**
     * Caller scope for the natural key. Human principal -> user id; api-key principal -> bound org id;
     * unauthenticated -> {@code "anonymous"}. Keeping callers disjoint stops one caller replaying
     * another's stored response.
     */
    private static String resolveActor() {
        Optional<AuthenticatedUser> user = SecurityUtils.currentUser();
        if (user.isPresent()) {
            AuthenticatedUser u = user.get();
            if (u.userId() != null) {
                return u.userId().toString();
            }
            if (u.isApiKey() && u.apiKeyOrgId() != null) {
                return "apikey:" + u.apiKeyOrgId();
            }
        }
        return "anonymous";
    }

    private byte[] readBody(HttpServletRequest request) {
        IdempotencyConfig.CachedBodyHttpServletRequest cached = WebUtils.getNativeRequest(request,
                IdempotencyConfig.CachedBodyHttpServletRequest.class);
        if (cached == null) {
            return new byte[0];
        }
        return cached.getCachedBody();
    }

    private static String sha256Hex(byte[] body) {
        if (body == null) {
            body = new byte[0];
        }
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(body));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private OffsetDateTime now() {
        return OffsetDateTime.now(clock);
    }

    /**
     * Transactional persistence boundary for idempotency rows. Each method runs in its OWN
     * transaction ({@code REQUIRES_NEW}) so claiming/completing a key commits independently of the
     * business handler's transaction — a rolled-back handler must not also roll back the
     * already-committed in-flight claim (and vice-versa).
     *
     * <p>A standalone {@link org.springframework.stereotype.Component} (rather than a {@code @Bean}
     * of {@link IdempotencyConfig}) so it is a first-class proxied bean — its {@code REQUIRES_NEW}
     * boundaries are honoured — without creating a self-referential dependency on the configurer that
     * registers the interceptor.</p>
     */
    @org.springframework.stereotype.Component
    public static class Store {

        private final IdempotencyKeyRepository repository;

        public Store(IdempotencyKeyRepository repository) {
            this.repository = repository;
        }

        @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
        public Optional<IdempotencyKey> find(String key, String method, String path, String actor) {
            return repository.findByIdemKeyAndMethodAndPathAndActorUserId(key, method, path, actor);
        }

        /**
         * Inserts an in-flight row (response_status NULL) and returns its id. A concurrent duplicate
         * trips the UNIQUE constraint and surfaces here as {@link DataIntegrityViolationException}.
         */
        @Transactional(propagation = Propagation.REQUIRES_NEW)
        public UUID insertInFlight(String key, String method, String path, String actor,
                                   String requestHash, OffsetDateTime createdAt) {
            IdempotencyKey row = IdempotencyKey.builder()
                    .id(Ids.newId())
                    .idemKey(key)
                    .method(method)
                    .path(path)
                    .actorUserId(actor)
                    .requestHash(requestHash)
                    .createdAt(createdAt)
                    .build();
            // saveAndFlush so the UNIQUE violation surfaces now (inside this tx), not at a later flush.
            return repository.saveAndFlush(row).getId();
        }

        @Transactional(propagation = Propagation.REQUIRES_NEW)
        public void complete(UUID rowId, int status, String body) {
            repository.findById(rowId).ifPresent(row -> {
                row.setResponseStatus(status);
                row.setResponseBody(body);
                repository.save(row);
            });
        }

        @Transactional(propagation = Propagation.REQUIRES_NEW)
        public void delete(UUID rowId) {
            repository.deleteById(rowId);
        }
    }
}
