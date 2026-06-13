package com.example.cp.licenses;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface LicenseArtifactRepository extends JpaRepository<LicenseArtifact, String> {

    Optional<LicenseArtifact> findByJti(String jti);
}
