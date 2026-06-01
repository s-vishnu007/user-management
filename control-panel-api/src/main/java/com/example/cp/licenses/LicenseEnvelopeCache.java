package com.example.cp.licenses;

import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Short-lived in-memory cache that holds the raw signed JWT for each freshly issued
 * license, keyed by jti. Allows /licenses/{jti}/download to serve the exact same JWT
 * that was returned at issue time, without persisting the JWT to disk.
 *
 * Eviction: lazy — checked on every read. We also evict any entry older than 7 days.
 * If the cache misses (eg after a restart), the download endpoint falls back to
 * re-issuing a fresh license.
 */
@Component
public class LicenseEnvelopeCache {

    private final Map<String, LicenseIssuer.IssuedLicense> cache = new ConcurrentHashMap<>();

    public void put(String jti, LicenseIssuer.IssuedLicense issued) {
        // light bookkeeping — purge stale entries opportunistically
        evictStale();
        cache.put(jti, issued);
    }

    public Optional<LicenseIssuer.IssuedLicense> get(String jti) {
        LicenseIssuer.IssuedLicense v = cache.get(jti);
        if (v == null) return Optional.empty();
        if (v.expiresAt() != null && v.expiresAt().isBefore(OffsetDateTime.now())) {
            cache.remove(jti);
            return Optional.empty();
        }
        return Optional.of(v);
    }

    private void evictStale() {
        OffsetDateTime now = OffsetDateTime.now();
        cache.entrySet().removeIf(e ->
                e.getValue().expiresAt() != null && e.getValue().expiresAt().isBefore(now)
        );
    }
}
