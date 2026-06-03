package com.example.cp.mfa;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface UserMfaRepository extends JpaRepository<UserMfa, UUID> {

    Optional<UserMfa> findByUserId(UUID userId);

    /** True only when the user has a row AND it is confirmed/enabled. */
    boolean existsByUserIdAndEnabledTrue(UUID userId);

    void deleteByUserId(UUID userId);
}
