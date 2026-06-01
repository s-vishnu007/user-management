--liquibase formatted sql

--changeset cp:02-roles-create
CREATE TABLE roles (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    code VARCHAR(64) UNIQUE NOT NULL,
    name VARCHAR(255),
    description TEXT,
    is_system BOOLEAN NOT NULL DEFAULT FALSE
);
--rollback DROP TABLE roles;

--changeset cp:02-permissions-create
CREATE TABLE permissions (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    code VARCHAR(128) UNIQUE NOT NULL,
    name VARCHAR(255),
    description TEXT,
    category VARCHAR(64)
);
--rollback DROP TABLE permissions;

--changeset cp:02-role-permissions-create
CREATE TABLE role_permissions (
    role_id UUID NOT NULL REFERENCES roles(id) ON DELETE CASCADE,
    permission_id UUID NOT NULL REFERENCES permissions(id) ON DELETE CASCADE,
    PRIMARY KEY (role_id, permission_id)
);
CREATE INDEX idx_role_permissions_permission ON role_permissions(permission_id);
--rollback DROP TABLE role_permissions;

--changeset cp:02-user-roles-create
CREATE TABLE user_roles (
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    role_id UUID NOT NULL REFERENCES roles(id) ON DELETE CASCADE,
    org_id UUID REFERENCES organizations(id) ON DELETE CASCADE,
    org_id_key UUID GENERATED ALWAYS AS (COALESCE(org_id, '00000000-0000-0000-0000-000000000000'::uuid)) STORED,
    PRIMARY KEY (user_id, role_id, org_id_key)
);
CREATE INDEX idx_user_roles_role ON user_roles(role_id);
CREATE INDEX idx_user_roles_org ON user_roles(org_id);
--rollback DROP TABLE user_roles;

--changeset cp:02-roles-seed
INSERT INTO roles (code, name, description, is_system) VALUES
    ('SUPER_ADMIN', 'Super Administrator', 'Platform-wide super administrator', TRUE),
    ('ORG_OWNER', 'Organization Owner', 'Owner of an organization', TRUE),
    ('ORG_ADMIN', 'Organization Administrator', 'Administrator within an organization', TRUE),
    ('ORG_MEMBER', 'Organization Member', 'Standard organization member', TRUE),
    ('VIEWER', 'Viewer', 'Read-only access', TRUE);
--rollback DELETE FROM roles WHERE code IN ('SUPER_ADMIN','ORG_OWNER','ORG_ADMIN','ORG_MEMBER','VIEWER');

--changeset cp:02-permissions-seed
INSERT INTO permissions (code, name, description, category) VALUES
    ('org.read', 'Read Organization', 'View organization details', 'org'),
    ('org.write', 'Write Organization', 'Modify organization', 'org'),
    ('subscription.read', 'Read Subscriptions', 'View subscriptions', 'subscription'),
    ('subscription.write', 'Write Subscriptions', 'Create or modify subscriptions', 'subscription'),
    ('license.issue', 'Issue License', 'Issue license tokens', 'license'),
    ('license.revoke', 'Revoke License', 'Revoke license tokens', 'license'),
    ('user.invite', 'Invite User', 'Invite new users', 'user'),
    ('user.read', 'Read Users', 'View users', 'user'),
    ('user.write', 'Write Users', 'Modify users', 'user'),
    ('audit.read', 'Read Audit Log', 'View audit log entries', 'audit'),
    ('sso.write', 'Write SSO', 'Configure SSO providers', 'sso'),
    ('apikey.write', 'Write API Keys', 'Manage API keys', 'apikey'),
    ('plan.write', 'Write Plans', 'Manage plans', 'plan');
--rollback DELETE FROM permissions WHERE code IN ('org.read','org.write','subscription.read','subscription.write','license.issue','license.revoke','user.invite','user.read','user.write','audit.read','sso.write','apikey.write','plan.write');

--changeset cp:02-role-permissions-seed
INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r CROSS JOIN permissions p WHERE r.code = 'SUPER_ADMIN';

INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r JOIN permissions p ON p.code IN (
    'org.read','org.write','subscription.read','subscription.write','license.issue','license.revoke',
    'user.invite','user.read','user.write','audit.read','sso.write','apikey.write'
) WHERE r.code = 'ORG_OWNER';

INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r JOIN permissions p ON p.code IN (
    'org.read','subscription.read','subscription.write','license.issue','license.revoke',
    'user.invite','user.read','user.write','audit.read','apikey.write'
) WHERE r.code = 'ORG_ADMIN';

INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r JOIN permissions p ON p.code IN (
    'org.read','subscription.read','user.read'
) WHERE r.code = 'ORG_MEMBER';

INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r JOIN permissions p ON p.code IN (
    'org.read','subscription.read','user.read','audit.read'
) WHERE r.code = 'VIEWER';
--rollback DELETE FROM role_permissions WHERE role_id IN (SELECT id FROM roles WHERE code IN ('SUPER_ADMIN','ORG_OWNER','ORG_ADMIN','ORG_MEMBER','VIEWER'));
