--liquibase formatted sql
-- Owner: apikeys fixer. Add @Version optimistic-locking column to api_keys so a concurrent
-- verify() last-used-at write cannot silently overwrite a committed revoke() (lost update).
-- Add changesets below using the cp:18-apikey-* id convention.

--changeset cp:18-apikey-version
ALTER TABLE api_keys ADD COLUMN version BIGINT NOT NULL DEFAULT 0;
--rollback ALTER TABLE api_keys DROP COLUMN version;
