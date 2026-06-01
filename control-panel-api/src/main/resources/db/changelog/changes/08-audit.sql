--liquibase formatted sql

--changeset cp:08-audit-log-create
CREATE TABLE audit_log (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    actor_user_id UUID,
    actor_org_id UUID,
    action VARCHAR(128) NOT NULL,
    target_type VARCHAR(64),
    target_id VARCHAR(128),
    payload_json JSONB,
    ip_address INET,
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_audit_log_occurred_at ON audit_log(occurred_at);
CREATE INDEX idx_audit_log_actor_occurred ON audit_log(actor_user_id, occurred_at);
CREATE INDEX idx_audit_log_target ON audit_log(target_type, target_id);
--rollback DROP TABLE audit_log;

--changeset cp:08-audit-log-immutability splitStatements:false
CREATE OR REPLACE FUNCTION audit_log_block_modifications() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'audit_log is immutable: % operation is not permitted', TG_OP;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER audit_log_no_update
BEFORE UPDATE ON audit_log
FOR EACH ROW EXECUTE FUNCTION audit_log_block_modifications();

CREATE TRIGGER audit_log_no_delete
BEFORE DELETE ON audit_log
FOR EACH ROW EXECUTE FUNCTION audit_log_block_modifications();
--rollback DROP TRIGGER IF EXISTS audit_log_no_update ON audit_log; DROP TRIGGER IF EXISTS audit_log_no_delete ON audit_log; DROP FUNCTION IF EXISTS audit_log_block_modifications();
