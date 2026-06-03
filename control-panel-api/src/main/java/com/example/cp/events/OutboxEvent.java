package com.example.cp.events;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "outbox_events")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class OutboxEvent {

    /**
     * Delivery lifecycle for an outbox row. Persisted as its {@link #name()} into the
     * {@code outbox_events.status VARCHAR(16)} column (see 14-outbox-reliability.sql).
     *
     * <ul>
     *   <li>{@code PENDING}   – awaiting (or retrying) delivery; eligible to be claimed once
     *       {@code next_attempt_at} is null or in the past.</li>
     *   <li>{@code PUBLISHED} – successfully NOTIFY'd; {@code published_at} is set; never re-claimed.</li>
     *   <li>{@code FAILED}    – poison message: exceeded the max attempt count; quarantined for
     *       operator inspection and never re-claimed automatically.</li>
     * </ul>
     */
    public enum Status {
        PENDING, PUBLISHED, FAILED
    }

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "aggregate_type", nullable = false, length = 64)
    private String aggregateType;

    @Column(name = "aggregate_id", nullable = false, length = 128)
    private String aggregateId;

    @Column(name = "event_type", nullable = false, length = 128)
    private String eventType;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload_json", nullable = false, columnDefinition = "jsonb")
    private String payloadJson;

    @Column(name = "occurred_at", nullable = false)
    private OffsetDateTime occurredAt;

    @Column(name = "published_at")
    private OffsetDateTime publishedAt;

    /** Number of delivery attempts made so far (incremented on each failure). */
    @Builder.Default
    @Column(name = "attempts", nullable = false)
    private int attempts = 0;

    /** Earliest time the row may be claimed again; null means immediately eligible. */
    @Column(name = "next_attempt_at")
    private OffsetDateTime nextAttemptAt;

    /** Last failure message (truncated), retained for diagnostics on retry/poison rows. */
    @Column(name = "last_error")
    private String lastError;

    /** Delivery lifecycle status; defaults to {@link Status#PENDING} for newly enqueued rows. */
    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private Status status = Status.PENDING;
}
