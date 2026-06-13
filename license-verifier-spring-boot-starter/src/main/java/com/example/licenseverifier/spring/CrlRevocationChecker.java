package com.example.licenseverifier.spring;

import com.example.licenseverifier.CrlVerifier;
import com.example.licenseverifier.RevocationChecker;
import com.example.licenseverifier.RevocationList;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * CRL-backed {@link RevocationChecker}. Fetches the signed CRL from
 * {@code app.license.crl-url} on a schedule, verifies it via {@link CrlVerifier}, and caches the
 * resulting {@link RevocationList}.
 *
 * <p>Fails CLOSED: when no CRL has been successfully loaded, or the cached CRL is stale beyond
 * {@code app.license.crl-max-stale}, {@link #isOperational()} returns {@code false} and
 * {@link #isRevoked(String)} returns {@code true} for every jti so all guarded calls are denied.
 *
 * <p>This class deliberately touches only verifier-lib types ({@link CrlVerifier},
 * {@link RevocationList}); all Nimbus JWS parsing lives inside {@link CrlVerifier} in the
 * license-verifier module.
 */
public class CrlRevocationChecker implements RevocationChecker {

    private static final Logger log = LoggerFactory.getLogger(CrlRevocationChecker.class);

    private final CrlVerifier crlVerifier;
    private final LicenseProperties properties;
    private final HttpClient httpClient;
    private final AtomicReference<RevocationList> current = new AtomicReference<>();

    public CrlRevocationChecker(CrlVerifier crlVerifier, LicenseProperties properties) {
        this.crlVerifier = crlVerifier;
        this.properties = properties;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    /**
     * Initial load performed once at startup. Calls {@link #refresh()}; when it fails and
     * {@code crl-fail-closed=true}, the cache is left empty so the checker is non-operational
     * (deny all). The failure is rethrown so the auto-configuration can fail startup fast when
     * fail-closed is desired.
     */
    public void load() {
        try {
            refresh();
        } catch (RuntimeException ex) {
            if (properties.isCrlFailClosed()) {
                log.error(
                        "Initial CRL fetch from {} failed; failing closed (all guarded calls denied "
                                + "until a valid CRL is fetched).",
                        properties.getCrlUrl(),
                        ex);
                throw ex;
            }
            log.error(
                    "Initial CRL fetch from {} failed; continuing because app.license.crl-fail-closed=false.",
                    properties.getCrlUrl(),
                    ex);
        }
    }

    @Scheduled(
            fixedDelayString = "${app.license.crl-refresh-interval:PT15M}",
            initialDelayString = "${app.license.crl-refresh-interval:PT15M}")
    public void scheduledRefresh() {
        try {
            refresh();
        } catch (RuntimeException ex) {
            log.warn(
                    "Scheduled CRL refresh from {} failed; keeping previously cached CRL.",
                    properties.getCrlUrl(),
                    ex);
        }
    }

    /**
     * Fetch the CRL over HTTP and verify it. On success, atomically swaps in the new
     * {@link RevocationList}. On failure: if no CRL has ever been loaded the exception is
     * rethrown (initial-failure signal); otherwise the previous list is kept.
     */
    public void refresh() {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(properties.getCrlUrl()))
                    .timeout(Duration.ofSeconds(15))
                    .GET()
                    .build();
            HttpResponse<String> response =
                    httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalStateException(
                        "CRL endpoint returned HTTP " + response.statusCode());
            }
            RevocationList rl = crlVerifier.verify(response.body());
            RevocationList previous = current.get();
            if (isRollback(previous, rl)) {
                // Monotonicity guard: a validly-signed but OLDER CRL must not replace a newer
                // cached one. A MITM / caching proxy / stale mirror could otherwise replay a
                // previously-signed CRL that omits a recently-revoked jti, silently suppressing
                // that revocation until the cached list goes stale. Keep the newer cached CRL.
                log.warn(
                        "Rejected CRL from {} as a rollback: fetched issuedAt={} is older than "
                                + "cached issuedAt={}; keeping the newer cached CRL.",
                        properties.getCrlUrl(),
                        rl.issuedAt(),
                        previous.issuedAt());
                return;
            }
            current.set(rl);
            log.debug(
                    "Refreshed CRL from {}: issuer={}, issuedAt={}, nextUpdate={}, revokedCount={}",
                    properties.getCrlUrl(),
                    rl.issuer(),
                    rl.issuedAt(),
                    rl.nextUpdate(),
                    rl.revokedJtis().size());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            if (current.get() == null) {
                throw new IllegalStateException("CRL fetch interrupted", e);
            }
            log.warn("CRL refresh interrupted; keeping previous CRL.");
        } catch (RuntimeException e) {
            if (current.get() == null) {
                throw e;
            }
            log.warn(
                    "CRL refresh from {} failed; keeping previous CRL.",
                    properties.getCrlUrl(),
                    e);
        } catch (Exception e) {
            if (current.get() == null) {
                throw new IllegalStateException(
                        "Initial CRL fetch from " + properties.getCrlUrl() + " failed", e);
            }
            log.warn(
                    "CRL refresh from {} failed; keeping previous CRL.",
                    properties.getCrlUrl(),
                    e);
        }
    }

    /**
     * @return {@code true} if {@code fetched} is a rollback relative to {@code cached}, i.e. both
     *         carry an {@code issuedAt} and the fetched one is strictly older. A fetched CRL with
     *         no {@code issuedAt} cannot be proven newer, so it is also treated as a rollback when
     *         a cached list with a known {@code issuedAt} exists.
     */
    private static boolean isRollback(RevocationList cached, RevocationList fetched) {
        if (cached == null || cached.issuedAt() == null) {
            return false;
        }
        if (fetched.issuedAt() == null) {
            return true;
        }
        return fetched.issuedAt().isBefore(cached.issuedAt());
    }

    @Override
    public boolean isRevoked(String jti) {
        RevocationList rl = current.get();
        if (rl == null) {
            return true; // never loaded -> fail closed
        }
        if (rl.isStale(Instant.now(), properties.getCrlMaxStale())) {
            return true; // stale -> fail closed
        }
        return rl.isRevoked(jti);
    }

    @Override
    public boolean isOperational() {
        RevocationList rl = current.get();
        return rl != null && !rl.isStale(Instant.now(), properties.getCrlMaxStale());
    }
}
