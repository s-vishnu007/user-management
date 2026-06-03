--liquibase formatted sql
-- Owner: Bucket KEYS (key-encryption-key rotation + COMPROMISED key status + JWKS exclusion).
-- Widens the signing_keys.status CHECK to allow a third terminal state, COMPROMISED, so a leaked
-- signing key can be flagged and immediately dropped from the published JWKS (offline verifiers stop
-- trusting it at their next JWKS refresh). SigningKey.status maps to this widened CHECK under
-- ddl-auto=validate (the column type/constraint are unchanged structurally — only the allowed value
-- set grows — so validation still passes).
--
-- Registered in db.changelog-master.yaml by bucket CONFIG/G as: db/changelog/changes/16-keys.sql
--
-- Note on the KEK (key-encryption-key) rotation feature: re-encrypting signing_keys.private_key_encrypted
-- under a new active KEK is a pure data operation performed in application code (KeyService.rotateKek),
-- not a schema change, so there is no DDL for it here. The ciphertext format is self-describing
-- (a versioned blob carries its KEK id inline; legacy unversioned blobs decrypt under the default KEK).

--changeset cp:16-signing-keys-status-compromised
ALTER TABLE signing_keys DROP CONSTRAINT IF EXISTS signing_keys_status_check;
ALTER TABLE signing_keys ADD CONSTRAINT signing_keys_status_check CHECK (status IN ('ACTIVE','RETIRED','COMPROMISED'));
--rollback ALTER TABLE signing_keys DROP CONSTRAINT IF EXISTS signing_keys_status_check; ALTER TABLE signing_keys ADD CONSTRAINT signing_keys_status_check CHECK (status IN ('ACTIVE','RETIRED'));
