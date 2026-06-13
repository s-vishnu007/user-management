--liquibase formatted sql
-- Owner: webhooks/integrations fixer. DDL for: durable webhook fan-out high-water mark (or a
-- fanned_out_at marker on outbox_events), a b-tree index on outbox_events(occurred_at) for the
-- fan-out scan, and a UNIQUE(org_id, user_id) constraint on the SCIM external-id mapping table.
-- Put ALL DDL for the webhooks AND scim packages in this single file.
-- Add changesets below using the cp:18-webhook-* / cp:18-scim-* id convention.

--changeset cp:18-webhook-outbox-fanned-out-marker
-- P1-11: durable per-event "fanned out" marker so the webhook fan-out never silently drops an event
-- that ages out of the time-window before a successful tick. The scanner claims rows where
-- fanned_out_at IS NULL with FOR UPDATE SKIP LOCKED, enqueues the per-subscription delivery rows, then
-- stamps fanned_out_at — making fan-out a durable, replay-safe, at-least-once cursor instead of a
-- best-effort lookback. A partial b-tree index on the un-fanned rows (ordered by occurred_at) keeps the
-- claim query index-only as the table grows.
ALTER TABLE outbox_events ADD COLUMN fanned_out_at TIMESTAMPTZ;
CREATE INDEX idx_outbox_events_unfanned
    ON outbox_events (occurred_at)
    WHERE fanned_out_at IS NULL;
-- Plain b-tree on occurred_at for the bounded backstop scan / general range reads (P2 outbox index).
CREATE INDEX idx_outbox_events_occurred_at ON outbox_events (occurred_at);
--rollback DROP INDEX IF EXISTS idx_outbox_events_occurred_at; DROP INDEX IF EXISTS idx_outbox_events_unfanned; ALTER TABLE outbox_events DROP COLUMN fanned_out_at;

--changeset cp:18-scim-user-mappings-unique-org-user
-- P2 SCIM: a control-panel user maps to at most ONE SCIM resource per org. Without this, a
-- check-then-insert race (or the soft-delete/re-provision lifecycle) could create duplicate mappings
-- for the same (org_id, user_id). The service catches the violation and returns a SCIM 409 uniqueness
-- error. (org_id, external_id) is already unique from 16-scim.sql; this closes the user_id side.
ALTER TABLE scim_user_mappings
    ADD CONSTRAINT ux_scim_user_mappings_org_user UNIQUE (org_id, user_id);
--rollback ALTER TABLE scim_user_mappings DROP CONSTRAINT ux_scim_user_mappings_org_user;

--changeset cp:18-audit-log-allow-gdpr-redaction splitStatements:false
-- P2 GDPR: the audit_log is append-only/tamper-evident (08-audit.sql installs BEFORE UPDATE/DELETE
-- triggers that reject every mutation). GDPR Art. 17 erasure, however, must scrub PII embedded in the
-- retained rows of an erased subject (raw client IP in ip_address, and any PII the SCIM provisioned
-- payload wrote into payload_json). We narrowly widen the immutability rule: an UPDATE is permitted
-- ONLY when the transaction has explicitly opted in via the session GUC `app.audit_redaction = 'on'`
-- (set LOCAL by ErasureService inside the erasure transaction) AND it does not touch the identity /
-- integrity columns (id, actor_user_id, actor_org_id, action, target_type, target_id, occurred_at,
-- outcome). DELETE remains categorically forbidden, and any UPDATE outside a redaction transaction is
-- still rejected — so the tamper-evidence guarantee for the security trail is preserved while the
-- right-to-erasure can scrub PII.
CREATE OR REPLACE FUNCTION audit_log_block_modifications() RETURNS trigger AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'audit_log is immutable: DELETE operation is not permitted';
    END IF;
    -- UPDATE path: only a flagged GDPR redaction transaction may proceed, and only to scrub PII.
    IF current_setting('app.audit_redaction', true) IS DISTINCT FROM 'on' THEN
        RAISE EXCEPTION 'audit_log is immutable: UPDATE operation is not permitted';
    END IF;
    IF NEW.id IS DISTINCT FROM OLD.id
        OR NEW.actor_user_id IS DISTINCT FROM OLD.actor_user_id
        OR NEW.actor_org_id IS DISTINCT FROM OLD.actor_org_id
        OR NEW.action IS DISTINCT FROM OLD.action
        OR NEW.target_type IS DISTINCT FROM OLD.target_type
        OR NEW.target_id IS DISTINCT FROM OLD.target_id
        OR NEW.occurred_at IS DISTINCT FROM OLD.occurred_at
        OR NEW.outcome IS DISTINCT FROM OLD.outcome THEN
        RAISE EXCEPTION 'audit_log redaction may only scrub payload_json/ip_address, not identity columns';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;
--rollback CREATE OR REPLACE FUNCTION audit_log_block_modifications() RETURNS trigger AS $$ BEGIN RAISE EXCEPTION 'audit_log is immutable: % operation is not permitted', TG_OP; END; $$ LANGUAGE plpgsql;
