--liquibase formatted sql
-- Owner: licensing fixer. Add @Version optimistic-locking column to license_tokens so a concurrent
-- heartbeat cannot rewrite a committed revocation/expiry back to ACTIVE (lost update), and adjust
-- the license_tokens.subscription_id FK so revocation history is not erased on hard delete.
-- Add changesets below using the cp:18-license-* id convention.

--changeset cp:18-license-tokens-version
-- P1-8: optimistic-locking version so a heartbeat's last-seen flush cannot silently overwrite a
-- revocation/expiry committed by another transaction back to ACTIVE. Hibernate increments this on
-- each UPDATE of the entity; existing rows start at 0 via the DEFAULT.
ALTER TABLE license_tokens ADD COLUMN version BIGINT NOT NULL DEFAULT 0;
--rollback ALTER TABLE license_tokens DROP COLUMN version;

--changeset cp:18-license-tokens-expiring-warned-at
-- P2 (lifecycle dedup): a durable marker so the lifecycle sweeper warns about an expiring token at
-- most once, replacing the count-the-outbox-rows heuristic (which is racy across concurrent sweeps
-- and re-warns once the outbox is purged). NULL = never warned; set to the warn time on first warn.
ALTER TABLE license_tokens ADD COLUMN expiring_warned_at TIMESTAMPTZ;
--rollback ALTER TABLE license_tokens DROP COLUMN expiring_warned_at;

--changeset cp:18-license-artifacts-create
-- P1-4: persist the exact signed .lic artifact (raw JWT + envelope metadata) at issue time so
-- GET /licenses/{jti}/download is a PURE READ. Previously a download cache-miss re-minted a brand
-- new license (new jti / row / outbox event / zero audit) reachable by a read-only principal.
-- One row per issued jti; the artifact never changes after issue, so there is no update path.
CREATE TABLE license_artifacts (
    jti VARCHAR(64) PRIMARY KEY REFERENCES license_tokens(jti) ON DELETE CASCADE,
    jwt TEXT NOT NULL,
    kid VARCHAR(64) NOT NULL,
    plan_code VARCHAR(64),
    org_name TEXT,
    org_slug VARCHAR(190),
    issued_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
--rollback DROP TABLE license_artifacts;

--changeset cp:18-license-tokens-subscription-fk-restrict
-- P3: license_tokens.subscription_id was ON DELETE CASCADE, so a future hard delete of a
-- subscription would silently erase its revocation history (the jtis that must stay on the CRL).
-- Re-point the FK to ON DELETE RESTRICT so the revocation audit trail cannot be deleted out from
-- under the CRL. Low risk: nothing in the codebase hard-deletes a subscription (lifecycle uses
-- status transitions), so this only blocks a destructive operation that should never happen
-- silently. The subscriptions table itself still cascades from organizations.
ALTER TABLE license_tokens DROP CONSTRAINT license_tokens_subscription_id_fkey;
ALTER TABLE license_tokens ADD CONSTRAINT license_tokens_subscription_id_fkey
    FOREIGN KEY (subscription_id) REFERENCES subscriptions(id) ON DELETE RESTRICT;
--rollback ALTER TABLE license_tokens DROP CONSTRAINT license_tokens_subscription_id_fkey;
--rollback ALTER TABLE license_tokens ADD CONSTRAINT license_tokens_subscription_id_fkey FOREIGN KEY (subscription_id) REFERENCES subscriptions(id) ON DELETE CASCADE;
