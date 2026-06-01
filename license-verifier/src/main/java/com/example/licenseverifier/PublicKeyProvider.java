package com.example.licenseverifier;

import com.example.licenseverifier.exceptions.LicenseFileMalformedException;
import com.nimbusds.jose.jwk.JWK;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URL;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.PublicKey;
import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

public interface PublicKeyProvider {

    Optional<PublicKey> findByKid(String kid);

    Set<String> knownKids();

    void refresh();

    static PublicKeyProvider fromJwks(InputStream input) {
        return new StaticProvider(JwksParser.parseJwks(input));
    }

    static PublicKeyProvider fromJwksUrl(URL url, Duration refreshInterval) {
        UrlProvider provider = new UrlProvider(url, refreshInterval);
        provider.refresh();
        provider.start();
        return provider;
    }

    /**
     * Export a JWK to a {@link PublicKey} when its type supports it (RSA/EC). Ed25519/X25519
     * {@code OctetKeyPair}s cannot be exported via Nimbus and return {@code null}; those keys are
     * still usable through the {@link JwkProvider} path used by the verifier.
     */
    static PublicKey exportPublicKey(JWK jwk) {
        if (jwk instanceof com.nimbusds.jose.jwk.AsymmetricJWK ajwk) {
            try {
                return ajwk.toPublicKey();
            } catch (Exception e) {
                return null;
            }
        }
        return null;
    }

    final class StaticProvider implements PublicKeyProvider, JwkProvider {

        private final Map<String, JWK> jwks;
        private final Map<String, PublicKey> publicKeys;

        StaticProvider(Map<String, JWK> jwks) {
            Map<String, JWK> copy = new LinkedHashMap<>(jwks);
            if (copy.isEmpty()) {
                throw new LicenseFileMalformedException("JWKS contained no keys");
            }
            // Eagerly export to java.security.PublicKey where the JWK type supports it (RSA/EC).
            // Ed25519 OctetKeyPair cannot be exported to a JCA PublicKey via Nimbus
            // ("Export to java.security.PublicKey not supported"); for those keys the verifier
            // uses the original JWK directly (the JwkProvider path), so a failed/absent export is
            // non-fatal — we retain the JWK and simply omit the PublicKey-map entry.
            Map<String, PublicKey> resolved = new LinkedHashMap<>();
            for (Map.Entry<String, JWK> entry : copy.entrySet()) {
                PublicKey pk = exportPublicKey(entry.getValue());
                if (pk != null) {
                    resolved.put(entry.getKey(), pk);
                }
            }
            this.jwks = Collections.unmodifiableMap(copy);
            this.publicKeys = Collections.unmodifiableMap(resolved);
        }

        @Override
        public Optional<PublicKey> findByKid(String kid) {
            return Optional.ofNullable(publicKeys.get(kid));
        }

        @Override
        public Set<String> knownKids() {
            return publicKeys.keySet();
        }

        @Override
        public void refresh() {
            // no-op
        }

        @Override
        public Optional<JWK> findJwkByKid(String kid) {
            return Optional.ofNullable(jwks.get(kid));
        }
    }

    final class UrlProvider implements PublicKeyProvider, JwkProvider {

        private static final Logger log = LoggerFactory.getLogger(UrlProvider.class);

        private final URL url;
        private final Duration refreshInterval;
        private final HttpClient httpClient;
        private final AtomicReference<Map<String, JWK>> jwks = new AtomicReference<>(Collections.emptyMap());
        private final AtomicReference<Map<String, PublicKey>> publicKeys = new AtomicReference<>(Collections.emptyMap());
        private final ScheduledExecutorService scheduler;

        UrlProvider(URL url, Duration refreshInterval) {
            this.url = url;
            this.refreshInterval = refreshInterval;
            this.httpClient = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(10))
                    .build();
            this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "license-jwks-refresh");
                t.setDaemon(true);
                return t;
            });
        }

        void start() {
            long millis = Math.max(refreshInterval.toMillis(), 1000);
            scheduler.scheduleWithFixedDelay(() -> {
                try {
                    refresh();
                } catch (Exception e) {
                    log.warn("Scheduled JWKS refresh failed; keeping previous keys", e);
                }
            }, millis, millis, TimeUnit.MILLISECONDS);
        }

        @Override
        public Optional<PublicKey> findByKid(String kid) {
            return Optional.ofNullable(publicKeys.get().get(kid));
        }

        @Override
        public Set<String> knownKids() {
            return publicKeys.get().keySet();
        }

        @Override
        public Optional<JWK> findJwkByKid(String kid) {
            return Optional.ofNullable(jwks.get().get(kid));
        }

        @Override
        public void refresh() {
            try {
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(url.toString()))
                        .timeout(Duration.ofSeconds(15))
                        .GET()
                        .build();
                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() < 200 || response.statusCode() >= 300) {
                    throw new IOException("JWKS endpoint returned HTTP " + response.statusCode());
                }
                Map<String, JWK> freshJwks = JwksParser.parseJwks(response.body());
                if (freshJwks.isEmpty()) {
                    log.warn("JWKS refresh returned 0 keys; keeping previous keys");
                    return;
                }
                // Export to PublicKey where supported (RSA/EC); Ed25519 OKPs remain usable via the
                // JWK path, so an empty PublicKey map is fine as long as we have JWKs.
                Map<String, PublicKey> freshKeys = new LinkedHashMap<>();
                for (Map.Entry<String, JWK> entry : freshJwks.entrySet()) {
                    PublicKey pk = exportPublicKey(entry.getValue());
                    if (pk != null) {
                        freshKeys.put(entry.getKey(), pk);
                    }
                }
                jwks.set(Collections.unmodifiableMap(freshJwks));
                publicKeys.set(Collections.unmodifiableMap(freshKeys));
                log.debug("Refreshed {} JWKS keys from {}", freshKeys.size(), url);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("JWKS refresh interrupted; keeping previous keys");
            } catch (Exception e) {
                if (jwks.get().isEmpty()) {
                    throw new LicenseFileMalformedException(
                            "Initial JWKS fetch from " + url + " failed", e);
                }
                log.warn("JWKS refresh from {} failed; keeping previous keys", url, e);
            }
        }
    }

    /**
     * Package-private extension implemented by built-in providers so the verifier can obtain
     * the original JWK (preserving Ed25519 raw encoding) instead of converting via PublicKey.
     */
    interface JwkProvider {
        Optional<JWK> findJwkByKid(String kid);
    }
}
