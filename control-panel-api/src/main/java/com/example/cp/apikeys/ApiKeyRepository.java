package com.example.cp.apikeys;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface ApiKeyRepository extends JpaRepository<ApiKey, UUID> {

    List<ApiKey> findByOrgId(UUID orgId);

    Page<ApiKey> findByOrgId(UUID orgId, Pageable pageable);

    List<ApiKey> findByKeyPrefix(String keyPrefix);

    Optional<ApiKey> findByKeyHash(String keyHash);

    /**
     * Targeted, guarded last-used-at touch. Updates only the {@code last_used_at} column and only
     * while the key is still live ({@code revoked_at IS NULL}), so it can never resurrect a key that
     * a concurrent {@link #revokeIfActive} committed between this caller's read and write. The
     * {@code version} bump keeps the JPA-managed optimistic-lock counter monotonic. Returns the
     * number of rows touched (0 if the key was concurrently revoked).
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update ApiKey k
               set k.lastUsedAt = :now, k.version = k.version + 1
             where k.id = :id and k.revokedAt is null
            """)
    int touchLastUsedIfActive(@Param("id") UUID id, @Param("now") OffsetDateTime now);

    /**
     * Guarded conditional revoke. Stamps {@code revoked_at} only while the key is still live, so a
     * concurrent {@link #touchLastUsedIfActive} cannot overwrite it back to NULL. Returns the number
     * of rows revoked (0 if already revoked).
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update ApiKey k
               set k.revokedAt = :now, k.version = k.version + 1
             where k.id = :id and k.orgId = :orgId and k.revokedAt is null
            """)
    int revokeIfActive(@Param("id") UUID id, @Param("orgId") UUID orgId, @Param("now") OffsetDateTime now);
}
