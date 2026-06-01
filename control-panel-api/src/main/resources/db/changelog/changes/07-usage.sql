--liquibase formatted sql

--changeset cp:07-usage-events-create
CREATE TABLE usage_events (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    subscription_id UUID NOT NULL REFERENCES subscriptions(id) ON DELETE CASCADE,
    jti VARCHAR(64),
    feature_key VARCHAR(64) NOT NULL,
    quantity NUMERIC NOT NULL DEFAULT 1,
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    metadata_json JSONB
);
CREATE INDEX idx_usage_events_subscription_occurred ON usage_events(subscription_id, occurred_at);
CREATE INDEX idx_usage_events_jti ON usage_events(jti);
--rollback DROP TABLE usage_events;

--changeset cp:07-usage-quotas-create
CREATE TABLE usage_quotas (
    subscription_id UUID NOT NULL REFERENCES subscriptions(id) ON DELETE CASCADE,
    feature_key VARCHAR(64) NOT NULL,
    period_start TIMESTAMPTZ NOT NULL,
    period_end TIMESTAMPTZ NOT NULL,
    limit_value NUMERIC,
    consumed_value NUMERIC NOT NULL DEFAULT 0,
    PRIMARY KEY (subscription_id, feature_key, period_start)
);
--rollback DROP TABLE usage_quotas;
