--liquibase formatted sql
-- Owner: Agent 5 (auth/users/orgs/rbac scope).
-- If master changelog db.changelog-master.yaml does not auto-include this file,
-- add the following line to it:
--   - include:
--       file: db/changelog/changes/99-auth-password-reset.sql

--changeset cp:99-password-reset-tokens-create
CREATE TABLE password_reset_tokens (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    token_hash VARCHAR(255) NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    used_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_password_reset_tokens_user ON password_reset_tokens(user_id);
CREATE INDEX idx_password_reset_tokens_token_hash ON password_reset_tokens(token_hash);
CREATE INDEX idx_password_reset_tokens_expires ON password_reset_tokens(expires_at);
--rollback DROP TABLE password_reset_tokens;
