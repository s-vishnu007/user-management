package com.example.cp.keys;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface SigningKeyRepository extends JpaRepository<SigningKey, UUID> {

    Optional<SigningKey> findByKid(String kid);

    Optional<SigningKey> findFirstByStatusOrderByCreatedAtDesc(SigningKey.Status status);

    List<SigningKey> findByStatus(SigningKey.Status status);

    List<SigningKey> findByStatusOrRetiredAtAfter(SigningKey.Status status, OffsetDateTime cutoff);
}
