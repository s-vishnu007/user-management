package com.example.cp.licenses;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface LicenseActivationRepository extends JpaRepository<LicenseActivation, UUID> {

    Optional<LicenseActivation> findByJtiAndNodeId(String jti, String nodeId);

    List<LicenseActivation> findByJtiOrderByLastSeenAtDesc(String jti);

    /** Count of distinct nodes seen within the lease window (i.e. currently-active seats). */
    long countByJtiAndLastSeenAtAfter(String jti, OffsetDateTime threshold);

    /** Activations considered active (last seen within the lease window). */
    List<LicenseActivation> findByJtiAndLastSeenAtAfterOrderByLastSeenAtDesc(String jti, OffsetDateTime threshold);
}
