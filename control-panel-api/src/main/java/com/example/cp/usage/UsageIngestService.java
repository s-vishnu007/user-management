package com.example.cp.usage;

import com.example.cp.common.ApiException;
import com.example.cp.common.Ids;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
public class UsageIngestService {

    private final UsageEventRepository eventRepo;
    private final UsageQuotaRepository quotaRepo;
    private final LicenseTokenLookup tokenLookup;
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper = new ObjectMapper();
    private final boolean enforceLimit;
    private final Duration occurredMaxPast;
    private final Duration occurredMaxFuture;

    public UsageIngestService(UsageEventRepository eventRepo,
                              UsageQuotaRepository quotaRepo,
                              LicenseTokenLookup tokenLookup,
                              JdbcTemplate jdbc,
                              @Value("${app.usage.enforce-limit:true}") boolean enforceLimit,
                              @Value("${app.usage.occurred-at-max-past:P35D}") Duration occurredMaxPast,
                              @Value("${app.usage.occurred-at-max-future:PT5M}") Duration occurredMaxFuture) {
        this.eventRepo = eventRepo;
        this.quotaRepo = quotaRepo;
        this.tokenLookup = tokenLookup;
        this.jdbc = jdbc;
        this.enforceLimit = enforceLimit;
        this.occurredMaxPast = occurredMaxPast;
        this.occurredMaxFuture = occurredMaxFuture;
    }

    public record IngestEvent(String eventId, String featureKey, BigDecimal quantity, OffsetDateTime occurredAt, Map<String, Object> metadata) {}

    public record IngestResult(int eventsAccepted, UUID subscriptionId) {}

    @Transactional
    public IngestResult ingest(String jti, List<IngestEvent> events) {
        if (jti == null || jti.isBlank()) {
            throw ApiException.badRequest("jti is required");
        }
        if (events == null || events.isEmpty()) {
            throw ApiException.badRequest("events list cannot be empty");
        }
        LicenseTokenView token = tokenLookup.findByJti(jti)
                .orElseThrow(() -> ApiException.badRequest("Unknown jti"));
        if (!token.isActive()) {
            throw ApiException.badRequest("License token is not active");
        }

        UUID subId = token.subscriptionId();
        OffsetDateTime now = OffsetDateTime.now();

        // 1. Validate each event and de-duplicate by (subscriptionId, jti, eventId) — both within this
        //    batch and against already-persisted rows — so replays are idempotent and never double-count.
        Set<String> batchEventIds = new HashSet<>();
        List<UsageEvent> toPersist = new ArrayList<>(events.size());
        for (IngestEvent e : events) {
            if (e.featureKey() == null || e.featureKey().isBlank()) {
                throw ApiException.badRequest("feature_key required for each event");
            }
            if (e.quantity() == null || e.quantity().signum() <= 0) {
                throw ApiException.badRequest("quantity must be greater than 0");
            }
            OffsetDateTime occurred = e.occurredAt() == null ? now : e.occurredAt();
            if (occurred.isBefore(now.minus(occurredMaxPast)) || occurred.isAfter(now.plus(occurredMaxFuture))) {
                throw ApiException.badRequest("occurredAt is outside the accepted window");
            }
            String eventId = (e.eventId() == null || e.eventId().isBlank()) ? null : e.eventId();
            if (eventId != null) {
                if (!batchEventIds.add(eventId)) continue;                       // duplicate within this batch
                if (eventRepo.existsBySubscriptionIdAndJtiAndEventId(subId, jti, eventId)) continue; // already ingested
            }
            toPersist.add(UsageEvent.builder()
                    .id(Ids.newId())
                    .subscriptionId(subId)
                    .jti(jti)
                    .eventId(eventId)
                    .featureKey(e.featureKey())
                    .quantity(e.quantity())
                    .occurredAt(occurred)
                    .metadataJson(serializeMetadata(e.metadata()))
                    .build());
        }

        if (toPersist.isEmpty()) {
            return new IngestResult(0, subId);                                    // fully de-duplicated replay
        }

        // 2. Persist the event rows, then accumulate consumption + enforce the per-(feature, period)
        //    limit ATOMICALLY in the upsert. Aggregating per bucket first means one guarded UPDATE per
        //    bucket: the WHERE makes "consumed + add <= limit" a single check-and-set with the row
        //    locked, so two concurrent batches can no longer both pass a stale SELECT and overrun the
        //    cap (the TOCTOU race the plain SELECT-then-upsert allowed).
        //
        //    The partial-unique dedup index (subscription_id, jti, event_id) is the authoritative
        //    idempotency backstop behind the SELECT pre-check above: if a concurrent request slipped
        //    the same eventId past that pre-check, the INSERT collides. Because Postgres aborts the
        //    whole transaction on a constraint violation (no further statements can run on this
        //    connection), we cannot re-query and partially re-ingest here — instead we treat the
        //    collision as "already ingested by the concurrent winner" and return idempotently
        //    (HTTP 202, zero newly-accepted) rather than surfacing a raw 500. The winning request
        //    already persisted the rows and accumulated the quota, so no work is lost.
        try {
            eventRepo.saveAll(toPersist);
            eventRepo.flush();
        } catch (DataIntegrityViolationException dup) {
            throw new DedupCollisionException(subId, dup);
        }

        Map<String, BigDecimal> addByBucket = new LinkedHashMap<>();
        Map<String, UsageEvent> bucketSample = new LinkedHashMap<>();
        for (UsageEvent ue : toPersist) {
            OffsetDateTime period = monthStartUtc(ue.getOccurredAt());
            String bucket = ue.getFeatureKey() + "|" + period;
            addByBucket.merge(bucket, ue.getQuantity(), BigDecimal::add);
            bucketSample.putIfAbsent(bucket, ue);
        }
        for (Map.Entry<String, BigDecimal> e : addByBucket.entrySet()) {
            UsageEvent sample = bucketSample.get(e.getKey());
            upsertQuotaEnforcingLimit(subId, sample.getFeatureKey(), e.getValue(), sample.getOccurredAt());
        }
        return new IngestResult(toPersist.size(), subId);
    }

