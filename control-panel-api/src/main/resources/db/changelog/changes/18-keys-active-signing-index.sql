--liquibase formatted sql
-- Owner: keys/crypto fixer. Add a partial unique index enforcing at most one ACTIVE signing key
-- (WHERE status='ACTIVE') so a concurrent rotate/bootstrap cannot leave two ACTIVE keys.
-- Add changesets below using the cp:18-keys-* id convention.

--changeset cp:18-keys-active-signing-unique
-- A signing key may be ACTIVE, RETIRED, or COMPROMISED. There must be at most ONE ACTIVE key at a
-- time: KeyService.generateNewActiveKey retires existing ACTIVE keys before inserting the new one,
-- but under READ COMMITTED two concurrent rotate/bootstrap transactions can both pass the "retire"
-- step and each insert an ACTIVE row, leaving two ACTIVE keys. A partial unique index makes the
-- second committer fail with a unique-constraint violation instead, so the invariant is enforced by
-- the database. The predicate is on status='ACTIVE' so RETIRED/COMPROMISED keys (of which there may
-- be many) are unaffected. Guarded with IF NOT EXISTS so re-runs are idempotent.
CREATE UNIQUE INDEX IF NOT EXISTS ux_signing_keys_single_active
    ON signing_keys (status)
    WHERE status = 'ACTIVE';
--rollback DROP INDEX IF EXISTS ux_signing_keys_single_active;
