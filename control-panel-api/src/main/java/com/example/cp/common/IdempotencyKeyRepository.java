package com.example.cp.common;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface IdempotencyKeyRepository extends JpaRepository<IdempotencyKey, UUID> {

    /** Looks up an existing record by its natural key {@code (idem_key, method, path, actor)}. */
    Optional<IdempotencyKey> findByIdemKeyAndMethodAndPathAndActorUserId(
            String idemKey, String method, String path, String actorUserId);

    /** Deletes expired rows (created before {@code threshold}); used by the optional cleanup sweep. */
    @Modifying
    @Query("DELETE FROM IdempotencyKey k WHERE k.createdAt < :threshold")
    int deleteExpired(@Param("threshold") OffsetDateTime threshold);
}
