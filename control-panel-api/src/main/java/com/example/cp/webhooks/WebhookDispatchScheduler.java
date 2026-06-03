package com.example.cp.webhooks;

import com.example.cp.events.OutboxEvent;
import com.example.cp.events.OutboxEventRepository;
import com.example.cp.keys.KeyEncryptor;
import com.example.cp.licenses.LicenseToken;
import com.example.cp.licenses.LicenseTokenRepository;
import com.example.cp.subscriptions.Subscription;
import com.example.cp.subscriptions.SubscriptionRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Fans the transactional outbox out to per-org webhook subscriptions and delivers each as a signed
 * HTTP POST with capped exponential-backoff retries.
 *
 * <p>Each scheduled tick does two phases:
 * <ol>
 *   <li><b>Fan-out</b> — read recent {@code outbox_events} (read-only, via the events repo), resolve
 *       each event's owning org, and for every <em>active</em> webhook subscription in that org whose
 *       {@code event_types} filter matches, insert a {@code PENDING} {@code webhook_deliveries} row.
 *       The {@code (subscription_id, event_id)} unique constraint + {@code ON CONFLICT DO NOTHING}
 *       makes this idempotent across ticks and instances (no double-enqueue).</li>
 *   <li><b>Deliver</b> — claim a bounded batch of due {@code PENDING} deliveries with
 *       {@code SELECT ... FOR UPDATE SKIP LOCKED} (so siblings never deliver the same row), POST the
 *       event JSON with the signed headers, and mark {@code DELIVERED} on 2xx or schedule a retry /
 *       quarantine as {@code FAILED} after {@link #MAX_ATTEMPTS}.</li>
 * </ol>
 *
 * <p>Outbound headers per delivery:
 * <ul>
 *   <li>{@code X-CP-Event}     – the outbox {@code event_type};</li>
 *   <li>{@code X-CP-Delivery}  – the {@code webhook_deliveries.id} (stable across retries, usable as an
 *       idempotency key by the receiver);</li>
 *   <li>{@code X-CP-Timestamp} – epoch-seconds the request was signed at;</li>
 *   <li>{@code X-CP-Signature} – {@code "sha256=" + HMAC_SHA256(secret, "<timestamp>.<body>")}.</li>
 * </ul>
 *
 * <p>The events package is consumed strictly read-only (the {@link OutboxEventRepository} and entity);
 * this scheduler never marks outbox rows, so it composes with the NOTIFY publisher without contention.
 */
@Component
public class WebhookDispatchScheduler {

    private static final Logger log = LoggerFactory.getLogger(WebhookDispatchScheduler.class);

    /** Max deliveries claimed per tick (bounds work and the held-lock set). */
    private static final int DELIVER_BATCH = 100;
    /** After this many failed attempts a delivery is quarantined as {@code FAILED}. */
    static final int MAX_ATTEMPTS = 8;
    /** Backoff base: delay before retry n is BASE * 2^(n-1), capped at {@link #BACKOFF_CAP}. */
    static final Duration BACKOFF_BASE = Duration.ofSeconds(10);
    static final Duration BACKOFF_CAP = Duration.ofHours(1);
    private static final int MAX_ERROR_LEN = 2000;
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(3);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(5);

    private final OutboxEventRepository outboxRepo;
    private final WebhookSubscriptionRepository subRepo;
    private final WebhookDeliveryRepository deliveryRepo;
    private final SubscriptionRepository subscriptionRepo;
    private final LicenseTokenRepository tokenRepo;
    private final WebhookSigner signer;
    private final KeyEncryptor keyEncryptor;
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper = new ObjectMapper();
    private HttpClient httpClient;

    /** How far back the fan-out scan looks each tick; bounds the outbox read window. */
    private final Duration scanLookback;

    public WebhookDispatchScheduler(OutboxEventRepository outboxRepo,
                                    WebhookSubscriptionRepository subRepo,
                                    WebhookDeliveryRepository deliveryRepo,
                                    SubscriptionRepository subscriptionRepo,
                                    LicenseTokenRepository tokenRepo,
                                    WebhookSigner signer,
                                    KeyEncryptor keyEncryptor,
                                    JdbcTemplate jdbc,
                                    @Value("${app.webhooks.scan-lookback:PT10M}") Duration scanLookback) {
        this.outboxRepo = outboxRepo;
        this.subRepo = subRepo;
        this.deliveryRepo = deliveryRepo;
        this.subscriptionRepo = subscriptionRepo;
        this.tokenRepo = tokenRepo;
        this.signer = signer;
        this.keyEncryptor = keyEncryptor;
        this.jdbc = jdbc;
        this.scanLookback = scanLookback;
        this.httpClient = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(CONNECT_TIMEOUT)
                .build();
    }

    /** Test seam: swap in a stub {@link HttpClient} so delivery can be exercised without real I/O. */
    void setHttpClientForTest(HttpClient client) {
        this.httpClient = client;
    }

    @Scheduled(fixedDelayString = "${app.webhooks.dispatch-interval-ms:5000}")
    public void dispatch() {
        try {
            fanOut();
        } catch (Exception e) {
            log.error("Webhook fan-out phase failed: {}", e.getMessage());
        }
        try {
            deliverDueBatch();
        } catch (Exception e) {
            log.error("Webhook delivery phase failed: {}", e.getMessage());
        }
    }

    // ------------------------------------------------------------------
    //  Phase 1: fan-out outbox events -> per-subscription delivery rows
    // ------------------------------------------------------------------

    @Transactional
    void fanOut() {
        OffsetDateTime since = OffsetDateTime.now().minus(scanLookback);
        List<OutboxEvent> events = outboxRepo.findSince(since);
        for (OutboxEvent event : events) {
            UUID orgId = resolveOrg(event);
            if (orgId == null) {
                continue;
            }
            List<WebhookSubscription> subs = subRepo.findByOrgIdAndActiveTrue(orgId);
            for (WebhookSubscription sub : subs) {
                if (!matches(sub.getEventTypes(), event.getEventType())) {
                    continue;
                }
                enqueue(sub.getId(), event.getId());
            }
        }
    }

    /**
     * Insert a PENDING delivery row idempotently. The {@code (subscription_id, event_id)} unique
     * constraint + {@code ON CONFLICT DO NOTHING} guarantees at-most-one row per pair even if two
     * instances run the scan concurrently.
     */
    private void enqueue(UUID subscriptionId, UUID eventId) {
        jdbc.update(
                "INSERT INTO webhook_deliveries (id, subscription_id, event_id, status, attempts, created_at) " +
                        "VALUES (?, ?, ?, 'PENDING', 0, now()) " +
                        "ON CONFLICT (subscription_id, event_id) DO NOTHING",
                com.example.cp.common.Ids.newId(), subscriptionId, eventId);
    }

    /** CSV filter match: null/blank filter = all events; otherwise exact, trimmed, case-sensitive token match. */
    static boolean matches(String eventTypesCsv, String eventType) {
        if (eventTypesCsv == null || eventTypesCsv.isBlank()) {
            return true;
        }
        if (eventType == null) {
            return false;
        }
        for (String tok : eventTypesCsv.split(",")) {
            if (tok.trim().equals(eventType)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Resolve the owning org for an outbox event. Subscription aggregates carry the subscription id;
     * license_token aggregates carry the jti (-> subscription -> org). Falls back to a payload
     * {@code org_id} when the aggregate row is gone (e.g. deleted subscription).
     */
    private UUID resolveOrg(OutboxEvent event) {
        String aggType = event.getAggregateType();
        String aggId = event.getAggregateId();
        if ("subscription".equals(aggType)) {
            UUID subId = parseUuid(aggId);
            if (subId != null) {
                Optional<UUID> org = subscriptionRepo.findById(subId).map(Subscription::getOrgId);
                if (org.isPresent()) {
                    return org.get();
                }
            }
        } else if ("license_token".equals(aggType)) {
            Optional<UUID> org = tokenRepo.findByJti(aggId)
                    .map(LicenseToken::getSubscriptionId)
                    .flatMap(sid -> subscriptionRepo.findById(sid).map(Subscription::getOrgId));
            if (org.isPresent()) {
                return org.get();
            }
        }
        // Fallback: many events carry org_id in their payload.
        return orgFromPayload(event.getPayloadJson());
    }

    private UUID orgFromPayload(String payloadJson) {
        if (payloadJson == null || payloadJson.isBlank()) {
            return null;
        }
        try {
            var node = mapper.readTree(payloadJson);
            var org = node.get("org_id");
            if (org != null && org.isTextual()) {
                return parseUuid(org.asText());
            }
        } catch (Exception ignore) {
            // best-effort; unresolved org just skips fan-out for this event
        }
        return null;
    }

    private static UUID parseUuid(String s) {
        if (s == null) return null;
        try {
            return UUID.fromString(s);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    // ------------------------------------------------------------------
    //  Phase 2: claim + deliver due PENDING rows
    // ------------------------------------------------------------------

    @Transactional
    void deliverDueBatch() {
        List<ClaimedDelivery> rows = jdbc.query(
                "SELECT d.id, d.subscription_id, d.event_id, d.attempts, " +
                        "       s.url, s.secret_enc, e.event_type, e.aggregate_type, e.aggregate_id, e.payload_json " +
                        "FROM webhook_deliveries d " +
                        "JOIN webhook_subscriptions s ON s.id = d.subscription_id " +
                        "JOIN outbox_events e ON e.id = d.event_id " +
                        "WHERE d.status = 'PENDING' " +
                        "AND (d.next_attempt_at IS NULL OR d.next_attempt_at <= now()) " +
                        "ORDER BY d.created_at ASC " +
                        "LIMIT " + DELIVER_BATCH + " " +
                        "FOR UPDATE OF d SKIP LOCKED",
                (rs, n) -> new ClaimedDelivery(
                        rs.getObject("id", UUID.class),
                        rs.getObject("subscription_id", UUID.class),
                        rs.getObject("event_id", UUID.class),
                        rs.getInt("attempts"),
                        rs.getString("url"),
                        rs.getBytes("secret_enc"),
                        rs.getString("event_type"),
                        rs.getString("aggregate_type"),
                        rs.getString("aggregate_id"),
                        rs.getString("payload_json")));
        if (rows.isEmpty()) {
            return;
        }
        OffsetDateTime now = OffsetDateTime.now();
        for (ClaimedDelivery row : rows) {
            try {
                attemptDelivery(row, now);
            } catch (Exception ex) {
                // Should not happen (attemptDelivery handles its own failures); isolate the row anyway.
                markFailure(row, now, -1, ex.getMessage());
            }
        }
    }

    /**
     * Build the signed request, POST it, and persist the outcome. 2xx -> DELIVERED; anything else
     * (non-2xx status, network error, signing/decryption error) -> retry-or-quarantine.
     */
    void attemptDelivery(ClaimedDelivery row, OffsetDateTime now) {
        String body = buildBody(row);
        String timestamp = Long.toString(now.toInstant().getEpochSecond());

        byte[] secret;
        try {
            secret = keyEncryptor.decrypt(row.secretEnc());
        } catch (Exception e) {
            // Undecryptable secret never recovers -> count it as an attempt and let backoff/poison apply.
            markFailure(row, now, null, "secret decrypt failed: " + e.getMessage());
            return;
        }
        String signature = signer.sign(secret, timestamp, body);

        HttpRequest request;
        try {
            request = HttpRequest.newBuilder(URI.create(row.url()))
                    .timeout(REQUEST_TIMEOUT)
                    .header("Content-Type", "application/json")
                    .header("X-CP-Event", row.eventType() == null ? "" : row.eventType())
                    .header("X-CP-Delivery", row.id().toString())
                    .header("X-CP-Timestamp", timestamp)
                    .header("X-CP-Signature", signature)
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                    .build();
        } catch (IllegalArgumentException e) {
            markFailure(row, now, null, "invalid url: " + e.getMessage());
            return;
        }

        try {
            HttpResponse<Void> response = httpClient.send(request, HttpResponse.BodyHandlers.discarding());
            int status = response.statusCode();
            if (status >= 200 && status < 300) {
                markDelivered(row, now, status);
            } else {
                markFailure(row, now, status, "non-2xx response: " + status);
            }
        } catch (IOException e) {
            markFailure(row, now, null, "io error: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            markFailure(row, now, null, "interrupted");
        }
    }

    /** The exact bytes signed and POSTed: the event envelope as JSON. */
    String buildBody(ClaimedDelivery row) {
        var node = mapper.createObjectNode();
        node.put("eventId", row.eventId().toString());
        node.put("deliveryId", row.id().toString());
        node.put("eventType", row.eventType());
        node.put("aggregateType", row.aggregateType());
        node.put("aggregateId", row.aggregateId());
        try {
            node.set("payload", mapper.readTree(
                    row.payloadJson() == null || row.payloadJson().isBlank() ? "{}" : row.payloadJson()));
        } catch (Exception e) {
            node.put("payload", row.payloadJson());
        }
        return node.toString();
    }

    private void markDelivered(ClaimedDelivery row, OffsetDateTime now, int status) {
        jdbc.update(
                "UPDATE webhook_deliveries SET status = 'DELIVERED', attempts = ?, response_status = ?, " +
                        "delivered_at = ?, next_attempt_at = NULL, last_error = NULL WHERE id = ?",
                row.attempts() + 1, status, Timestamp.from(now.toInstant()), row.id());
    }

    private void markFailure(ClaimedDelivery row, OffsetDateTime now, Integer status, String error) {
        int attempts = row.attempts() + 1;
        String err = truncate(error);
        if (attempts >= MAX_ATTEMPTS) {
            jdbc.update(
                    "UPDATE webhook_deliveries SET status = 'FAILED', attempts = ?, response_status = ?, " +
                            "next_attempt_at = NULL, last_error = ? WHERE id = ?",
                    attempts, status, err, row.id());
            log.error("Webhook delivery id={} (sub={} event={}) quarantined as FAILED after {} attempts: {}",
                    row.id(), row.subscriptionId(), row.eventId(), attempts, err);
        } else {
            Timestamp next = Timestamp.from(now.plus(backoff(attempts)).toInstant());
            jdbc.update(
                    "UPDATE webhook_deliveries SET attempts = ?, response_status = ?, next_attempt_at = ?, " +
                            "last_error = ? WHERE id = ?",
                    attempts, status, next, err, row.id());
            log.warn("Webhook delivery id={} (sub={} event={}) failed (attempt {}/{}), retrying after backoff: {}",
                    row.id(), row.subscriptionId(), row.eventId(), attempts, MAX_ATTEMPTS, err);
        }
    }

    /** Capped exponential backoff: BACKOFF_BASE * 2^(attempts-1), never exceeding BACKOFF_CAP. */
    static Duration backoff(int attempts) {
        int shift = Math.max(0, attempts - 1);
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

    /** A claimed delivery joined with its subscription + outbox event, ready to POST. */
    record ClaimedDelivery(UUID id, UUID subscriptionId, UUID eventId, int attempts, String url,
                           byte[] secretEnc, String eventType, String aggregateType, String aggregateId,
                           String payloadJson) {}
}
