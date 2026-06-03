package com.example.cp.webhooks;

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

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * One delivery attempt-set for a single ({@code subscription}, outbox {@code event}) pair. The unique
 * constraint {@code (subscription_id, event_id)} (see 15-webhooks.sql) makes the fan-out idempotent:
 * the scanner inserts a PENDING row per matching subscription with {@code ON CONFLICT DO NOTHING}, so
 * an event is never enqueued to the same subscription twice even across scheduler ticks or instances.
 *
 * <p>Lifecycle:
 * <ul>
 *   <li>{@code PENDING}   – awaiting (or retrying) POST; claimable once {@code next_attempt_at} is null
 *       or in the past.</li>
 *   <li>{@code DELIVERED} – endpoint returned 2xx; {@code delivered_at} set; never re-claimed.</li>
 *   <li>{@code FAILED}    – exceeded the max attempt count; terminal/poison; never re-claimed.</li>
 * </ul>
 */
@Entity
@Table(name = "webhook_deliveries")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class WebhookDelivery {

    public enum Status {
        PENDING, DELIVERED, FAILED
    }

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "subscription_id", nullable = false)
    private UUID subscriptionId;

    /** The {@code outbox_events.id} this delivery corresponds to. */
    @Column(name = "event_id", nullable = false)
    private UUID eventId;

    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private Status status = Status.PENDING;

    @Builder.Default
    @Column(name = "attempts", nullable = false)
    private int attempts = 0;

    /** Last HTTP status code observed from the endpoint (null until the first attempt completes). */
    @Column(name = "response_status")
    private Integer responseStatus;

    /** Earliest time the row may be claimed again; null means immediately eligible. */
    @Column(name = "next_attempt_at")
    private OffsetDateTime nextAttemptAt;

    /** Last failure message (truncated), retained for diagnostics on retry/poison rows. */
    @Column(name = "last_error")
    private String lastError;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "delivered_at")
    private OffsetDateTime deliveredAt;
}
