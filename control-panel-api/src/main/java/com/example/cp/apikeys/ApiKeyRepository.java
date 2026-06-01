package com.example.cp.apikeys;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface ApiKeyRepository extends JpaRepository<ApiKey, UUID> {

    List<ApiKey> findByOrgId(UUID orgId);

    List<ApiKey> findByKeyPrefix(String keyPrefix);

    Optional<ApiKey> findByKeyHash(String keyHash);
}
