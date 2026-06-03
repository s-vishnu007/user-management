package com.example.cp.auth;

import org.springframework.stereotype.Component;

/**
 * @deprecated Superseded by {@link LoginRateLimiter} (per-email AND per-IP, cluster-safe via Redis).
 * Retained only as a thin backward-compatible delegate for any caller still on the per-email-only
 * API; new code MUST use {@link LoginRateLimiter} directly so the per-IP ceiling and shared
 * counters apply. This shim passes a {@code null} IP, so it exercises only the per-account path.
 */
@Deprecated(forRemoval = true)
@Component
public class LoginAttempt {

    private final LoginRateLimiter delegate;

    public LoginAttempt(LoginRateLimiter delegate) {
        this.delegate = delegate;
    }

    public boolean isLocked(String email) {
        return delegate.isLocked(email, null);
    }

    public void recordFailure(String email) {
        delegate.recordFailure(email, null);
    }

    public void recordSuccess(String email) {
        delegate.recordSuccess(email, null);
    }
}
