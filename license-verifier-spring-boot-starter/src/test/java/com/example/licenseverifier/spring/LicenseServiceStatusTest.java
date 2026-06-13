package com.example.licenseverifier.spring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.licenseverifier.LicenseVerifier;
import com.example.licenseverifier.PublicKeyProvider;
import com.example.licenseverifier.RevocationChecker;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Exercises {@link LicenseService} load/status transitions against a real
 * {@link LicenseVerifier}: ACTIVE, NOT_LOADED, strict-abort, the READ_ONLY-on-expiry grace at
 * startup, and the REVOKED-wins status precedence over READ_ONLY/EXPIRED.
 */
class LicenseServiceStatusTest {

    private TestCrypto crypto;
    private LicenseProperties props;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        crypto = new TestCrypto();
        props = new LicenseProperties();
        props.setAudience(TestCrypto.AUDIENCE);
        props.setIssuer(TestCrypto.ISSUER);
        props.setClockSkew(Duration.ofMinutes(5));
        props.setReadOnlyOnExpiry(true);
        props.setStrict(false);
    }

    private LicenseVerifier verifier() {
        PublicKeyProvider keys =
                PublicKeyProvider.fromJwks(
                        new ByteArrayInputStream(crypto.jwksJson().getBytes(StandardCharsets.UTF_8)));
        LicenseVerifier.Builder b = LicenseVerifier.builder()
                .publicKeys(keys)
                .audience(TestCrypto.AUDIENCE)
                .issuer(TestCrypto.ISSUER)
                .clockSkew(Duration.ofMinutes(5));
        return b.build();
    }

    private Path writeLicense(String content) throws Exception {
        Path p = tempDir.resolve("license.lic");
        Files.writeString(p, content);
        props.setPath(p.toString());
        return p;
    }

    @Test
    void active_when_valid_license_loaded() throws Exception {
        Instant now = Instant.now();
        writeLicense(crypto.signLicense("lic_ok", now, now.plus(Duration.ofDays(30))));

        LicenseService svc = new LicenseService(verifier(), props, RevocationChecker.none());
        svc.load();

        assertThat(svc.status()).isEqualTo(LicenseService.Status.ACTIVE);
        assertThat(svc.current().jti()).isEqualTo("lic_ok");
    }

    @Test
    void not_loaded_when_file_missing_and_not_strict() {
        props.setPath(tempDir.resolve("does-not-exist.lic").toString());
        props.setStrict(false);

        LicenseService svc = new LicenseService(verifier(), props, RevocationChecker.none());
        svc.load();

        assertThat(svc.status()).isEqualTo(LicenseService.Status.NOT_LOADED);
        assertThat(svc.currentOptional()).isEmpty();
    }

    @Test
    void strict_load_aborts_when_file_missing() {
        props.setPath(tempDir.resolve("does-not-exist.lic").toString());
        props.setStrict(true);

        LicenseService svc = new LicenseService(verifier(), props, RevocationChecker.none());
        assertThatThrownBy(svc::load).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void expired_license_loads_as_read_only_at_startup_when_read_only_on_expiry() throws Exception {
        // License expired long ago: a container restart must still load it and enter READ_ONLY,
        // not fail closed.
        Instant past = Instant.now().minus(Duration.ofDays(400));
        writeLicense(crypto.signLicense("lic_expired", past, past.plus(Duration.ofDays(1))));
        props.setReadOnlyOnExpiry(true);

        LicenseService svc = new LicenseService(verifier(), props, RevocationChecker.none());
        svc.load();

        assertThat(svc.currentOptional()).isPresent();
        assertThat(svc.status()).isEqualTo(LicenseService.Status.READ_ONLY);
        assertThat(svc.isReadOnly()).isTrue();
    }

    @Test
    void expired_license_not_loaded_when_read_only_on_expiry_disabled_and_not_strict()
            throws Exception {
        Instant past = Instant.now().minus(Duration.ofDays(400));
        writeLicense(crypto.signLicense("lic_expired", past, past.plus(Duration.ofDays(1))));
        props.setReadOnlyOnExpiry(false);
        props.setStrict(false);

        LicenseService svc = new LicenseService(verifier(), props, RevocationChecker.none());
        svc.load();

        // Hard-rejected at load (verify() throws expired) -> nothing cached -> NOT_LOADED.
        assertThat(svc.status()).isEqualTo(LicenseService.Status.NOT_LOADED);
    }

    @Test
    void revoked_wins_over_active() throws Exception {
        Instant now = Instant.now();
        writeLicense(crypto.signLicense("lic_revoked_after_load", now, now.plus(Duration.ofDays(30))));

        // The CRL only lists this jti AFTER the license was loaded (verify() already rejects a jti
        // revoked before load; this is the post-load precedence path). The flag flips once loading
        // is done so the license loads ACTIVE, then status() sees it as REVOKED.
        java.util.concurrent.atomic.AtomicBoolean revokedNow =
                new java.util.concurrent.atomic.AtomicBoolean(false);
        RevocationChecker revoking = new RevocationChecker() {
            @Override
            public boolean isRevoked(String jti) {
                return revokedNow.get() && "lic_revoked_after_load".equals(jti);
            }
        };

        LicenseService svc = new LicenseService(verifier(), props, revoking);
        svc.load();
        assertThat(svc.status()).isEqualTo(LicenseService.Status.ACTIVE);

        // Now the control panel revokes it; the CRL refresh picks it up.
        revokedNow.set(true);
        assertThat(svc.status()).isEqualTo(LicenseService.Status.REVOKED);
    }

    @Test
    void revoked_wins_over_read_only_when_checker_goes_non_operational_after_load() throws Exception {
        // An expired-but-loaded license would normally report READ_ONLY. If the CRL checker later
        // goes non-operational (stale/unreachable), fail-closed REVOKED must take precedence over
        // READ_ONLY. The checker is operational at load (so the license loads) then flips.
        Instant past = Instant.now().minus(Duration.ofDays(400));
        writeLicense(crypto.signLicense("lic_expired", past, past.plus(Duration.ofDays(1))));
        props.setReadOnlyOnExpiry(true);

        java.util.concurrent.atomic.AtomicBoolean operational =
                new java.util.concurrent.atomic.AtomicBoolean(true);
        RevocationChecker checker = new RevocationChecker() {
            @Override
            public boolean isRevoked(String jti) {
                return false;
            }

            @Override
            public boolean isOperational() {
                return operational.get();
            }
        };

        LicenseService svc = new LicenseService(verifier(), props, checker);
        svc.load();
        assertThat(svc.status()).isEqualTo(LicenseService.Status.READ_ONLY);

        // CRL goes stale / unreachable -> fail closed -> REVOKED wins over READ_ONLY.
        operational.set(false);
        assertThat(svc.status()).isEqualTo(LicenseService.Status.REVOKED);
    }
}
