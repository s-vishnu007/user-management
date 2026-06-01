package com.example.cp.usage;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface UsageQuotaRepository extends JpaRepository<UsageQuota, UsageQuota.PK> {

    List<UsageQuota> findBySubscriptionId(UUID subscriptionId);

    Optional<UsageQuota> findBySubscriptionIdAndFeatureKey(UUID subscriptionId, String featureKey);
}
