--liquibase formatted sql
-- Owner: Bucket E (sso/ssrf). P0 SSO hardening.
-- Registered in db.changelog-master.yaml by bucket G (after 12-additional-permissions.sql).

--changeset cp:13-sso-read-permission
INSERT INTO permissions (code, name, description, category) VALUES
  ('sso.read', 'Read SSO', 'View SSO providers', 'sso')
ON CONFLICT (code) DO NOTHING;

-- Grant sso.read to SUPER_ADMIN, ORG_OWNER, ORG_ADMIN; sso.write to ORG_ADMIN
-- (sso.write already exists and is already granted to SUPER_ADMIN/ORG_OWNER).
INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r JOIN permissions p ON p.code = 'sso.read'
WHERE r.code = 'SUPER_ADMIN'
ON CONFLICT DO NOTHING;

INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r JOIN permissions p ON p.code = 'sso.read'
WHERE r.code = 'ORG_OWNER'
ON CONFLICT DO NOTHING;

INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r JOIN permissions p ON p.code IN ('sso.read', 'sso.write')
WHERE r.code = 'ORG_ADMIN'
ON CONFLICT DO NOTHING;
--rollback DELETE FROM role_permissions WHERE permission_id IN (SELECT id FROM permissions WHERE code = 'sso.read'); DELETE FROM permissions WHERE code = 'sso.read';

--changeset cp:13-sso-providers-columns
ALTER TABLE sso_providers ADD COLUMN IF NOT EXISTS client_secret_enc BYTEA;
ALTER TABLE sso_providers ADD COLUMN IF NOT EXISTS allowed_email_domains TEXT;
--rollback ALTER TABLE sso_providers DROP COLUMN IF EXISTS allowed_email_domains; ALTER TABLE sso_providers DROP COLUMN IF EXISTS client_secret_enc;

--changeset cp:13-sso-identities
CREATE TABLE sso_identities (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    provider_id UUID NOT NULL REFERENCES sso_providers(id) ON DELETE CASCADE,
    subject TEXT NOT NULL,
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ux_sso_identity UNIQUE (provider_id, subject)
);
CREATE INDEX idx_sso_identities_user ON sso_identities(user_id);
--rollback DROP TABLE sso_identities;
