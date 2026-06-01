package com.example.cp.subscriptions;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface SubscriptionOverrideRepository extends JpaRepository<SubscriptionOverride, UUID> {

    List<SubscriptionOverride> findBySubscriptionId(UUID subscriptionId);

    void deleteBySubscriptionId(UUID subscriptionId);
}
