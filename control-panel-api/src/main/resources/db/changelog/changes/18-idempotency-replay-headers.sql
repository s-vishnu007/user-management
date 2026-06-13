--liquibase formatted sql
-- Central fix for the api-contract fixer (which owns no migration): the idempotency replay
-- semantics fix added two nullable columns to the IdempotencyKey entity so a replayed response can
-- restore its Content-Type and Location headers (previously only status+body were restored). With
-- spring.jpa.hibernate.ddl-auto=validate these columns must exist or the context fails to start.

--changeset cp:18-idempotency-response-content-type
ALTER TABLE idempotency_keys ADD COLUMN response_content_type VARCHAR(128);
--rollback ALTER TABLE idempotency_keys DROP COLUMN response_content_type;

--changeset cp:18-idempotency-response-location
ALTER TABLE idempotency_keys ADD COLUMN response_location VARCHAR(2048);
--rollback ALTER TABLE idempotency_keys DROP COLUMN response_location;
