package com.example.cp.usage;

import com.example.cp.common.ApiException;
import com.example.cp.common.Ids;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class UsageIngestService {

    private final UsageEventRepository eventRepo;
    private final UsageQuotaRepository quotaRepo;
    private final LicenseTokenLookup tokenLookup;
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper = new ObjectMapper();

    public UsageIngestService(UsageEventRepository eventRepo,
                              UsageQuotaRepository quotaRepo,
                              LicenseTokenLookup tokenLookup,
                              JdbcTemplate jdbc) {
        this.eventRepo = eventRepo;
        this.quotaRepo = quotaRepo;
        this.tokenLookup = tokenLookup;
        this.jdbc = jdbc;
    }

    public record IngestEvent(String featureKey, BigDecimal quantity, OffsetDateTime occurredAt, Map<String, Object> metadata) {}

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
        List<UsageEvent> persisted = new ArrayList<>(events.size());
        for (IngestEvent e : events) {
            if (e.featureKey() == null || e.featureKey().isBlank()) {
                throw ApiException.badRequest("feature_key required for each event");
            }
            BigDecimal qty = e.quantity() == null ? BigDecimal.ONE : e.quantity();
            OffsetDateTime occurred = e.occurredAt() == null ? OffsetDateTime.now() : e.occurredAt();
            String meta = serializeMetadata(e.metadata());
            UsageEvent ue = UsageEvent.builder()
                    .id(Ids.newId())
                    .subscriptionId(subId)
                    .jti(jti)
                    .featureKey(e.featureKey())
                    .quantity(qty)
                    .occurredAt(occurred)
                    .metadataJson(meta)
                    .build();
            persisted.add(ue);
        }
        eventRepo.saveAll(persisted);

        for (UsageEvent ue : persisted) {
            upsertQuota(subId, ue.getFeatureKey(), ue.getQuantity(), ue.getOccurredAt());
        }
        return new IngestResult(persisted.size(), subId);
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
