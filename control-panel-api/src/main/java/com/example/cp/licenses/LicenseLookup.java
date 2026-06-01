package com.example.cp.licenses;

import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Bean exposed as "licenseLookup" for SpEL access from @PreAuthorize.
 */
@Component("licenseLookup")
public class LicenseLookup {

    private final LicenseTokenRepository repo;

    public LicenseLookup(LicenseTokenRepository repo) {
        this.repo = repo;
    }

    public UUID subscriptionId(String jti) {
        return repo.findByJti(jti).map(LicenseToken::getSubscriptionId).orElse(null);
    }
}
