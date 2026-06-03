--liquibase formatted sql

-- Wave 2 bucket LICENSING: make the dead lease/heartbeat schema real (seat/node activation
-- counting), add a license type flag (STANDARD/TRIAL) for first-class trials, and back the
-- lifecycle scheduler (ACTIVE->EXPIRED transitions + license.expiring outbox warnings).

--changeset cp:15-license-tokens-type
-- license_type distinguishes STANDARD (paid) from TRIAL licenses so trials can carry a short TTL
-- and be reported/converted distinctly. Matches LicenseToken.licenseType (EnumType.STRING).
ALTER TABLE license_tokens ADD COLUMN license_type VARCHAR(20) NOT NULL DEFAULT 'STANDARD'
    CHECK (license_type IN ('STANDARD','TRIAL'));
--rollback ALTER TABLE license_tokens DROP COLUMN license_type;

--changeset cp:15-license-activations-create
-- One row per (license jti, node_id): a node/seat that has activated against the license. The
-- heartbeat endpoint upserts last_seen_at on each call; "active seats" within the configurable
-- lease window are counted from these rows. node_id is the licensed app's self-reported node
-- identifier (host id / instance id). last_seen_ip is text (not inet) to match the String-mapped
-- JPA field, consistent with license_tokens.last_seen_ip.
CREATE TABLE license_activations (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    jti VARCHAR(64) NOT NULL REFERENCES license_tokens(jti) ON DELETE CASCADE,
    node_id VARCHAR(190) NOT NULL,
    first_seen_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_seen_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_seen_ip VARCHAR(45),
    CONSTRAINT ux_license_activations_jti_node UNIQUE (jti, node_id)
);
CREATE INDEX idx_license_activations_jti ON license_activations(jti);
CREATE INDEX idx_license_activations_jti_last_seen ON license_activations(jti, last_seen_at);
--rollback DROP TABLE license_activations;
