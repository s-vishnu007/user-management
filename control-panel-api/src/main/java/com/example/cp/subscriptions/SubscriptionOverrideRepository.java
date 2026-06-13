package com.example.cp.subscriptions;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface SubscriptionOverrideRepository extends JpaRepository<SubscriptionOverride, UUID> {

    List<SubscriptionOverride> findBySubscriptionId(UUID subscriptionId);

    /** Deterministic ordering for entitlement resolution and listing. */
    List<SubscriptionOverride> findBySubscriptionIdOrderByTypeAscKeyAsc(UUID subscriptionId);

    Optional<SubscriptionOverride> findBySubscriptionIdAndTypeAndKey(
            UUID subscriptionId, SubscriptionOverride.Type type, String key);

    void deleteBySubscriptionId(UUID subscriptionId);
}
