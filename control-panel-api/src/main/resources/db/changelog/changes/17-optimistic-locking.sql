--liquibase formatted sql
-- Adds JPA @Version optimistic-locking columns to the most write-contended aggregate tables so
-- concurrent updates (multi-instance deployment) cannot silently overwrite each other — the second
-- writer fails with an OptimisticLockException instead of a lost update. Hibernate increments the
-- column on each UPDATE; existing rows start at 0 via the DEFAULT.

--changeset cp:17-users-version
ALTER TABLE users ADD COLUMN version BIGINT NOT NULL DEFAULT 0;
--rollback ALTER TABLE users DROP COLUMN version;

--changeset cp:17-organizations-version
ALTER TABLE organizations ADD COLUMN version BIGINT NOT NULL DEFAULT 0;
--rollback ALTER TABLE organizations DROP COLUMN version;

--changeset cp:17-subscriptions-version
ALTER TABLE subscriptions ADD COLUMN version BIGINT NOT NULL DEFAULT 0;
--rollback ALTER TABLE subscriptions DROP COLUMN version;
