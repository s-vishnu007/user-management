--liquibase formatted sql

-- Re-architects license issuance from subscription-anchored to org+user-anchored with a
-- hand-picked RBAC grant set baked into each token (see admin-ui LicensesPage). A license is now
-- issued TO a specific user INSIDE an org, carrying exactly the permissions/roles chosen at issue
-- time — nothing is derived from a plan. The subscription path is retained (column stays, now
-- nullable) for back-compat with the subscription-scoped issue endpoint and existing tokens.

--changeset cp:20-license-tokens-user-grants
-- A per-user license has no subscription; drop the NOT NULL so org-anchored tokens can be inserted.
ALTER TABLE license_tokens ALTER COLUMN subscription_id DROP NOT NULL;

-- Direct org anchor so tenant-isolation (TenantAccessChecker.resolveOrgForJti) and the
-- org-scoped license list can resolve a token's owning org WITHOUT a subscription hop.
ALTER TABLE license_tokens ADD COLUMN org_id UUID REFERENCES organizations(id) ON DELETE CASCADE;
-- The user the license is issued to (subject of the JWT). SET NULL on user delete so the token row
-- (and its CRL/audit history) survives; subject_email keeps a durable display snapshot.
ALTER TABLE license_tokens ADD COLUMN user_id UUID REFERENCES users(id) ON DELETE SET NULL;
ALTER TABLE license_tokens ADD COLUMN subject_email VARCHAR(320);
-- JSON-array snapshots of exactly what was baked into the JWT, for the Licenses list / audit. Stored
-- as TEXT (the entity maps them as String) so Hibernate ddl-auto=validate stays happy; '[]' default
-- keeps legacy subscription-anchored rows valid.
ALTER TABLE license_tokens ADD COLUMN permissions TEXT NOT NULL DEFAULT '[]';
ALTER TABLE license_tokens ADD COLUMN roles TEXT NOT NULL DEFAULT '[]';

-- Backfill org_id for existing subscription-anchored tokens so the direct-org resolution path works
-- uniformly for old and new rows.
UPDATE license_tokens lt
SET org_id = s.org_id
FROM subscriptions s
WHERE lt.subscription_id = s.id AND lt.org_id IS NULL;

CREATE INDEX idx_license_tokens_org_status ON license_tokens(org_id, status);
CREATE INDEX idx_license_tokens_user ON license_tokens(user_id);
--rollback DROP INDEX IF EXISTS idx_license_tokens_user; DROP INDEX IF EXISTS idx_license_tokens_org_status; ALTER TABLE license_tokens DROP COLUMN roles; ALTER TABLE license_tokens DROP COLUMN permissions; ALTER TABLE license_tokens DROP COLUMN subject_email; ALTER TABLE license_tokens DROP COLUMN user_id; ALTER TABLE license_tokens DROP COLUMN org_id; ALTER TABLE license_tokens ALTER COLUMN subscription_id SET NOT NULL;
