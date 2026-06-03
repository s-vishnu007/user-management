--liquibase formatted sql
-- Owner: Bucket SCIM (SCIM 2.0 user provisioning/deprovisioning for enterprise IdPs).
-- Adds one table:
--   scim_user_mappings – per-org link from an IdP-assigned externalId to a control-panel user.
--                        This is the durable identity bridge that lets an IdP address users it
--                        provisioned (by externalId or by the mapping's own id) without ever
--                        learning another tenant's user ids; UNIQUE(org_id, external_id) makes the
--                        externalId stable+unique within a tenant (the SCIM client's namespace).
-- Registered in db.changelog-master.yaml as: db/changelog/changes/16-scim.sql
--
-- Column/entity mapping (spring.jpa.hibernate.ddl-auto=validate, so each MUST match the entity
-- com.example.cp.scim.ScimUserMapping):
--   scim_user_mappings
--     id           UUID PK                              -> id (UUID)
--     org_id       UUID NOT NULL FK organizations(id)   -> orgId (UUID)
--     external_id  TEXT (nullable)                      -> externalId (String, nullable)
--     user_id      UUID NOT NULL FK users(id)           -> userId (UUID)
--     created_at   TIMESTAMPTZ NOT NULL                 -> createdAt (OffsetDateTime)
--   UNIQUE(org_id, external_id)  -> a given IdP externalId maps to at most one user per org.

--changeset cp:16-scim-user-mappings
CREATE TABLE scim_user_mappings (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    org_id UUID NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    external_id TEXT,
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ux_scim_user_mappings_org_external UNIQUE (org_id, external_id)
);
-- A SCIM client looks up "the mapping for this user in my org" when linking an existing user and
-- when listing; index org_id + user_id to keep those reads index-only.
CREATE INDEX idx_scim_user_mappings_org_user ON scim_user_mappings(org_id, user_id);
--rollback DROP TABLE scim_user_mappings;

--changeset cp:16-scim-permissions-seed
-- The scim.manage authority/scope. It is carried by an org-bound API key (the SCIM client credential)
-- and gates every /scim/v2 endpoint; also granted to SUPER_ADMIN so a platform admin can exercise the
-- endpoints in the admin console. ON CONFLICT keeps the migration idempotent / re-runnable.
INSERT INTO permissions (code, name, description, category) VALUES
    ('scim.manage', 'Manage SCIM', 'Provision and deprovision users via SCIM 2.0', 'scim')
ON CONFLICT (code) DO NOTHING;

INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r JOIN permissions p ON p.code = 'scim.manage'
WHERE r.code = 'SUPER_ADMIN'
ON CONFLICT DO NOTHING;
--rollback DELETE FROM role_permissions WHERE permission_id IN (SELECT id FROM permissions WHERE code = 'scim.manage'); DELETE FROM permissions WHERE code = 'scim.manage';
