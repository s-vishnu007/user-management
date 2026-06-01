--liquibase formatted sql

--changeset cp:12-additional-permissions
INSERT INTO permissions (code, name, description, category) VALUES
  ('key.rotate', 'Rotate signing keys', 'Generate and activate new signing keys', 'keys'),
  ('key.read', 'Read signing keys', 'View signing key metadata', 'keys'),
  ('event.read', 'Read event stream', 'Query outbox event stream', 'events'),
  ('plan.read', 'Read plans', 'View plan catalog', 'plans'),
  ('plan.create', 'Create plans', 'Create plan definitions', 'plans'),
  ('subscription.create', 'Create subscriptions', 'Provision subscriptions for organizations', 'subscriptions'),
  ('subscription.suspend', 'Suspend subscriptions', 'Temporarily disable subscriptions', 'subscriptions'),
  ('subscription.cancel', 'Cancel subscriptions', 'Permanently end subscriptions', 'subscriptions'),
  ('license.read', 'Read licenses', 'View license metadata', 'licenses'),
  ('org.create', 'Create organizations', 'Provision new organizations', 'orgs'),
  ('org.members.add', 'Add organization members', 'Add users to organizations', 'orgs'),
  ('org.members.remove', 'Remove organization members', 'Remove users from organizations', 'orgs'),
  ('api-key.create', 'Create API keys', 'Issue new programmatic API keys', 'api-keys'),
  ('api-key.delete', 'Delete API keys', 'Revoke programmatic API keys', 'api-keys')
ON CONFLICT (code) DO NOTHING;

INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM roles r CROSS JOIN permissions p
WHERE r.code = 'SUPER_ADMIN'
ON CONFLICT DO NOTHING;
--rollback DELETE FROM role_permissions WHERE permission_id IN (SELECT id FROM permissions WHERE code IN ('key.rotate','key.read','event.read','plan.read','plan.create','subscription.create','subscription.suspend','subscription.cancel','license.read','org.create','org.members.add','org.members.remove','api-key.create','api-key.delete')); DELETE FROM permissions WHERE code IN ('key.rotate','key.read','event.read','plan.read','plan.create','subscription.create','subscription.suspend','subscription.cancel','license.read','org.create','org.members.add','org.members.remove','api-key.create','api-key.delete');
