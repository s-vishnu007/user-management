--liquibase formatted sql

--changeset cp:13-rbac-permissions-seed
INSERT INTO permissions (code, name, description, category) VALUES
  ('role.assign', 'Assign Roles', 'Assign and remove RBAC roles to users', 'rbac'),
  ('rbac.read', 'Read RBAC Catalog', 'View roles and permissions catalog', 'rbac')
ON CONFLICT (code) DO NOTHING;

INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r JOIN permissions p ON p.code IN ('role.assign','rbac.read')
WHERE r.code = 'SUPER_ADMIN'
ON CONFLICT DO NOTHING;

INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r JOIN permissions p ON p.code = 'role.assign'
WHERE r.code = 'ORG_OWNER'
ON CONFLICT DO NOTHING;

INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r JOIN permissions p ON p.code = 'rbac.read'
WHERE r.code IN ('ORG_OWNER','ORG_ADMIN')
ON CONFLICT DO NOTHING;
--rollback DELETE FROM role_permissions WHERE permission_id IN (SELECT id FROM permissions WHERE code IN ('role.assign','rbac.read')); DELETE FROM permissions WHERE code IN ('role.assign','rbac.read');
