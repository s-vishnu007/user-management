package com.example.cp.usage;

import com.example.cp.common.ApiException;
import com.example.cp.common.Ids;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
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

        // 2. Enforce per-(feature, period) limits BEFORE writing; any breach throws 409 and the whole
        //    @Transactional ingest rolls back (no event rows, consumed_value unchanged).
        if (enforceLimit) {
            Map<String, BigDecimal> addByBucket = new LinkedHashMap<>();
            for (UsageEvent ue : toPersist) {
                addByBucket.merge(ue.getFeatureKey() + "|" + monthStartUtc(ue.getOccurredAt()),
                        ue.getQuantity(), BigDecimal::add);
            }
            for (UsageEvent ue : toPersist) {
                OffsetDateTime period = monthStartUtc(ue.getOccurredAt());
                BigDecimal add = addByBucket.remove(ue.getFeatureKey() + "|" + period);
                if (add != null) {
                    checkLimit(subId, ue.getFeatureKey(), period, add);
                }
            }
        }

        // 3. Persist and accumulate.
        eventRepo.saveAll(toPersist);
        for (UsageEvent ue : toPersist) {
            upsertQuota(subId, ue.getFeatureKey(), ue.getQuantity(), ue.getOccurredAt());
        }
        return new IngestResult(toPersist.size(), subId);
    }

    /** Rejects with 409 when applying {@code add} to a non-null limit would exceed it for the period. */
    private void checkLimit(UUID subId, String featureKey, OffsetDateTime periodStart, BigDecimal add) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT limit_value, consumed_value FROM usage_quotas "
                        + "WHERE subscription_id = ? AND feature_key = ? AND period_start = ?",
                subId, featureKey, periodStart);
        if (rows.isEmpty() || rows.get(0).get("limit_value") == null) {
            return;                                                               // no row / no limit configured
        }
        BigDecimal limit = new BigDecimal(rows.get(0).get("limit_value").toString());
        Object consumedObj = rows.get(0).get("consumed_value");
        BigDecimal consumed = consumedObj == null ? BigDecimal.ZERO : new BigDecimal(consumedObj.toString());
        if (consumed.add(add).compareTo(limit) > 0) {
            throw ApiException.conflict("Usage limit exceeded for feature '" + featureKey + "'");
        }
    }

    private void upsertQuota(UUID subId, String featureKey, BigDecimal qty, OffsetDateTime occurredAt) {
        OffsetDateTime periodStart = monthStartUtc(occurredAt);
        OffsetDateTime periodEnd = periodStart.plusMonths(1);
        jdbc.update("""
                INSERT INTO usage_quotas (subscription_id, feature_key, period_start, period_end, limit_value, consumed_value)
                VALUES (?, ?, ?, ?, NULL, ?)
                ON CONFLICT (subscription_id, feature_key, period_start)
                DO UPDATE SET consumed_value = usage_quotas.consumed_value + EXCLUDED.consumed_value,
                              period_end = EXCLUDED.period_end
                """, subId, featureKey, periodStart, periodEnd, qty);
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
        return quotaRepo.findBySubscriptionIdAndFeatureKey(subscriptionId, featureKey)
                .map(List::of)
                .orElse(List.of());
    }

    @Transactional(readOnly = true)
    public List<UsageEvent> listEvents(UUID subscriptionId, OffsetDateTime from, OffsetDateTime to) {
        return eventRepo.findInRange(subscriptionId, from, to);
    }
}
