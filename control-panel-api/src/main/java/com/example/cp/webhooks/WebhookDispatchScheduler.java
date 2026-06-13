package com.example.cp.webhooks;

import com.example.cp.keys.KeyEncryptor;
import com.example.cp.licenses.LicenseToken;
import com.example.cp.licenses.LicenseTokenRepository;
import com.example.cp.subscriptions.Subscription;
import com.example.cp.subscriptions.SubscriptionRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import jakarta.annotation.PreDestroy;

import java.io.IOException;
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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Fans the transactional outbox out to per-org webhook subscriptions and delivers each as a signed
 * HTTP POST with capped exponential-backoff retries.
 *
 * <p>Each scheduled tick does two phases:
 * <ol>
 *   <li><b>Fan-out</b> — durably <em>claim</em> a bounded batch of not-yet-fanned-out
 *       {@code outbox_events} (rows with {@code fanned_out_at IS NULL}) via
 *       {@code SELECT ... FOR UPDATE SKIP LOCKED LIMIT}, resolve each event's owning org from its
 *       payload, insert a {@code PENDING} {@code webhook_deliveries} row for every matching active
 *       subscription, and stamp {@code fanned_out_at} so the event is never re-scanned. Because the
 *       claim is a durable per-event marker (not a sliding time window), an event can NEVER be silently
 *       dropped by ageing out of a lookback window — it stays claimable until a fan-out tick commits.
 *       The {@code (subscription_id, event_id)} unique constraint + {@code ON CONFLICT DO NOTHING}
 *       makes the enqueue idempotent even if the same row is somehow processed twice.</li>
 *   <li><b>Deliver</b> — claim a bounded batch of due {@code PENDING} deliveries with
 *       {@code SELECT ... FOR UPDATE SKIP LOCKED} (so siblings never deliver the same row), re-validate
 *       and IP-pin the destination URL through the SSRF guard, POST the event JSON with the signed
 *       headers, and mark {@code DELIVERED} on 2xx or schedule a retry / quarantine as {@code FAILED}
 *       after {@link #maxAttempts}.</li>
 * </ol>
 *
 * <p><b>Phase isolation.</b> Fan-out runs on its own single-thread executor and delivery on the shared
 * {@code @Scheduled} thread, so a tarpit endpoint that stalls a delivery batch can never starve fan-out
 * (and thereby drop events for other tenants). Each phase is also bounded by a LIMIT.
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
 * <p>The transactional outbox is consumed strictly via raw SQL here (the durable {@code fanned_out_at}
 * marker is webhook-owned and lives outside the events {@code OutboxEvent} entity), so this scheduler
 * never contends with the NOTIFY publisher's own status machine.
 */
@Component
public class WebhookDispatchScheduler {

    private static final Logger log = LoggerFactory.getLogger(WebhookDispatchScheduler.class);

    /** Max deliveries claimed per tick (bounds work and the held-lock set). */
    private static final int DELIVER_BATCH = 100;
    /** Max outbox events claimed for fan-out per tick (bounds the scan + held-lock set). */
    private static final int FANOUT_BATCH = 500;
    /** Backoff base: delay before retry n is BASE * 2^(n-1), capped at {@link #BACKOFF_CAP}. */
    static final Duration BACKOFF_BASE = Duration.ofSeconds(10);
    static final Duration BACKOFF_CAP = Duration.ofHours(1);
    private static final int MAX_ERROR_LEN = 2000;
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(3);

    /** Default per-attempt request timeout when {@code app.webhooks.timeout} is unset. */
    private static final Duration DEFAULT_REQUEST_TIMEOUT = Duration.ofSeconds(10);
    /** Default poison cap when {@code app.webhooks.max-attempts} is unset. */
    private static final int DEFAULT_MAX_ATTEMPTS = 8;
    /** Retention: terminal (DELIVERED/FAILED) delivery rows older than this are purged. */
    private static final Duration DELIVERY_RETENTION = Duration.ofDays(30);

    private final WebhookSubscriptionRepository subRepo;
    private final WebhookDeliveryRepository deliveryRepo;
    private final SubscriptionRepository subscriptionRepo;
    private final LicenseTokenRepository tokenRepo;
    private final WebhookSigner signer;
    private final KeyEncryptor keyEncryptor;
    private final JdbcTemplate jdbc;
    private final WebhookUrlGuard urlGuard;
    private final ObjectMapper mapper = new ObjectMapper();
    private HttpClient httpClient;

    /**
     * Self-reference so the {@code @Scheduled} entry point invokes the package-private
     * {@code @Transactional} phase methods through the Spring proxy. A direct self-invocation would
     * bypass the proxy and run the {@code FOR UPDATE SKIP LOCKED} claim in autocommit, releasing the
     * row locks immediately and allowing duplicate deliveries across instances (P2 self-invocation).
     */
    private final WebhookDispatchScheduler self;

    /**
     * Dedicated single-thread executor for the DELIVERY phase. Fan-out runs synchronously on the shared
     * {@code @Scheduled} thread, while delivery (the slow, tarpit-prone phase that synchronously POSTs up
     * to {@link #DELIVER_BATCH} endpoints) runs here. This guarantees a tarpit endpoint that stalls a
     * delivery batch can NEVER stretch the gap between fan-out ticks — so events can never age out / be
     * dropped for other tenants because one tenant's webhook is hanging (P1-11 scheduler starvation).
     */
    private final ExecutorService deliveryExecutor =
            Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "webhook-delivery");
                t.setDaemon(true);
                return t;
            });

    /** At most one delivery batch runs at a time; new ticks skip submitting while one is in flight. */
    private final java.util.concurrent.atomic.AtomicBoolean deliveryInFlight =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    /** Max delivery attempts before a delivery is quarantined as FAILED (app.webhooks.max-attempts). */
    private final int maxAttempts;
    /** Per-attempt connect+read timeout for the outbound POST (app.webhooks.timeout). */
    private final Duration requestTimeout;

    public WebhookDispatchScheduler(WebhookSubscriptionRepository subRepo,
                                    WebhookDeliveryRepository deliveryRepo,
                                    SubscriptionRepository subscriptionRepo,
                                    LicenseTokenRepository tokenRepo,
                                    WebhookSigner signer,
                                    KeyEncryptor keyEncryptor,
                                    JdbcTemplate jdbc,
                                    WebhookUrlGuard urlGuard,
                                    @Lazy WebhookDispatchScheduler self,
                                    @Value("${app.webhooks.max-attempts:8}") int maxAttempts,
                                    @Value("${app.webhooks.timeout:PT10S}") Duration requestTimeout) {
        this.subRepo = subRepo;
        this.deliveryRepo = deliveryRepo;
        this.subscriptionRepo = subscriptionRepo;
        this.tokenRepo = tokenRepo;
        this.signer = signer;
        this.keyEncryptor = keyEncryptor;
        this.jdbc = jdbc;
        this.urlGuard = urlGuard;
        this.self = self;
        this.maxAttempts = maxAttempts > 0 ? maxAttempts : DEFAULT_MAX_ATTEMPTS;
        this.requestTimeout = (requestTimeout != null && !requestTimeout.isZero() && !requestTimeout.isNegative())
                ? requestTimeout : DEFAULT_REQUEST_TIMEOUT;
        this.httpClient = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(CONNECT_TIMEOUT)
                .build();
    }

    int maxAttempts() {
        return maxAttempts;
    }

    Duration requestTimeout() {
        return requestTimeout;
    }

    /** Test seam: swap in a stub {@link HttpClient} so delivery can be exercised without real I/O. */
    void setHttpClientForTest(HttpClient client) {
        this.httpClient = client;
    }

    @PreDestroy
    void shutdown() {
        deliveryExecutor.shutdownNow();
    }

    @Scheduled(fixedDelayString = "${app.webhooks.dispatch-interval-ms:5000}")
    public void dispatch() {
        // Fan-out runs synchronously on the shared scheduler thread (it is bounded and fast: a claim +
        // a handful of inserts/updates). It MUST go through the self-proxy so the @Transactional
        // FOR UPDATE SKIP LOCKED claim runs in a real transaction (P2 self-invocation).
        try {
            self.fanOut();
        } catch (Exception e) {
            log.error("Webhook fan-out phase failed: {}", e.getMessage());
        }
        // Delivery (slow, tarpit-prone) runs off-thread so it can never delay the next fan-out tick. If a
        // previous batch is still running (a hung endpoint), skip this tick's submission rather than
        // queueing unboundedly — the rows stay claimable for the batch already in flight / a later tick.
        if (deliveryInFlight.compareAndSet(false, true)) {
            deliveryExecutor.submit(() -> {
                try {
                    self.deliverDueBatch();
                } catch (Exception e) {
                    log.error("Webhook delivery phase failed: {}", e.getMessage());
                } finally {
                    deliveryInFlight.set(false);
                }
            });
        }
    }

    /** Periodic retention sweep: drop terminal delivery rows past {@link #DELIVERY_RETENTION}. */
    @Scheduled(fixedDelayString = "${app.webhooks.cleanup-interval-ms:3600000}")
    public void cleanup() {
        try {
            OffsetDateTime cutoff = OffsetDateTime.now().minus(DELIVERY_RETENTION);
            int removed = deliveryRepo.deleteTerminalOlderThan(cutoff);
            if (removed > 0) {
                log.info("Webhook delivery retention sweep removed {} terminal rows older than {}", removed, cutoff);
            }
        } catch (Exception e) {
            log.error("Webhook delivery retention sweep failed: {}", e.getMessage());
        }
    }

    // ------------------------------------------------------------------
    //  Phase 1: durably claim outbox events -> per-subscription delivery rows
    // ------------------------------------------------------------------

    /**
     * Claim a bounded batch of not-yet-fanned-out outbox events with {@code FOR UPDATE SKIP LOCKED},
     * enqueue one PENDING delivery per matching active subscription, then stamp {@code fanned_out_at}
     * on the claimed rows so they are never re-scanned. Durable + at-least-once: nothing is dropped if
     * a tick crashes (the row simply stays unclaimed for the next tick).
     */
    @Transactional
    public void fanOut() {
        List<ClaimedEvent> events = jdbc.query(
                "SELECT id, aggregate_type, aggregate_id, event_type, payload_json " +
                        "FROM outbox_events " +
                        "WHERE fanned_out_at IS NULL " +
                        "ORDER BY occurred_at ASC " +
                        "LIMIT " + FANOUT_BATCH + " " +
                        "FOR UPDATE SKIP LOCKED",
                (rs, n) -> new ClaimedEvent(
                        rs.getObject("id", UUID.class),
                        rs.getString("aggregate_type"),
                        rs.getString("aggregate_id"),
                        rs.getString("event_type"),
                        rs.getString("payload_json")));
        if (events.isEmpty()) {
            return;
        }
        OffsetDateTime now = OffsetDateTime.now();
        for (ClaimedEvent event : events) {
            UUID orgId = resolveOrg(event);
            if (orgId != null) {
                List<WebhookSubscription> subs = subRepo.findByOrgIdAndActiveTrue(orgId);
                for (WebhookSubscription sub : subs) {
                    if (matches(sub.getEventTypes(), event.eventType())) {
                        enqueue(sub.getId(), event.id());
                    }
                }
            }
            // Always mark fanned-out — even when no subscription matched or the org is unresolved —
            // so a permanently-unroutable event does not get re-scanned forever. The fan-out marker
            // means "we have considered this event", not "it produced a delivery".
            markFannedOut(event.id(), now);
        }
    }

    /**
     * Insert a PENDING delivery row idempotently. The {@code (subscription_id, event_id)} unique
     * constraint + {@code ON CONFLICT DO NOTHING} guarantees at-most-one row per pair.
     */
    private void enqueue(UUID subscriptionId, UUID eventId) {
        jdbc.update(
                "INSERT INTO webhook_deliveries (id, subscription_id, event_id, status, attempts, created_at) " +
                        "VALUES (?, ?, ?, 'PENDING', 0, now()) " +
                        "ON CONFLICT (subscription_id, event_id) DO NOTHING",
                com.example.cp.common.Ids.newId(), subscriptionId, eventId);
    }

    private void markFannedOut(UUID eventId, OffsetDateTime now) {
        jdbc.update("UPDATE outbox_events SET fanned_out_at = ? WHERE id = ?",
                Timestamp.from(now.toInstant()), eventId);
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
     * {@code org_id} when the aggregate row is gone (e.g. deleted subscription). Not every event
     * carries org_id in its payload (e.g. SubscriptionSuspended/Cancelled), so the aggregate lookup is
     * the primary path.
     */
    private UUID resolveOrg(ClaimedEvent event) {
        String aggType = event.aggregateType();
        String aggId = event.aggregateId();
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
        return orgFromPayload(event.payloadJson());
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
    public void deliverDueBatch() {
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
     * (non-2xx status, network error, signing/decryption error, SSRF re-check failure) ->
     * retry-or-quarantine.
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

        // DNS-rebind SSRF defense: re-resolve + re-validate the destination immediately before sending
        // and pin the connection to the vetted IP. The URL was validated at registration, but the JDK
        // HttpClient would otherwise re-resolve the hostname at send time (an org admin could repoint
        // DNS at an internal address). The pinned client connects only to the address we vetted here.
        WebhookUrlGuard.PinnedTarget target;
        try {
            target = urlGuard.resolveAndPin(row.url());
        } catch (WebhookUrlGuard.SsrfException e) {
            markFailure(row, now, null, "ssrf re-check failed: " + e.internalDetail());
            return;
        }

        HttpRequest request;
        try {
            request = HttpRequest.newBuilder(target.requestUri())
                    .timeout(requestTimeout)
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
        if (attempts >= maxAttempts) {
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
                    row.id(), row.subscriptionId(), row.eventId(), attempts, maxAttempts, err);
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

    /** A claimed outbox event ready to fan out to subscriptions. */
    record ClaimedEvent(UUID id, String aggregateType, String aggregateId, String eventType,
                        String payloadJson) {}

    /** A claimed delivery joined with its subscription + outbox event, ready to POST. */
    record ClaimedDelivery(UUID id, UUID subscriptionId, UUID eventId, int attempts, String url,
                           byte[] secretEnc, String eventType, String aggregateType, String aggregateId,
                           String payloadJson) {}
}
