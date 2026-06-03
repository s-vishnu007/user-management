package com.example.cp.webhooks;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface WebhookSubscriptionRepository extends JpaRepository<WebhookSubscription, UUID> {

    List<WebhookSubscription> findByOrgIdOrderByCreatedAtDesc(UUID orgId);

    /** Scoped lookup so CRUD on a single subscription cannot escape the path org (no cross-tenant IDOR). */
    Optional<WebhookSubscription> findByIdAndOrgId(UUID id, UUID orgId);

    /** Active subscriptions for an org, used by the dispatch fan-out to enqueue per-event deliveries. */
    List<WebhookSubscription> findByOrgIdAndActiveTrue(UUID orgId);
}
