package com.example.cp.webhooks;

import com.example.cp.common.Ids;
import com.example.cp.keys.KeyEncryptor;
import com.example.cp.orgs.Organization;
import com.example.cp.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration coverage for the P1-11 durable webhook fan-out cursor. Proves that an outbox event well
 * older than the legacy 10-minute lookback window is STILL fanned out (no silent time-window drop) and
 * is durably marked {@code fanned_out_at} so it is processed exactly once.
 */
class WebhookFanOutIntegrityIT extends AbstractIntegrationTest {

    @Autowired private WebhookDispatchScheduler scheduler;
    @Autowired private WebhookSubscriptionRepository webhookRepo;
    @Autowired private KeyEncryptor keyEncryptor;
    @Autowired private JdbcTemplate jdbc;

    @Test
    void fanOut_emitsDeliveryForEventOlderThanLegacyLookback_andMarksFannedOut() {
        Organization org = seedOrg("Fanout Org");

        // An active subscription for the org (all event types).
        WebhookSubscription sub = WebhookSubscription.builder()
                .id(Ids.newId())
                .orgId(org.getId())
                .url("https://203.0.113.10/cp")
                .secretEnc(keyEncryptor.encrypt("s".getBytes(StandardCharsets.UTF_8)))
                .eventTypes(null)
                .active(true)
                .createdAt(OffsetDateTime.now())
                .build();
        webhookRepo.save(sub);

        // An outbox event that occurred 30 minutes ago — well outside the old PT10M scan window. Under
        // the legacy time-window scan this would have been silently dropped; the durable cursor must
        // still fan it out because fanned_out_at IS NULL.
        UUID eventId = Ids.newId();
        jdbc.update(
                "INSERT INTO outbox_events (id, aggregate_type, aggregate_id, event_type, payload_json, "
                        + "occurred_at, status) VALUES (?, 'subscription', ?, 'SubscriptionActivated', ?::jsonb, "
                        + "now() - interval '30 minutes', 'PENDING')",
                eventId, UUID.randomUUID().toString(),
                "{\"org_id\":\"" + org.getId() + "\"}");

        // (The background @Scheduled dispatch() may also fan this out; fan-out is idempotent, so the
        // assertions below hold regardless of whether this explicit call or a background tick did it.)
        scheduler.fanOut();

        // A PENDING delivery row was created for the subscription/event pair.
        Integer deliveries = jdbc.queryForObject(
                "SELECT count(*) FROM webhook_deliveries WHERE subscription_id = ? AND event_id = ? "
                        + "AND status = 'PENDING'",
                Integer.class, sub.getId(), eventId);
        assertThat(deliveries).isEqualTo(1);

        // The event is durably marked fanned-out so it is never re-scanned.
        Integer marked = jdbc.queryForObject(
                "SELECT count(*) FROM outbox_events WHERE id = ? AND fanned_out_at IS NOT NULL",
                Integer.class, eventId);
        assertThat(marked).isEqualTo(1);

        // Idempotent: a second fan-out neither re-processes the event nor duplicates the delivery.
        scheduler.fanOut();
        Integer deliveriesAfter = jdbc.queryForObject(
                "SELECT count(*) FROM webhook_deliveries WHERE subscription_id = ? AND event_id = ?",
                Integer.class, sub.getId(), eventId);
        assertThat(deliveriesAfter).isEqualTo(1);
    }
}
