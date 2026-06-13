--liquibase formatted sql
-- Owner: billing fixer. Add @Version optimistic-locking column to invoices and a unique constraint
-- on (subscription_id, period) so the same period cannot be billed twice (duplicate-invoice guard).
-- Add changesets below using the cp:18-billing-* id convention.

-- @Version optimistic-locking column on invoices. Hibernate increments it on every UPDATE so a
-- concurrent issue/void can no longer silently overwrite the other (e.g. a VOID being rewritten back
-- to ISSUED): the losing writer fails with an OptimisticLockException. Existing rows start at 0.
--changeset cp:18-invoices-version
ALTER TABLE invoices ADD COLUMN version BIGINT NOT NULL DEFAULT 0;
--rollback ALTER TABLE invoices DROP COLUMN version;

-- Duplicate-invoice guard. A billing period for a subscription is identified by its period_start
-- (the UTC month boundary that usage_quotas are bucketed under). Only one non-VOID invoice may cover
-- a given (subscription_id, period_start); a partial unique index lets a VOIDed invoice be superseded
-- by a fresh draft for the same period. generateDraftInvoice also performs an in-application
-- existing-invoice check, and catches the violation of this constraint to return idempotently under a
-- concurrent generate race.
--changeset cp:18-invoices-unique-subscription-period
CREATE UNIQUE INDEX uq_invoices_subscription_period
    ON invoices (subscription_id, period_start)
    WHERE status <> 'VOID';
--rollback DROP INDEX uq_invoices_subscription_period;
