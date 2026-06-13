package com.example.cp.auth;

/**
 * Raised when a durable session-revocation write (jti denylist) cannot be persisted — e.g. a Redis
 * outage during logout. Surfaced so the logout endpoint returns {@code 503} (the client should
 * retry) rather than silently reporting a successful logout while the token remains valid until its
 * natural expiry.
 */
public class RevocationStoreException extends RuntimeException {

    public RevocationStoreException(String message, Throwable cause) {
        super(message, cause);
    }
}
