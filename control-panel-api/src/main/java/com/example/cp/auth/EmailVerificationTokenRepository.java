package com.example.cp.auth;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface EmailVerificationTokenRepository extends JpaRepository<EmailVerificationToken, UUID> {

    /** Looks up a verification token by its SHA-256 hash (the raw token is never stored). */
    Optional<EmailVerificationToken> findByTokenHash(String tokenHash);

    /**
     * Deletes the user's still-active (unused) verification tokens. Called before issuing a fresh one
     * so at most one verification link is ever live per user — bounds the valid-token pool and DB
     * growth under repeated resends. Must run within the caller's transaction.
     */
    @Modifying
    @Query("delete from EmailVerificationToken t where t.userId = :userId and t.usedAt is null")
    int deleteActiveByUserId(@Param("userId") UUID userId);
}
