package com.example.cp.keys;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface SigningKeyRepository extends JpaRepository<SigningKey, UUID> {

    Optional<SigningKey> findByKid(String kid);

    Optional<SigningKey> findFirstByStatusOrderByCreatedAtDesc(SigningKey.Status status);

    List<SigningKey> findByStatus(SigningKey.Status status);

    List<SigningKey> findByStatusOrRetiredAtAfter(SigningKey.Status status, OffsetDateTime cutoff);

    /**
     * Keys eligible for publication at {@code /.well-known/jwks.json}: the current ACTIVE key plus
     * RETIRED keys still inside the retention window. COMPROMISED keys are <strong>never</strong>
     * returned — flagging a key COMPROMISED drops it from the JWKS immediately so offline verifiers
     * stop trusting it at their next refresh. Equivalent to the old
     * {@code findByStatusOrRetiredAtAfter(ACTIVE, cutoff)} but with the COMPROMISED exclusion made
     * explicit and the published statuses pinned to ACTIVE/RETIRED.
     */
    @Query("""
            SELECT k FROM SigningKey k
            WHERE (k.status = :active)
               OR (k.status = :retired AND k.retiredAt > :cutoff)
            """)
    List<SigningKey> findPublishable(@Param("active") SigningKey.Status active,
                                     @Param("retired") SigningKey.Status retired,
                                     @Param("cutoff") OffsetDateTime cutoff);
}
