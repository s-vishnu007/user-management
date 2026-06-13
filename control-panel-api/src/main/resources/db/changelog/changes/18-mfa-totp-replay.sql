--liquibase formatted sql
-- Owner: auth-session fixer. Persist the last-accepted TOTP time-step per MFA enrollment so a code
-- cannot be replayed within its validity window (reject step <= last_accepted_step).
-- Add changesets below using the cp:18-mfa-* id convention.

--changeset cp:18-mfa-last-accepted-step
-- The 30s TOTP time-step (epoch_seconds / 30) of the most-recently-accepted code. NULL means no code
-- has been accepted yet (fresh enrollment). MfaService rejects any code whose step is <= this value,
-- closing the ~90s replay window left open by allowedTimePeriodDiscrepancy=1.
ALTER TABLE user_mfa ADD COLUMN last_accepted_step BIGINT;
--rollback ALTER TABLE user_mfa DROP COLUMN last_accepted_step;
