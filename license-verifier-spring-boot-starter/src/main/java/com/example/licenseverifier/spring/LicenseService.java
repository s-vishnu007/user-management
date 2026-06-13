package com.example.licenseverifier.spring;

import com.example.licenseverifier.License;
import com.example.licenseverifier.LicenseVerifier;
import com.example.licenseverifier.RevocationChecker;
import com.example.licenseverifier.exceptions.LicenseException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * In-memory holder of the active {@link License}. Loads from disk on startup
 * and re-reads on the configured refresh interval. All permission checks go
 * through this service.
 */
public class LicenseService {

    private static final Logger log = LoggerFactory.getLogger(LicenseService.class);

    public enum Status {
        ACTIVE,
        EXPIRED,
        NOT_LOADED,
        READ_ONLY,
        REVOKED
    }

    private final LicenseVerifier verifier;
    private final LicenseProperties properties;
    private final RevocationChecker revocationChecker;
    private final AtomicReference<License> current = new AtomicReference<>();

    public LicenseService(
            LicenseVerifier verifier,
            LicenseProperties properties,
            RevocationChecker revocationChecker) {
        this.verifier = verifier;
        this.properties = properties;
        this.revocationChecker =
                revocationChecker != null ? revocationChecker : RevocationChecker.none();
    }

    /**
     * Initial load. Throws when {@code strict=true} and the license cannot be
     * read or verified; otherwise logs the failure and leaves the service in
     * {@link Status#NOT_LOADED}.
     */
    public void load() {
        try {
            License license = readAndVerify();
            current.set(license);
            log.info(
                    "License loaded: plan={}, expiresAt={}, permissions={}",
                    safePlan(license),
                    safeExpiresAt(license),
                    safePermissionCount(license));
        } catch (Exception ex) {
            if (properties.isStrict()) {
                throw new IllegalStateException(
                        "License could not be loaded from " + properties.getPath()
                                + " and app.license.strict=true",
                        ex);
            }
            log.error(
                    "License could not be loaded from {}. Continuing with NOT_LOADED status "
                            + "because app.license.strict=false. All permission checks will fail.",
                    properties.getPath(),
                    ex);
        }
    }

    /**
     * Re-read the license file (and, if configured, refresh JWKS). If the new
     * file fails to verify, the previously cached License is kept.
     */
    @Scheduled(
            fixedDelayString = "${app.license.refresh-interval:PT24H}",
            initialDelayString = "${app.license.refresh-interval:PT24H}")
    public void reload() {
        try {
            License refreshed = readAndVerify();
            current.set(refreshed);
            log.info("License reloaded: expiresAt={}", safeExpiresAt(refreshed));
        } catch (Exception ex) {
            log.error(
                    "License reload from {} failed; keeping previously cached license.",
                    properties.getPath(),
                    ex);
        }
    }

    private License readAndVerify() throws Exception {
        Path licPath = Path.of(properties.getPath());
        if (!Files.exists(licPath)) {
            throw new LicenseException("License file not found at " + licPath);
        }
        String contents = Files.readString(licPath);
        // When read-only-on-expiry is enabled, tolerate a past exp at load time so a container
        // restart after expiry can establish the documented READ_ONLY grace rather than failing
        // closed (strict=true would otherwise refuse to start, strict=false would sit in
        // NOT_LOADED and deny everything). Signature, audience, issuer, nbf and revocation are
        // still fully enforced by verifyAllowingExpired; status() then maps the expired-but-valid
        // license to READ_ONLY. When read-only-on-expiry is off, expiry remains a hard rejection.
        if (properties.isReadOnlyOnExpiry()) {
            return verifier.verifyAllowingExpired(contents);
        }
        return verifier.verify(contents);
    }

    /** Throws if the license is not currently loaded. */
    public License current() {
        License lic = current.get();
        if (lic == null) {
            throw new LicenseException("License is not loaded");
        }
        return lic;
    }

    public Optional<License> currentOptional() {
        return Optional.ofNullable(current.get());
    }

    /** Returns true when the license is loaded but expired and tolerated as read-only. */
    public boolean isReadOnly() {
        return status() == Status.READ_ONLY;
    }

    public Status status() {
        License lic = current.get();
        if (lic == null) {
            return Status.NOT_LOADED;
        }
        // Revocation wins over READ_ONLY/EXPIRED: a license listed on the CRL (or a CRL that has
        // gone stale, fail-closed) denies all access. This covers licenses revoked AFTER they were
        // loaded; verify() already rejects a jti revoked before load. A non-operational checker
        // (stale/never-loaded CRL) denies everything regardless of jti.
        if (!revocationChecker.isOperational()
                || (lic.jti() != null && revocationChecker.isRevoked(lic.jti()))) {
            return Status.REVOKED;
        }
        if (isExpired(lic)) {
            return properties.isReadOnlyOnExpiry() ? Status.READ_ONLY : Status.EXPIRED;
        }
        return Status.ACTIVE;
    }

    private boolean isExpired(License lic) {
        Instant exp = safeExpiresAt(lic);
        if (exp == null) {
            return false;
        }
        return exp.isBefore(Instant.now().minus(properties.getClockSkew()));
    }

    private static String safePlan(License lic) {
        try {
            return lic.plan();
        } catch (Exception ex) {
            return "?";
        }
    }

    private static Instant safeExpiresAt(License lic) {
        try {
            return lic.expiresAt();
        } catch (Exception ex) {
            return null;
        }
    }

    private static int safePermissionCount(License lic) {
        try {
            return lic.permissions().size();
        } catch (Exception ex) {
            return -1;
        }
    }
}
