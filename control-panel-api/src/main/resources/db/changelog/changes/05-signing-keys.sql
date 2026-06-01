--liquibase formatted sql

--changeset cp:05-signing-keys-create
CREATE TABLE signing_keys (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    kid VARCHAR(64) UNIQUE NOT NULL,
    algorithm VARCHAR(32) NOT NULL,
    public_key_pem TEXT NOT NULL,
    private_key_encrypted BYTEA NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE','RETIRED')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    retired_at TIMESTAMPTZ
);
CREATE INDEX idx_signing_keys_status ON signing_keys(status);
--rollback DROP TABLE signing_keys;
