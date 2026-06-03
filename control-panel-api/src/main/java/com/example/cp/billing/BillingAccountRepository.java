package com.example.cp.billing;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface BillingAccountRepository extends JpaRepository<BillingAccount, UUID> {

    Optional<BillingAccount> findByOrgId(UUID orgId);

    boolean existsByOrgId(UUID orgId);
}