    /**
     * Signals that the dedup unique index collided concurrently (the rows are already ingested by the
     * winning request). Propagating it out of {@link #ingest(String, List)} rolls back the (now
     * Postgres-aborted) transaction via the {@code @Transactional} proxy; the controller catches it
     * and returns an idempotent zero-accepted response instead of a raw 500. No work is lost because
     * the winning request already persisted the rows and accumulated the quota.
     */
    static final class DedupCollisionException extends RuntimeException {
        final UUID subscriptionId;
        DedupCollisionException(UUID subscriptionId, Throwable cause) {
            super(cause);
            this.subscriptionId = subscriptionId;
        }
    }

    /**
     * Atomic accumulate-with-limit. On a fresh period the row inserts ({@code limit_value} NULL = no
     * cap). On conflict the consumed total is incremented ONLY when the row has no limit or the new
     * total still fits; the row is locked for the duration of the UPDATE so the check and the set are
     * indivisible. When {@code enforceLimit} is on and the guarded UPDATE matches zero rows, the limit
     * would be exceeded -> 409, rolling back the whole {@code @Transactional} ingest (event rows and
     * all). When enforcement is off, the WHERE guard is omitted so consumption always accumulates.
     */
    private void upsertQuotaEnforcingLimit(UUID subId, String featureKey, BigDecimal add, OffsetDateTime occurredAt) {
        OffsetDateTime periodStart = monthStartUtc(occurredAt);
        OffsetDateTime periodEnd = periodStart.plusMonths(1);
        String sql = """
                INSERT INTO usage_quotas (subscription_id, feature_key, period_start, period_end, limit_value, consumed_value)
                VALUES (?, ?, ?, ?, NULL, ?)
                ON CONFLICT (subscription_id, feature_key, period_start)
                DO UPDATE SET consumed_value = usage_quotas.consumed_value + EXCLUDED.consumed_value,
                              period_end = EXCLUDED.period_end
                """;
        if (enforceLimit) {
            sql += "WHERE usage_quotas.limit_value IS NULL "
                    + "OR usage_quotas.consumed_value + EXCLUDED.consumed_value <= usage_quotas.limit_value";
        }
        int affected = jdbc.update(sql, subId, featureKey, periodStart, periodEnd, add);
        // affected == 0 only when the row already existed and the limit guard excluded it (a fresh
        // INSERT always reports 1). That is the over-limit case.
        if (enforceLimit && affected == 0) {
            throw ApiException.conflict("Usage limit exceeded for feature '" + featureKey + "'");
        }
    }

    private static OffsetDateTime monthStartUtc(OffsetDateTime t) {
        OffsetDateTime utc = t.withOffsetSameInstant(ZoneOffset.UTC);
        return utc.withDayOfMonth(1).truncatedTo(ChronoUnit.DAYS);
    }

    private String serializeMetadata(Map<String, Object> metadata) {
        if (metadata == null || metadata.isEmpty()) {
            return null;
        }
        try {
            return mapper.writeValueAsString(metadata);
        } catch (JsonProcessingException e) {
            throw ApiException.badRequest("Invalid metadata: " + e.getMessage());
        }
    }

    @Transactional(readOnly = true)
    public List<UsageQuota> getQuotaStatus(UUID subscriptionId, String featureKey) {
        if (featureKey == null || featureKey.isBlank()) {
            return quotaRepo.findBySubscriptionId(subscriptionId);
        }
        // Returns one row per period — a single-result query here threw after the 2nd month.
        return quotaRepo.findBySubscriptionIdAndFeatureKey(subscriptionId, featureKey);
    }

    @Transactional(readOnly = true)
    public List<UsageEvent> listEvents(UUID subscriptionId, OffsetDateTime from, OffsetDateTime to) {
        return eventRepo.findInRange(subscriptionId, from, to);
    }
}
