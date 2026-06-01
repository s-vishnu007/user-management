package com.example.cp.subscriptions;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface SubscriptionRepository extends JpaRepository<Subscription, UUID> {

    List<Subscription> findByOrgId(UUID orgId);

    List<Subscription> findByOrgIdAndStatus(UUID orgId, Subscription.Status status);
}
