--liquibase formatted sql

-- Wave 2 bucket IDEMPOTENCY (#81): server-side Idempotency-Key support on mutating endpoints.
-- A request that carries an "Idempotency-Key" header on a POST/PUT/PATCH under /api/** is keyed by
-- (idem_key, method, path, actor). The first request inserts an in-flight row (UNIQUE guard); once it
-- completes, its response status + body are stored so any retry replays the same outcome instead of
-- re-executing the side effect. Keys are TTL-bounded (created_at + app.idempotency.ttl).
-- Registered in db.changelog-master.yaml by bucket G (CONFIG) as: db/changelog/changes/15-idempotency.sql

--changeset cp:15-idempotency-keys-create
CREATE TABLE idempotency_keys (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    idem_key VARCHAR(255) NOT NULL,
    method VARCHAR(10) NOT NULL,
    path VARCHAR(512) NOT NULL,
    -- Actor scoping: the human user id, or (for api-key principals) the bound org id, or the literal
    -- 'anonymous' so the same key replayed by a different caller cannot read back a prior response.
    actor_user_id VARCHAR(64) NOT NULL,
    -- SHA-256 (hex) of the request body; lets a same-key retry with a DIFFERENT payload be flagged.
    request_hash VARCHAR(64),
    -- NULL while the first request is still in flight; set to the final HTTP status on completion.
    response_status INTEGER,
    response_body TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ux_idempotency_key UNIQUE (idem_key, method, path, actor_user_id)
);
-- Supports the TTL cleanup sweep (delete rows older than now() - ttl).
CREATE INDEX idx_idempotency_keys_created_at ON idempotency_keys(created_at);
--rollback DROP TABLE idempotency_keys;
