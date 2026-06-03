--liquibase formatted sql
-- Owner: Bucket WEBHOOKS (signed outbound webhooks with retries, fed from the transactional outbox).
-- Adds two tables:
--   webhook_subscriptions  – per-org registration of an HTTPS endpoint + HMAC secret (encrypted at rest).
--   webhook_deliveries     – one row per (subscription, outbox event); drives at-least-once delivery
--                            with capped exponential-backoff retries and a poison/FAILED terminal state.
-- Addresses ROADMAP gap #33 (outbound webhooks with signing and retries).
-- Registered in db.changelog-master.yaml as: db/changelog/changes/15-webhooks.sql
--
-- Column/entity mapping (spring.jpa.hibernate.ddl-auto=validate, so each MUST match the entities):
--   webhook_subscriptions  -> com.example.cp.webhooks.WebhookSubscription
--     id            UUID PK                       -> id (UUID)
--     org_id        UUID NOT NULL FK orgs(id)      -> orgId (UUID)
--     url           TEXT NOT NULL                  -> url (String)
--     secret_enc    BYTEA NOT NULL                 -> secretEnc (byte[]; AES-GCM via KeyEncryptor)
--     event_types   TEXT (CSV; NULL = all events)  -> eventTypes (String, nullable)
--     active        BOOLEAN NOT NULL DEFAULT TRUE   -> active (boolean)
--     created_at    TIMESTAMPTZ NOT NULL           -> createdAt (OffsetDateTime)
--   webhook_deliveries     -> com.example.cp.webhooks.WebhookDelivery
--     id              UUID PK                                 -> id (UUID)
--     subscription_id UUID NOT NULL FK webhook_subscriptions  -> subscriptionId (UUID)
--     event_id        UUID NOT NULL (outbox_events.id)        -> eventId (UUID)
--     status          VARCHAR(16) NOT NULL 'PENDING'          -> status (enum PENDING/DELIVERED/FAILED)
--     attempts        INT NOT NULL DEFAULT 0                   -> attempts (int)
--     response_status INT                                     -> responseStatus (Integer, nullable)
--     next_attempt_at TIMESTAMPTZ                              -> nextAttemptAt (OffsetDateTime, nullable)
--     last_error      TEXT                                     -> lastError (String, nullable)
--     created_at      TIMESTAMPTZ NOT NULL                     -> createdAt (OffsetDateTime)
--     delivered_at    TIMESTAMPTZ                              -> deliveredAt (OffsetDateTime, nullable)

--changeset cp:15-webhook-subscriptions
CREATE TABLE webhook_subscriptions (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    org_id UUID NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    url TEXT NOT NULL,
    secret_enc BYTEA NOT NULL,
    event_types TEXT,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_webhook_subscriptions_org ON webhook_subscriptions(org_id);
CREATE INDEX idx_webhook_subscriptions_active ON webhook_subscriptions(active) WHERE active = TRUE;
--rollback DROP TABLE webhook_subscriptions;

--changeset cp:15-webhook-deliveries
CREATE TABLE webhook_deliveries (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    subscription_id UUID NOT NULL REFERENCES webhook_subscriptions(id) ON DELETE CASCADE,
    event_id UUID NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING','DELIVERED','FAILED')),
    attempts INT NOT NULL DEFAULT 0,
    response_status INT,
    next_attempt_at TIMESTAMPTZ,
    last_error TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    delivered_at TIMESTAMPTZ,
    -- Idempotency: exactly one delivery row per (subscription, event). The fan-out scanner relies on
    -- this to avoid enqueuing the same event to the same subscription twice across scheduler ticks /
    -- instances (ON CONFLICT DO NOTHING).
    CONSTRAINT ux_webhook_delivery UNIQUE (subscription_id, event_id)
);
-- Supports the claim query: status = 'PENDING' AND (next_attempt_at IS NULL OR next_attempt_at <= now()).
CREATE INDEX idx_webhook_deliveries_claimable
    ON webhook_deliveries (next_attempt_at, created_at)
    WHERE status = 'PENDING';
--rollback DROP TABLE webhook_deliveries;
