--liquibase formatted sql
-- Owner: Bucket AUTH-MFA (MFA/TOTP + login lockout + password policy). P1 Wave 1.
-- Registered in db.changelog-master.yaml by bucket CONFIG (after the 13-*.sql changesets).

--changeset cp:14-mfa-user-mfa
-- Per-user TOTP enrollment. The TOTP shared secret is stored AES-GCM-encrypted (KeyEncryptor),
-- never in the clear. The row exists only while the user is enrolling or enrolled; `enabled`
-- flips to true once the user confirms a code (POST /api/v1/auth/mfa/verify).
CREATE TABLE user_mfa (
    user_id    UUID PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
    secret_enc BYTEA NOT NULL,
    enabled    BOOLEAN NOT NULL DEFAULT false,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
--rollback DROP TABLE user_mfa;
