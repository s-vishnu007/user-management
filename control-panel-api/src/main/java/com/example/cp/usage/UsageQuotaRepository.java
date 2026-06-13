package com.example.cp.usage;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

// NOTE: findForUpdate still returns Optional (single (sub,feature,period) row); the list-returning
// findBySubscriptionIdAndFeatureKey above intentionally drops the single-result constraint.

@Repository
public interface UsageQuotaRepository extends JpaRepository<UsageQuota, UsageQuota.PK> {

    List<UsageQuota> findBySubscriptionId(UUID subscriptionId);

    /**
     * One row accumulates PER period (month) for a (subscription, feature) pair, so this MUST return
     * a list: the old single-result {@code Optional} flavour threw
     * {@code IncorrectResultSizeDataAccessException} -> 500 the moment a second usage period existed.
     */
    List<UsageQuota> findBySubscriptionIdAndFeatureKey(UUID subscriptionId, String featureKey);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            SELECT q FROM UsageQuota q
            WHERE q.subscriptionId = :s AND q.featureKey = :f AND q.periodStart = :p
            """)
    Optional<UsageQuota> findForUpdate(@Param("s") UUID subscriptionId,
                                       @Param("f") String featureKey,
                                       @Param("p") OffsetDateTime periodStart);
}
