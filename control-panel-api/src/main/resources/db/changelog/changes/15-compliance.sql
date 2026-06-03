--liquibase formatted sql
-- Owner: Bucket GDPR (data-subject export + right-to-erasure + tenant deletion). Wave 2.
-- Registered in db.changelog-master.yaml by bucket CONFIG (after the 14-*.sql changesets) as:
--   db/changelog/changes/15-compliance.sql
--
-- This migration ONLY introduces the erasure_log audit-of-record for GDPR/CCPA data-subject
-- requests (right of access / portability + right to erasure + tenant off-boarding). It does not
-- add or alter any PII-bearing column on existing tables; erasure pseudonymises in place via the
-- ErasureService using the existing repositories.

--changeset cp:15-compliance-erasure-log
-- One row per data-subject request (export OR erase OR tenant-delete). `completed_at` is set when
-- the operation finishes so a started-but-failed request is distinguishable from a completed one.
-- `requested_by` is the actor (super_admin / subject / org admin) that initiated the request.
CREATE TABLE erasure_log (
    id           UUID PRIMARY KEY,
    subject_type VARCHAR(16) NOT NULL CHECK (subject_type IN ('user','org')),
    subject_id   UUID NOT NULL,
    requested_by UUID,
    requested_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    completed_at TIMESTAMPTZ,
    action       VARCHAR(16) NOT NULL CHECK (action IN ('export','erase'))
);
CREATE INDEX idx_erasure_log_subject ON erasure_log (subject_type, subject_id);
CREATE INDEX idx_erasure_log_requested_at ON erasure_log (requested_at);
--rollback DROP TABLE erasure_log;
