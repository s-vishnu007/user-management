--liquibase formatted sql
-- Owner: self-service signup + email verification.
-- Adds an email_verified flag to users and a hashed, single-use email-verification token table
-- (mirrors password_reset_tokens). Registration is non-blocking: new users are ACTIVE but
-- email_verified=false until they confirm.

--changeset cp:19-users-email-verified
ALTER TABLE users ADD COLUMN email_verified BOOLEAN NOT NULL DEFAULT FALSE;
-- Grandfather every user that existed before this column (bootstrap admin, invited members,
-- SSO/JIT users) as verified so they are never nagged. Only new self-service signups insert
-- email_verified=FALSE going forward.
UPDATE users SET email_verified = TRUE;
--rollback ALTER TABLE users DROP COLUMN email_verified;

--changeset cp:19-email-verification-tokens-create
CREATE TABLE email_verification_tokens (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    token_hash VARCHAR(255) NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    used_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_email_verification_tokens_user ON email_verification_tokens(user_id);
CREATE INDEX idx_email_verification_tokens_token_hash ON email_verification_tokens(token_hash);
CREATE INDEX idx_email_verification_tokens_expires ON email_verification_tokens(expires_at);
--rollback DROP TABLE email_verification_tokens;
