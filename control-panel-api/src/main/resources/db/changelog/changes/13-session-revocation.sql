--liquibase formatted sql
-- Owner: Bucket A (security/auth/session core).
-- Adds users.token_version as the durable per-user token-version (bulk session-revocation primitive).
-- Redis (cp:sess:tokenver:{userId}) is a write-through cache; this column is the source of truth so a
-- Redis flush cannot silently un-revoke sessions. JwtAuthFilter compares the JWT "tv" claim against this.
-- Registered in db.changelog-master.yaml by bucket G as: db/changelog/changes/13-session-revocation.sql

--changeset cp:13-users-token-version
ALTER TABLE users ADD COLUMN token_version BIGINT NOT NULL DEFAULT 0;
--rollback ALTER TABLE users DROP COLUMN token_version;
