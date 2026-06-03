package com.example.cp.webhooks;

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
 * Per-org registration of an outbound webhook endpoint. The signing {@code secret} is stored only as
 * an AES-GCM blob ({@link #secretEnc}) produced by {@code KeyEncryptor}; the plaintext secret is
 * returned exactly once at create time and is never read back from the DB or echoed in any DTO.
 *
 * <p>{@link #eventTypes} is an optional CSV of outbox {@code event_type} names the subscription wants
 * (e.g. {@code "SubscriptionActivated,LicenseRevoked"}); a {@code null}/blank value subscribes to ALL
 * event types. Matching is performed in {@code WebhookDispatchScheduler}.
 */
@Entity
@Table(name = "webhook_subscriptions")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class WebhookSubscription {

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "org_id", nullable = false)
    private UUID orgId;

    @Column(name = "url", nullable = false)
    private String url;

    /** AES-GCM blob (KeyEncryptor) of the HMAC-SHA256 signing secret. Never null, never returned. */
    @Column(name = "secret_enc", nullable = false)
    private byte[] secretEnc;

    /** CSV of subscribed event_type names; null/blank = all events. */
    @Column(name = "event_types")
    private String eventTypes;

    @Column(name = "active", nullable = false)
    private boolean active;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;
}
