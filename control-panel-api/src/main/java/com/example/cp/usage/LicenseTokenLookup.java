package com.example.cp.usage;

import com.example.cp.licenses.LicenseToken;
import com.example.cp.licenses.LicenseTokenRepository;
import org.springframework.stereotype.Component;

import java.util.Optional;

@Component
public class LicenseTokenLookup {

    private final LicenseTokenRepository repo;

    public LicenseTokenLookup(LicenseTokenRepository repo) {
        this.repo = repo;
    }

    public Optional<LicenseTokenView> findByJti(String jti) {
        return repo.findByJti(jti).map(t -> new LicenseTokenView(
                t.getId(), t.getJti(), t.getSubscriptionId(),
                t.getStatus() == null ? null : t.getStatus().name(),
                t.getExpiresAt(), t.getRevokedAt()));
    }
}
