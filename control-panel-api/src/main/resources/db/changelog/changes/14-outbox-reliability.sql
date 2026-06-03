--liquibase formatted sql
-- Owner: Bucket RESILIENCE (transactional outbox reliability / multi-instance safety).
-- Adds retry/backoff/poison-message tracking to outbox_events so the OutboxPublisher can claim a
-- batch with SELECT ... FOR UPDATE SKIP LOCKED (no double-publish across instances), retry failed
-- rows with exponential backoff, and quarantine poison messages after a max attempt count.
-- Addresses ROADMAP gaps #29 (multi-instance safety) and #68 (retry/backoff/DLQ).
-- Registered in db.changelog-master.yaml by bucket G as: db/changelog/changes/14-outbox-reliability.sql
--
-- Column/entity mapping (spring.jpa.hibernate.ddl-auto=validate, so each MUST match OutboxEvent):
--   attempts        INT NOT NULL DEFAULT 0        -> OutboxEvent.attempts (int)
--   next_attempt_at TIMESTAMPTZ                   -> OutboxEvent.nextAttemptAt (OffsetDateTime, nullable)
--   last_error      TEXT                          -> OutboxEvent.lastError (String, nullable)
--   status          VARCHAR(16) NOT NULL 'PENDING'-> OutboxEvent.status (enum PENDING/PUBLISHED/FAILED)
--
-- Existing rows (published_at set) predate this changeset; backfill their status to PUBLISHED so the
-- poller does not re-claim already-delivered events. Rows still awaiting delivery stay PENDING.

--changeset cp:14-outbox-reliability-columns
ALTER TABLE outbox_events ADD COLUMN attempts INT NOT NULL DEFAULT 0;
ALTER TABLE outbox_events ADD COLUMN next_attempt_at TIMESTAMPTZ;
ALTER TABLE outbox_events ADD COLUMN last_error TEXT;
ALTER TABLE outbox_events ADD COLUMN status VARCHAR(16) NOT NULL DEFAULT 'PENDING';
ALTER TABLE outbox_events ADD CONSTRAINT chk_outbox_status CHECK (status IN ('PENDING','PUBLISHED','FAILED'));
--rollback ALTER TABLE outbox_events DROP CONSTRAINT chk_outbox_status; ALTER TABLE outbox_events DROP COLUMN status; ALTER TABLE outbox_events DROP COLUMN last_error; ALTER TABLE outbox_events DROP COLUMN next_attempt_at; ALTER TABLE outbox_events DROP COLUMN attempts;

--changeset cp:14-outbox-reliability-backfill-published
-- Already-delivered rows (legacy) must not be re-claimed once status drives the poller.
UPDATE outbox_events SET status = 'PUBLISHED' WHERE published_at IS NOT NULL AND status = 'PENDING';
--rollback UPDATE outbox_events SET status = 'PENDING' WHERE published_at IS NOT NULL AND status = 'PUBLISHED';

--changeset cp:14-outbox-reliability-claim-index
-- Supports the claim query: status = 'PENDING' AND (next_attempt_at IS NULL OR next_attempt_at <= now())
-- ordered by occurred_at. Partial index keeps it small (only the work-to-do rows).
CREATE INDEX IF NOT EXISTS idx_outbox_events_claimable
    ON outbox_events (next_attempt_at, occurred_at)
    WHERE status = 'PENDING';
--rollback DROP INDEX IF EXISTS idx_outbox_events_claimable;
