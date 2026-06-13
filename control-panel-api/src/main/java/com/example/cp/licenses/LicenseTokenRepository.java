package com.example.cp.licenses;

import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface LicenseTokenRepository extends JpaRepository<LicenseToken, UUID> {

    Optional<LicenseToken> findByJti(String jti);

    /**
     * Pessimistic row lock on the token used by the heartbeat seat-enforcement path: serializes the
     * count-active-seats / insert-activation sequence for a given jti so N concurrent first beats
     * from distinct nodes cannot each read {@code limit-1} and all insert, exceeding the seat cap
     * (audit P1-9 TOCTOU).
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT t FROM LicenseToken t WHERE t.jti = :jti")
    Optional<LicenseToken> findByJtiForUpdate(@Param("jti") String jti);

    /**
     * Guarded last-seen update that only touches a token still {@code ACTIVE}. Returns the number of
     * rows updated (0 if the token was concurrently revoked/expired). Used by the heartbeat so a
     * benign last-seen refresh never resurrects a token whose revocation/expiry committed
     * concurrently (audit P1-8).
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE LicenseToken t SET t.lastSeenAt = :seenAt, t.lastSeenIp = :seenIp "
            + "WHERE t.jti = :jti AND t.status = :active")
    int touchLastSeenIfActive(@Param("jti") String jti,
                              @Param("seenAt") OffsetDateTime seenAt,
                              @Param("seenIp") String seenIp,
                              @Param("active") LicenseToken.Status active);

    /**
     * Guarded conditional revocation UPDATE: flips a token to REVOKED only if it is not already
     * REVOKED, stamping {@code revoked_at}/{@code revoke_reason}. Returns rows updated (0 if already
     * revoked). Atomic w.r.t. a concurrent heartbeat's last-seen update so the jti reliably reaches
     * the CRL and cannot be un-revoked (audit P1-8).
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE LicenseToken t SET t.status = :revoked, "
            + "t.revokedAt = :revokedAt, t.revokeReason = :reason "
            + "WHERE t.jti = :jti AND t.status <> :revoked")
    int revokeIfNotRevoked(@Param("jti") String jti,
                           @Param("revokedAt") OffsetDateTime revokedAt,
                           @Param("reason") String reason,
                           @Param("revoked") LicenseToken.Status revoked);

    List<LicenseToken> findBySubscriptionIdOrderByIssuedAtDesc(UUID subscriptionId);

    List<LicenseToken> findBySubscriptionIdOrderByIssuedAtDesc(UUID subscriptionId, Pageable pageable);

    List<LicenseToken> findBySubscriptionIdAndStatusOrderByIssuedAtDesc(UUID subscriptionId, LicenseToken.Status status);

    List<LicenseToken> findBySubscriptionIdAndStatusOrderByIssuedAtDesc(UUID subscriptionId, LicenseToken.Status status, Pageable pageable);

    List<LicenseToken> findByStatusOrderByIssuedAtDesc(LicenseToken.Status status);

    /** All tokens of a subscription currently in the given status (used to cascade revocation). */
    List<LicenseToken> findBySubscriptionIdAndStatus(UUID subscriptionId, LicenseToken.Status status);

    /** Most-recent revocation timestamp across all REVOKED tokens, or null if none. Used as a cheap
     * cache key for the signed CRL so it is only re-signed when the revoked set changes. */
    @Query("SELECT MAX(t.revokedAt) FROM LicenseToken t WHERE t.status = :revoked")
    OffsetDateTime maxRevokedAt(@Param("revoked") LicenseToken.Status revoked);

    long countByStatus(LicenseToken.Status status);

    /**
     * REVOKED tokens whose {@code expires_at} is still after {@code cutoff} (i.e. not yet expired):
     * the CRL only needs to list jtis that could still verify offline. Expired-but-revoked jtis are
     * pruned from the signed CRL since an offline verifier already rejects them on expiry (P3).
     */
    List<LicenseToken> findByStatusAndExpiresAtAfterOrderByRevokedAtAsc(
            LicenseToken.Status status, OffsetDateTime cutoff);

    List<LicenseToken> findByStatusAndRevokedAtAfterOrderByRevokedAtAsc(LicenseToken.Status status, OffsetDateTime since);

    List<LicenseToken> findByStatusOrderByRevokedAtAsc(LicenseToken.Status status);

    /** Tokens still marked {@code status} whose {@code expires_at} is at/before {@code cutoff} (overdue). */
    List<LicenseToken> findByStatusAndExpiresAtLessThanEqual(LicenseToken.Status status, OffsetDateTime cutoff);

    /**
     * Set-based expiry transition (audit P2): atomically moves every ACTIVE token past its expiry to
     * EXPIRED in one statement. Returns the count transitioned. Run in the same tx as the matching
     * outbox inserts so the transition and its events commit (or roll back) together.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE LicenseToken t SET t.status = :expired "
            + "WHERE t.status = :active AND t.expiresAt <= :cutoff")
    int markExpiredPastDue(@Param("cutoff") OffsetDateTime cutoff,
                           @Param("active") LicenseToken.Status active,
                           @Param("expired") LicenseToken.Status expired);

    /**
     * Marks a single token as warned for the {@code license.expiring} event only if it has not been
     * warned yet ({@code expiring_warned_at IS NULL}). Returns 1 if this call claimed the warning, 0
     * if another sweep already did — the durable dedup that replaces the racy count-the-outbox-rows
     * heuristic (audit P2).
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE LicenseToken t SET t.expiringWarnedAt = :at "
            + "WHERE t.jti = :jti AND t.expiringWarnedAt IS NULL")
    int claimExpiringWarning(@Param("jti") String jti, @Param("at") OffsetDateTime at);

    /**
     * ACTIVE tokens expiring inside a window ({@code after < expires_at <= before}) that have NOT yet
     * been warned ({@code expiring_warned_at IS NULL}). The not-yet-warned filter is the durable
     * dedup for {@code license.expiring} (audit P2).
     */
    @Query("SELECT t FROM LicenseToken t WHERE t.status = :status "
            + "AND t.expiresAt > :after AND t.expiresAt <= :before AND t.expiringWarnedAt IS NULL")
    List<LicenseToken> findExpiringNotYetWarned(@Param("status") LicenseToken.Status status,
                                                @Param("after") OffsetDateTime after,
                                                @Param("before") OffsetDateTime before);
}
