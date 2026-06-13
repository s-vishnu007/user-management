package com.example.cp.common;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.OffsetDateTime;

/**
 * Retention sweep for {@code idempotency_keys}.
 *
 * <p>{@link IdempotencyInterceptor} drops an expired record lazily when a same-key request happens to
 * land after the TTL, but a key that is never retried would otherwise live forever. This scheduled
 * job purges rows older than the configured idempotency TTL ({@code app.idempotency.ttl}, the same
 * window after which a record is no longer replayable), giving the long-orphaned
 * {@link IdempotencyKeyRepository#deleteExpired(OffsetDateTime)} helper a real caller and keeping the
 * table bounded. Runs hourly.</p>
 */
@Component
public class IdempotencyRetentionJob {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyRetentionJob.class);

    private final IdempotencyKeyRepository repository;
    private final Duration ttl;

    public IdempotencyRetentionJob(IdempotencyKeyRepository repository,
                                   IdempotencyConfig.IdempotencyProperties properties) {
        this.repository = repository;
        this.ttl = properties.getTtl();
    }

    @Scheduled(fixedDelayString = "${app.idempotency.purge.fixed-delay:PT1H}",
            initialDelayString = "${app.idempotency.purge.initial-delay:PT5M}")
    @Transactional
    public void purgeExpired() {
        try {
            OffsetDateTime threshold = OffsetDateTime.now().minus(ttl);
            int deleted = repository.deleteExpired(threshold);
            if (deleted > 0) {
                log.info("Idempotency retention sweep purged {} keys created before {}", deleted, threshold);
            }
        } catch (Exception e) {
            log.error("Idempotency retention sweep failed: {}", e.getMessage());
        }
    }
}
