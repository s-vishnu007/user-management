package com.example.licenseverifier;

/**
 * Abstraction the {@link LicenseVerifier} consults to decide whether a verified license jti is
 * revoked. The default no-op implementation ({@link #none()}) preserves the historical behavior
 * when no CRL is configured; the Spring Boot starter supplies a CRL-backed implementation that
 * fails closed when the cached revocation list is stale.
 */
public interface RevocationChecker {

    /**
     * @return {@code true} if the given license jti has been revoked.
     */
    boolean isRevoked(String jti);

    /**
     * Whether the checker currently has a usable view of revocation state. A fail-closed checker
     * returns {@code false} here when it cannot prove a jti is <em>not</em> revoked (e.g. its
     * cached CRL is stale or never loaded); the {@link LicenseVerifier} then rejects every license.
     */
    default boolean isOperational() {
        return true;
    }

    /**
     * @return a singleton no-op checker that never revokes and is always operational, preserving
     *         the behavior of callers that do not configure revocation checking.
     */
    static RevocationChecker none() {
        return NoneHolder.INSTANCE;
    }

    /** Lazy holder for the {@link #none()} singleton. */
    final class NoneHolder {
        private static final RevocationChecker INSTANCE = new RevocationChecker() {
            @Override
            public boolean isRevoked(String jti) {
                return false;
            }

            @Override
            public boolean isOperational() {
                return true;
            }
        };

        private NoneHolder() {
        }
    }
}
