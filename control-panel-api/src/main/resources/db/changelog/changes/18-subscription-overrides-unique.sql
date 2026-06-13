--liquibase formatted sql
-- Owner: licensing fixer (subscriptions overrides). Add a UNIQUE constraint on
-- subscription_overrides(subscription_id, type, key) so entitlement resolution is deterministic.
-- Add changesets below using the cp:18-suboverride-* id convention.

--changeset cp:18-suboverride-dedupe
-- Defensively collapse any pre-existing duplicate (subscription_id, type, key) rows before adding
-- the unique constraint: keep the lexicographically-smallest id per group, delete the rest. On a
-- fresh database this matches nothing; it only matters on an upgrade that already accumulated
-- duplicates under the previous order-dependent resolution.
DELETE FROM subscription_overrides o
USING subscription_overrides keep
WHERE o.subscription_id = keep.subscription_id
  AND o.type = keep.type
  AND o.key = keep.key
  AND o.id > keep.id;
--rollback SELECT 1;

--changeset cp:18-suboverride-unique
-- P3: without this, two PERMISSION/FEATURE overrides for the same (subscription, type, key) could
-- coexist and entitlement resolution became order-dependent (the iteration order of
-- findBySubscriptionId decided the winner). The unique constraint guarantees one override per
-- (subscription, type, key) so resolveEntitlements() is deterministic.
ALTER TABLE subscription_overrides
    ADD CONSTRAINT ux_subscription_overrides_sub_type_key UNIQUE (subscription_id, type, key);
--rollback ALTER TABLE subscription_overrides DROP CONSTRAINT ux_subscription_overrides_sub_type_key;
