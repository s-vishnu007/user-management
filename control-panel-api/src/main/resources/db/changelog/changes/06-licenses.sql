--liquibase formatted sql

--changeset cp:06-license-tokens-create
CREATE TABLE license_tokens (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    jti VARCHAR(64) UNIQUE NOT NULL,
    subscription_id UUID NOT NULL REFERENCES subscriptions(id) ON DELETE CASCADE,
    kid VARCHAR(64) NOT NULL,
    issued_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at TIMESTAMPTZ NOT NULL,
    revoked_at TIMESTAMPTZ,
    revoke_reason TEXT,
    fingerprint VARCHAR(128),
    last_seen_at TIMESTAMPTZ,
    -- Stored as text (not inet): the JPA entity maps this as a String and Hibernate binds it as
    -- VARCHAR, which a strict inet column rejects. Text is sufficient for a last-seen IP.
    last_seen_ip VARCHAR(45),
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE','REVOKED','EXPIRED'))
);
CREATE INDEX idx_license_tokens_subscription_status ON license_tokens(subscription_id, status);
CREATE INDEX idx_license_tokens_jti ON license_tokens(jti);
CREATE INDEX idx_license_tokens_expires_at ON license_tokens(expires_at);
--rollback DROP TABLE license_tokens;
