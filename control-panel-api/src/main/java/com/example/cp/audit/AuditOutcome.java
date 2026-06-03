package com.example.cp.audit;

/**
 * Outcome dimension persisted to {@code audit_log.outcome} (via {@link #name()} as VARCHAR(16))
 * and read back by {@code AuditController.AuditLogDto}.
 *
 * <ul>
 *   <li>{@link #SUCCESS} — the audited action completed normally.</li>
 *   <li>{@link #DENIED} — authentication/authorization refusal (AccessDeniedException,
 *       AuthenticationException, or {@code ApiException} with status 401/403).</li>
 *   <li>{@link #FAILED} — any other thrown exception (validation 4xx, 5xx, business errors
 *       not classified as 401/403).</li>
 * </ul>
 */
public enum AuditOutcome {
    SUCCESS,
    DENIED,
    FAILED
}
