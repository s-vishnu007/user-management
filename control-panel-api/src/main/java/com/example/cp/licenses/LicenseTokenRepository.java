package com.example.cp.licenses;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface LicenseTokenRepository extends JpaRepository<LicenseToken, UUID> {

    Optional<LicenseToken> findByJti(String jti);

    List<LicenseToken> findBySubscriptionIdOrderByIssuedAtDesc(UUID subscriptionId);

    List<LicenseToken> findBySubscriptionIdAndStatusOrderByIssuedAtDesc(UUID subscriptionId, LicenseToken.Status status);

    List<LicenseToken> findByStatusOrderByIssuedAtDesc(LicenseToken.Status status);

    List<LicenseToken> findByStatusAndRevokedAtAfterOrderByRevokedAtAsc(LicenseToken.Status status, OffsetDateTime since);

    List<LicenseToken> findByStatusOrderByRevokedAtAsc(LicenseToken.Status status);

    /** Tokens still marked {@code status} whose {@code expires_at} is at/before {@code cutoff} (overdue). */
    List<LicenseToken> findByStatusAndExpiresAtLessThanEqual(LicenseToken.Status status, OffsetDateTime cutoff);

    /**
     * Tokens still {@code status} expiring inside a window: {@code after < expires_at <= before}.
     * Used by the lifecycle scheduler to emit {@code license.expiring} warnings (not-yet-expired but
     * within {@code app.licensing.expiry-warning}).
     */
    List<LicenseToken> findByStatusAndExpiresAtGreaterThanAndExpiresAtLessThanEqual(
            LicenseToken.Status status, OffsetDateTime after, OffsetDateTime before);
}
