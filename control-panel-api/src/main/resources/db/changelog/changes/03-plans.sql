--liquibase formatted sql

--changeset cp:03-plans-create
CREATE TABLE plans (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    code VARCHAR(64) UNIQUE NOT NULL,
    name VARCHAR(255),
    description TEXT,
    tier VARCHAR(32),
    is_active BOOLEAN NOT NULL DEFAULT TRUE,
    default_ttl_days INT NOT NULL DEFAULT 365,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
--rollback DROP TABLE plans;

--changeset cp:03-plan-permissions-create
CREATE TABLE plan_permissions (
    plan_id UUID NOT NULL REFERENCES plans(id) ON DELETE CASCADE,
    permission_code VARCHAR(128) NOT NULL,
    PRIMARY KEY (plan_id, permission_code)
);
--rollback DROP TABLE plan_permissions;

--changeset cp:03-plan-features-create
CREATE TABLE plan_features (
    plan_id UUID NOT NULL REFERENCES plans(id) ON DELETE CASCADE,
    feature_key VARCHAR(64) NOT NULL,
    value_json JSONB NOT NULL,
    PRIMARY KEY (plan_id, feature_key)
);
--rollback DROP TABLE plan_features;

--changeset cp:03-plans-seed
INSERT INTO plans (code, name, description, tier, is_active, default_ttl_days) VALUES
    ('starter', 'Starter', 'Entry tier for small teams', 'starter', TRUE, 365),
    ('pro', 'Pro', 'Professional tier for growing teams', 'pro', TRUE, 365),
    ('enterprise', 'Enterprise', 'Enterprise tier with full features', 'enterprise', TRUE, 365);
--rollback DELETE FROM plans WHERE code IN ('starter','pro','enterprise');

--changeset cp:03-plan-permissions-seed
INSERT INTO plan_permissions (plan_id, permission_code)
SELECT p.id, t.code FROM plans p, (VALUES
    ('export.csv'),
    ('api.v1')
) AS t(code) WHERE p.code = 'starter';

INSERT INTO plan_permissions (plan_id, permission_code)
SELECT p.id, t.code FROM plans p, (VALUES
    ('export.csv'),
    ('export.pdf'),
    ('api.v1'),
    ('api.v2'),
    ('admin.users.invite')
) AS t(code) WHERE p.code = 'pro';

INSERT INTO plan_permissions (plan_id, permission_code)
SELECT p.id, t.code FROM plans p, (VALUES
    ('export.csv'),
    ('export.pdf'),
    ('export.xlsx'),
    ('api.v1'),
    ('api.v2'),
    ('admin.users.invite'),
    ('admin.sso.configure'),
    ('admin.audit.export')
) AS t(code) WHERE p.code = 'enterprise';
--rollback DELETE FROM plan_permissions WHERE plan_id IN (SELECT id FROM plans WHERE code IN ('starter','pro','enterprise'));

--changeset cp:03-plan-features-seed
INSERT INTO plan_features (plan_id, feature_key, value_json)
SELECT p.id, k, v::jsonb FROM plans p, (VALUES
    ('max_users', '10'),
    ('max_storage_gb', '10'),
    ('ai_assistant', 'false')
) AS t(k, v) WHERE p.code = 'starter';

INSERT INTO plan_features (plan_id, feature_key, value_json)
SELECT p.id, k, v::jsonb FROM plans p, (VALUES
    ('max_users', '50'),
    ('max_storage_gb', '100'),
    ('ai_assistant', 'true')
) AS t(k, v) WHERE p.code = 'pro';

INSERT INTO plan_features (plan_id, feature_key, value_json)
SELECT p.id, k, v::jsonb FROM plans p, (VALUES
    ('max_users', '1000'),
    ('max_storage_gb', '1000'),
    ('ai_assistant', 'true')
) AS t(k, v) WHERE p.code = 'enterprise';
--rollback DELETE FROM plan_features WHERE plan_id IN (SELECT id FROM plans WHERE code IN ('starter','pro','enterprise'));
