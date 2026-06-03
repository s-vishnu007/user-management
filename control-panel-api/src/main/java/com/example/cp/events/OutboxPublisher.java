package com.example.cp.events;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Multi-instance-safe transactional-outbox publisher.
 *
 * <p>On each scheduled tick it claims a bounded batch of due {@code PENDING} rows with
 * {@code SELECT ... FOR UPDATE SKIP LOCKED}, ordered by {@code occurred_at}. The row locks are held
 * for the lifetime of the surrounding transaction, so a sibling instance running the same query
 * skips the rows this instance already grabbed — no two instances publish the same event, and no
 * instance blocks waiting on another's locked rows.
 *
 * <p>Delivery itself is a parameterized {@code pg_notify(channel, payload)} (no SQL string
 * interpolation of channel/payload). Because {@code NOTIFY} is buffered until the transaction
 * commits, the {@code published_at}/{@code status} updates and the notification are atomic: either
 * both happen or neither does. A row is only marked {@code PUBLISHED} after a successful
 * notify-and-update within the same committed transaction.
 *
 * <p>Failure handling (per row, isolated so one bad row does not poison the batch):
 * <ul>
 *   <li>increment {@code attempts}, record the (truncated) {@code last_error};</li>
 *   <li>schedule the next attempt with capped exponential backoff via {@code next_attempt_at};</li>
 *   <li>after {@link #MAX_ATTEMPTS} attempts, mark the row {@code FAILED} (poison) and log loudly so
 *       it is quarantined rather than retried forever.</li>
 * </ul>
 */
@Component("eventsOutboxPublisher")
public class OutboxPublisher {

    private static final Logger log = LoggerFactory.getLogger(OutboxPublisher.class);

    private static final int BATCH_SIZE = 100;
    private static final String CHANNEL = "cp_events";

    /** After this many failed attempts a row is quarantined as a poison message ({@code FAILED}). */
    static final int MAX_ATTEMPTS = 10;
    /** Backoff base: delay before retry n is BASE * 2^(n-1), capped at {@link #BACKOFF_CAP}. */
    static final Duration BACKOFF_BASE = Duration.ofSeconds(5);
    static final Duration BACKOFF_CAP = Duration.ofHours(1);
    /** last_error is bounded so a giant exception message cannot bloat the row. */
    private static final int MAX_ERROR_LEN = 2000;

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper = new ObjectMapper();

    public OutboxPublisher(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Scheduled(fixedDelay = 5000L)
    @Transactional
    public void publishBatch() {
        try {
            // Claim only due, not-yet-delivered rows; FOR UPDATE SKIP LOCKED makes the claim
            // exclusive across instances. Ordering by occurred_at preserves per-claim ordering.
            List<ClaimedRow> rows = jdbc.query(
                    "SELECT id, aggregate_type, aggregate_id, event_type, attempts FROM outbox_events " +
                            "WHERE status = 'PENDING' " +
                            "AND (next_attempt_at IS NULL OR next_attempt_at <= now()) " +
                            "ORDER BY occurred_at ASC " +
                            "LIMIT " + BATCH_SIZE + " " +
                            "FOR UPDATE SKIP LOCKED",
                    (rs, n) -> new ClaimedRow(
                            rs.getObject("id", UUID.class),
                            rs.getString("aggregate_type"),
                            rs.getString("aggregate_id"),
                            rs.getString("event_type"),
                            rs.getInt("attempts"))
            );
            if (rows.isEmpty()) {
                return;
            }
            OffsetDateTime now = OffsetDateTime.now();
            for (ClaimedRow row : rows) {
                try {
                    notify(row);
                    markPublished(row, now);
                } catch (Exception ex) {
                    markFailure(row, now, ex);
                }
            }
        } catch (Exception e) {
            // Whole-batch failure (e.g. claim query / connection). Let the next tick retry.
            log.error("Outbox publisher batch failed: {}", e.getMessage());
        }
    }

    private void markPublished(ClaimedRow row, OffsetDateTime now) {
        jdbc.update(
                "UPDATE outbox_events SET status = 'PUBLISHED', published_at = ?, " +
                        "next_attempt_at = NULL, last_error = NULL WHERE id = ?",
                Timestamp.from(now.toInstant()), row.id);
    }

    private void markFailure(ClaimedRow row, OffsetDateTime now, Exception ex) {
        int attempts = row.attempts + 1;
        String error = truncate(ex.getMessage() == null ? ex.getClass().getName() : ex.getMessage());
        if (attempts >= MAX_ATTEMPTS) {
            jdbc.update(
                    "UPDATE outbox_events SET status = 'FAILED', attempts = ?, last_error = ?, " +
                            "next_attempt_at = NULL WHERE id = ?",
                    attempts, error, row.id);
            log.error("Outbox event id={} type={} quarantined as FAILED after {} attempts: {}",
                    row.id, row.eventType, attempts, error);
        } else {
            Timestamp next = Timestamp.from(now.plus(backoff(attempts)).toInstant());
            jdbc.update(
                    "UPDATE outbox_events SET attempts = ?, last_error = ?, next_attempt_at = ? WHERE id = ?",
                    attempts, error, next, row.id);
            log.warn("Failed to publish outbox event id={} type={} (attempt {}/{}), retrying after backoff: {}",
                    row.id, row.eventType, attempts, MAX_ATTEMPTS, error);
        }
    }

    /** Capped exponential backoff: BACKOFF_BASE * 2^(attempts-1), never exceeding BACKOFF_CAP. */
    static Duration backoff(int attempts) {
        int shift = Math.max(0, attempts - 1);
        // Guard against overflow for large attempt counts before the FAILED cap kicks in.
        if (shift >= 62) {
            return BACKOFF_CAP;
        }
        long millis = BACKOFF_BASE.toMillis() << shift;
        if (millis <= 0 || millis > BACKOFF_CAP.toMillis()) {
            return BACKOFF_CAP;
        }
        return Duration.ofMillis(millis);
    }

    private static String truncate(String s) {
        if (s == null) {
            return null;
        }
        return s.length() <= MAX_ERROR_LEN ? s : s.substring(0, MAX_ERROR_LEN);
    }

    private void notify(ClaimedRow row) {
        ObjectNode payload = mapper.createObjectNode();
        payload.put("eventId", row.id.toString());
        payload.put("eventType", row.eventType);
        payload.put("aggregateType", row.aggregateType);
        payload.put("aggregateId", row.aggregateId);
        String json = payload.toString();
        // Parameterized pg_notify avoids SQL string interpolation of the channel/payload (no injection
        // surface, no manual quote-escaping). It must run as a QUERY, not update(): SELECT pg_notify(...)
        // returns a (void) result row, so jdbc.update() would fail with "a result was returned when none
        // was expected". The result is discarded. Delivered when this transaction commits.
        jdbc.query("SELECT pg_notify(?, ?)",
                (org.springframework.jdbc.core.ResultSetExtractor<Void>) rs -> null,
                CHANNEL, json);
    }

    private record ClaimedRow(UUID id, String aggregateType, String aggregateId, String eventType, int attempts) {}
}
