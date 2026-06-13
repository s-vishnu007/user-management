package com.example.licenseverifier.spring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.licenseverifier.CrlVerifier;
import com.example.licenseverifier.PublicKeyProvider;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Drives the real {@link CrlRevocationChecker} (fetch -> {@link CrlVerifier} -> cache) over a live
 * HTTP fetch against {@link StubCrlServer}. These exercise the fail-closed offline-revocation
 * runtime every customer app runs and that the audit (P1-13) found at ~0% coverage.
 */
class CrlRevocationCheckerTest {

    private TestCrypto crypto;
    private StubCrlServer server;
    private LicenseProperties props;

    @BeforeEach
    void setUp() {
        crypto = new TestCrypto();
        server = new StubCrlServer();
        props = new LicenseProperties();
        props.setIssuer(TestCrypto.ISSUER);
        props.setCrlUrl(server.url());
        props.setCrlMaxStale(Duration.ofHours(1));
        props.setCrlFailClosed(true);
    }

    @AfterEach
    void tearDown() {
        server.close();
    }

    private CrlRevocationChecker newChecker() {
        PublicKeyProvider keys =
                PublicKeyProvider.fromJwks(
                        new ByteArrayInputStream(crypto.jwksJson().getBytes(StandardCharsets.UTF_8)));
        CrlVerifier verifier = new CrlVerifier(keys, TestCrypto.ISSUER);
        return new CrlRevocationChecker(verifier, props);
    }

    @Test
    void never_loaded_denies_all() {
        CrlRevocationChecker checker = newChecker();
        // No load() called and nothing served -> no CRL cached.
        assertThat(checker.isOperational()).isFalse();
        assertThat(checker.isRevoked("any-jti")).isTrue();
    }

    @Test
    void loads_crl_and_reports_revoked_and_non_revoked() {
        Instant now = Instant.now();
        server.serve(crypto.signCrl(now, now.plus(Duration.ofHours(1)), List.of("lic_revoked")));

        CrlRevocationChecker checker = newChecker();
        checker.load();

        assertThat(checker.isOperational()).isTrue();
        assertThat(checker.isRevoked("lic_revoked")).isTrue();
        assertThat(checker.isRevoked("lic_active")).isFalse();
    }

    @Test
    void stale_beyond_max_becomes_non_operational_and_denies_all() {
        Instant issued = Instant.now().minus(Duration.ofHours(5));
        // nextUpdate well in the past + maxStale(1h) exceeded -> stale.
        server.serve(crypto.signCrl(issued, issued.plus(Duration.ofMinutes(1)),
                List.of("lic_revoked")));

        CrlRevocationChecker checker = newChecker();
        checker.load();

        assertThat(checker.isOperational()).isFalse();
        // A non-revoked jti is also denied once stale (fail closed).
        assertThat(checker.isRevoked("lic_active")).isTrue();
    }

    @Test
    void refresh_failure_retains_previous_crl() {
        Instant now = Instant.now();
        server.serve(crypto.signCrl(now, now.plus(Duration.ofHours(1)), List.of("lic_revoked")));

        CrlRevocationChecker checker = newChecker();
        checker.load();
        assertThat(checker.isRevoked("lic_revoked")).isTrue();

        // Endpoint now fails; a refresh should keep the previously cached CRL.
        server.serveStatus(500);
        checker.refresh();

        assertThat(checker.isOperational()).isTrue();
        assertThat(checker.isRevoked("lic_revoked")).isTrue();
    }

    @Test
    void rejects_rollback_to_older_crl() {
        Instant now = Instant.now();
        // First serve a NEWER CRL that revokes lic_revoked.
        server.serve(crypto.signCrl(now, now.plus(Duration.ofHours(1)), List.of("lic_revoked")));

        CrlRevocationChecker checker = newChecker();
        checker.load();
        assertThat(checker.isRevoked("lic_revoked")).isTrue();

        // A MITM/mirror replays an OLDER, still validly-signed CRL that omits lic_revoked.
        server.serve(crypto.signCrl(now.minus(Duration.ofHours(2)),
                now.plus(Duration.ofHours(1)), List.of()));
        checker.refresh();

        // The rollback must be rejected: the newer cached CRL (still revoking lic_revoked) is kept.
        assertThat(checker.isRevoked("lic_revoked")).isTrue();
    }

    @Test
    void accepts_newer_crl_on_refresh() {
        Instant now = Instant.now();
        server.serve(crypto.signCrl(now.minus(Duration.ofMinutes(30)),
                now.plus(Duration.ofHours(1)), List.of("lic_a")));

        CrlRevocationChecker checker = newChecker();
        checker.load();
        assertThat(checker.isRevoked("lic_a")).isTrue();
        assertThat(checker.isRevoked("lic_b")).isFalse();

        // A genuinely newer CRL adds lic_b.
        server.serve(crypto.signCrl(now, now.plus(Duration.ofHours(1)), List.of("lic_a", "lic_b")));
        checker.refresh();

        assertThat(checker.isRevoked("lic_a")).isTrue();
        assertThat(checker.isRevoked("lic_b")).isTrue();
    }

    @Test
    void load_fails_closed_aborts_when_initial_fetch_fails_and_fail_closed_true() {
        server.serveStatus(503);
        props.setCrlFailClosed(true);

        CrlRevocationChecker checker = newChecker();
        // Initial fetch fails; fail-closed -> load() rethrows so the context startup aborts.
        assertThatThrownBy(checker::load).isInstanceOf(RuntimeException.class);
        assertThat(checker.isOperational()).isFalse();
    }

    @Test
    void load_continues_when_initial_fetch_fails_and_fail_closed_false() {
        server.serveStatus(503);
        props.setCrlFailClosed(false);

        CrlRevocationChecker checker = newChecker();
        // Initial fetch fails but fail-closed=false -> load() swallows. Still non-operational
        // (deny-all) because no CRL was cached.
        checker.load();
        assertThat(checker.isOperational()).isFalse();
        assertThat(checker.isRevoked("any")).isTrue();
    }
}
