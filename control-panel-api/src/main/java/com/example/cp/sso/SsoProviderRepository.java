package com.example.cp.sso;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface SsoProviderRepository extends JpaRepository<SsoProvider, UUID> {

    List<SsoProvider> findByOrgId(UUID orgId);

    List<SsoProvider> findByEnabledTrue();
}
