package com.example.cp.sso;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface SsoIdentityRepository extends JpaRepository<SsoIdentity, UUID> {

    Optional<SsoIdentity> findByProviderIdAndSubject(UUID providerId, String subject);
}
