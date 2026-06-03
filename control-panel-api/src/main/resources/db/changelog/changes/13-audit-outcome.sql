--liquibase formatted sql
-- Owner: Bucket F (audit core).
-- Adds audit_log.outcome so security-relevant DENIED/FAILED events are first-class alongside SUCCESS.
-- AuditWriter persists AuditOutcome.name() into this column; AuditLog maps it via @Enumerated(STRING).
-- Registered in db.changelog-master.yaml by bucket G as: db/changelog/changes/13-audit-outcome.sql
--
-- Least-privilege note (defense-in-depth, NOT enforced here to avoid breaking the single-role dev/test
-- setup): in production the application should connect as a role with INSERT/SELECT-only on audit_log
-- (no UPDATE/DELETE/TRUNCATE and no table ownership), while Liquibase migrations run under a separate
-- privileged role. Owner-modifiable triggers are not a sufficient tamper control on their own.

--changeset cp:13-audit-log-outcome
ALTER TABLE audit_log ADD COLUMN outcome VARCHAR(16) NOT NULL DEFAULT 'SUCCESS';
ALTER TABLE audit_log ADD CONSTRAINT chk_audit_outcome CHECK (outcome IN ('SUCCESS','DENIED','FAILED'));
--rollback ALTER TABLE audit_log DROP CONSTRAINT chk_audit_outcome; ALTER TABLE audit_log DROP COLUMN outcome;

--changeset cp:13-audit-log-outcome-index
CREATE INDEX IF NOT EXISTS idx_audit_log_action_outcome ON audit_log (action, outcome, occurred_at);
--rollback DROP INDEX IF EXISTS idx_audit_log_action_outcome;
