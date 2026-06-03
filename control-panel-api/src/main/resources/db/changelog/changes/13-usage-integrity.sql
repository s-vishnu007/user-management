--liquibase formatted sql

--changeset cp:13-usage-events-event-id
ALTER TABLE usage_events ADD COLUMN event_id VARCHAR(120);
--rollback ALTER TABLE usage_events DROP COLUMN event_id;

--changeset cp:13-usage-events-dedup-uidx
CREATE UNIQUE INDEX IF NOT EXISTS ux_usage_events_dedup
    ON usage_events (subscription_id, jti, event_id)
    WHERE event_id IS NOT NULL;
--rollback DROP INDEX IF EXISTS ux_usage_events_dedup;

--changeset cp:13-usage-events-qty-check
ALTER TABLE usage_events ADD CONSTRAINT ck_usage_qty_nonneg CHECK (quantity >= 0);
--rollback ALTER TABLE usage_events DROP CONSTRAINT ck_usage_qty_nonneg;

--changeset cp:13-usage-quotas-checks
ALTER TABLE usage_quotas ADD CONSTRAINT chk_usage_quotas_consumed_nonneg CHECK (consumed_value >= 0);
ALTER TABLE usage_quotas ADD CONSTRAINT chk_usage_quotas_limit_nonneg CHECK (limit_value IS NULL OR limit_value >= 0);
--rollback ALTER TABLE usage_quotas DROP CONSTRAINT chk_usage_quotas_consumed_nonneg; ALTER TABLE usage_quotas DROP CONSTRAINT chk_usage_quotas_limit_nonneg;

--changeset cp:13-usage-permissions-seed
INSERT INTO permissions (code, name, description, category) VALUES
    ('usage.read', 'Read usage', 'View usage events and quotas', 'usage'),
    ('usage.ingest', 'Ingest usage', 'Ingest usage events', 'usage'),
    ('license.read', 'Read licenses', 'View license metadata', 'license')
ON CONFLICT (code) DO NOTHING;

INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r JOIN permissions p ON p.code IN (
    'usage.read', 'usage.ingest', 'license.read'
) WHERE r.code = 'SUPER_ADMIN'
ON CONFLICT DO NOTHING;

INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r JOIN permissions p ON p.code IN (
    'usage.read', 'usage.ingest', 'license.read'
) WHERE r.code = 'ORG_OWNER'
ON CONFLICT DO NOTHING;

INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r JOIN permissions p ON p.code IN (
    'usage.read', 'usage.ingest', 'license.read'
) WHERE r.code = 'ORG_ADMIN'
ON CONFLICT DO NOTHING;

INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r JOIN permissions p ON p.code IN (
    'usage.read', 'license.read'
) WHERE r.code = 'ORG_MEMBER'
ON CONFLICT DO NOTHING;

INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r JOIN permissions p ON p.code IN (
    'usage.read', 'license.read'
) WHERE r.code = 'VIEWER'
ON CONFLICT DO NOTHING;
--rollback DELETE FROM role_permissions WHERE permission_id IN (SELECT id FROM permissions WHERE code IN ('usage.read','usage.ingest')); DELETE FROM permissions WHERE code IN ('usage.read','usage.ingest');
