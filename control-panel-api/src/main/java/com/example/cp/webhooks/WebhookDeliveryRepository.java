package com.example.cp.webhooks;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.UUID;

@Repository
public interface WebhookDeliveryRepository extends JpaRepository<WebhookDelivery, UUID> {

    boolean existsBySubscriptionIdAndEventId(UUID subscriptionId, UUID eventId);

    /**
     * Retention sweep: delete terminal (DELIVERED/FAILED) delivery rows created before {@code cutoff}.
     * PENDING rows are never purged (they are still owed delivery / retry). Called by the scheduler's
     * periodic {@code cleanup()} tick so {@code webhook_deliveries} does not grow unbounded.
     *
     * @return the number of rows removed.
     */
    @Modifying
    @Query("DELETE FROM WebhookDelivery d WHERE d.status <> com.example.cp.webhooks.WebhookDelivery.Status.PENDING "
            + "AND d.createdAt < :cutoff")
    int deleteTerminalOlderThan(@Param("cutoff") OffsetDateTime cutoff);
}
