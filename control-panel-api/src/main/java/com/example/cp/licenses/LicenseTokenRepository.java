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
}
