package com.example.cp.common;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Persisted record of one idempotent mutating request (#81).
 *
 * <p>Identity is the natural key {@code (idem_key, method, path, actor_user_id)} (a DB UNIQUE
 * constraint, mirrored by the JPA fields). The lifecycle is two-phase:</p>
 * <ol>
 *   <li><b>In-flight</b> — {@link IdempotencyInterceptor#preHandle} inserts a row with
 *       {@code response_status == null} before the handler runs. The UNIQUE constraint makes a
 *       concurrent duplicate fail fast (treated as an in-flight conflict).</li>
 *   <li><b>Completed</b> — {@code afterCompletion} fills in {@link #responseStatus} and
 *       {@link #responseBody}. A later retry that finds a completed row replays it verbatim.</li>
 * </ol>
 *
 * <p>{@link #requestHash} is the SHA-256 (hex) of the request body captured on the first request so a
 * retry that reuses the same key with a DIFFERENT body can be rejected rather than silently replaying
 * an unrelated response. Rows expire at {@code createdAt + app.idempotency.ttl}.</p>
 */
@Entity
@Table(name = "idempotency_keys")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class IdempotencyKey {

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    /** The client-supplied {@code Idempotency-Key} header value. */
    @Column(name = "idem_key", nullable = false, length = 255)
    private String idemKey;

    @Column(name = "method", nullable = false, length = 10)
    private String method;

    @Column(name = "path", nullable = false, length = 512)
    private String path;

    /**
     * Caller scope: the human user id, the api-key's bound org id, or the literal {@code "anonymous"}.
     * Stored as text so a single column covers all principal kinds; part of the unique key so one
     * caller can never replay another caller's stored response.
     */
    @Column(name = "actor_user_id", nullable = false, length = 64)
    private String actorUserId;

    /** SHA-256 (hex) of the request body captured on the first request. */
    @Column(name = "request_hash", length = 64)
    private String requestHash;

    /** Final HTTP status; {@code null} while the originating request is still in flight. */
    @Column(name = "response_status")
    private Integer responseStatus;

    @Column(name = "response_body", columnDefinition = "text")
    private String responseBody;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    /** A record is replayable once its originating request has completed (status was filled in). */
    public boolean isCompleted() {
        return responseStatus != null;
    }
}
