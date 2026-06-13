package com.example.licenseverifier.spring;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.licenseverifier.PublicKeyProvider;
import com.example.licenseverifier.RevocationChecker;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Boots the real {@link LicenseVerifierAutoConfiguration} via {@link ApplicationContextRunner}
 * against a stub CRL endpoint, asserting the fail-closed wiring end-to-end (P1-13) and the
 * revocation-off-by-default warning / strict-requires-CRL behaviour (P2).
 */
class LicenseVerifierAutoConfigurationTest {

    private TestCrypto crypto;
    private StubCrlServer server;

    @TempDir
    Path tempDir;

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(LicenseVerifierAutoConfiguration.class))
            .withUserConfiguration(TestKeysConfig.class);

    @BeforeEach
    void setUp() {
        crypto = new TestCrypto();
        TestKeysConfig.JWKS = crypto.jwksJson();
        server = new StubCrlServer();
    }

    @AfterEach
    void tearDown() {
        server.close();
    }

    private Path writeLicense(String content) throws Exception {
        Path p = tempDir.resolve("license.lic");
        Files.writeString(p, content);
        return p;
    }

    @Test
    void wires_crl_backed_checker_and_active_license() throws Exception {
        Instant now = Instant.now();
        Path lic = writeLicense(crypto.signLicense("lic_ok", now, now.plus(Duration.ofDays(30))));
        server.serve(crypto.signCrl(now, now.plus(Duration.ofHours(1)), List.of("lic_other")));

        runner.withPropertyValues(
                        "app.license.audience=" + TestCrypto.AUDIENCE,
                        "app.license.issuer=" + TestCrypto.ISSUER,
                        "app.license.path=" + lic,
                        "app.license.strict=true",
                        "app.license.crl-url=" + server.url())
                .run(ctx -> {
                    assertThat(ctx).hasNotFailed();
                    assertThat(ctx).hasSingleBean(LicenseService.class);
                    RevocationChecker checker = ctx.getBean(RevocationChecker.class);
                    assertThat(checker).isInstanceOf(CrlRevocationChecker.class);
                    LicenseService svc = ctx.getBean(LicenseService.class);
                    assertThat(svc.status()).isEqualTo(LicenseService.Status.ACTIVE);
                });
    }

    @Test
    void revoked_license_in_crl_is_rejected_at_verify_and_service_not_loaded() throws Exception {
        Instant now = Instant.now();
        // The loaded license's jti is on the CRL -> verify() throws at load -> NOT_LOADED.
        Path lic = writeLicense(crypto.signLicense("lic_revoked", now, now.plus(Duration.ofDays(30))));
        server.serve(crypto.signCrl(now, now.plus(Duration.ofHours(1)), List.of("lic_revoked")));

        runner.withPropertyValues(
                        "app.license.audience=" + TestCrypto.AUDIENCE,
                        "app.license.issuer=" + TestCrypto.ISSUER,
                        "app.license.path=" + lic,
                        "app.license.strict=false",
                        "app.license.crl-url=" + server.url())
                .run(ctx -> {
                    assertThat(ctx).hasNotFailed();
                    LicenseService svc = ctx.getBean(LicenseService.class);
                    assertThat(svc.status()).isEqualTo(LicenseService.Status.NOT_LOADED);
                });
    }

    @Test
    void crl_never_loaded_denies_all() throws Exception {
        Instant now = Instant.now();
        Path lic = writeLicense(crypto.signLicense("lic_ok", now, now.plus(Duration.ofDays(30))));
        // CRL endpoint returns 503 so no CRL is ever cached. fail-closed=false lets the context
        // still start; the checker is non-operational (deny-all).
        server.serveStatus(503);

        runner.withPropertyValues(
                        "app.license.audience=" + TestCrypto.AUDIENCE,
                        "app.license.issuer=" + TestCrypto.ISSUER,
                        "app.license.path=" + lic,
                        "app.license.strict=false",
                        "app.license.crl-fail-closed=false",
                        "app.license.crl-url=" + server.url())
                .run(ctx -> {
                    assertThat(ctx).hasNotFailed();
                    RevocationChecker checker = ctx.getBean(RevocationChecker.class);
                    assertThat(checker.isOperational()).isFalse();
                    assertThat(checker.isRevoked("lic_ok")).isTrue();
                    // The non-operational checker makes verify() fail closed at load time, so with
                    // strict=false the otherwise-valid license never loads -> NOT_LOADED (deny-all).
                    LicenseService svc = ctx.getBean(LicenseService.class);
                    assertThat(svc.status()).isEqualTo(LicenseService.Status.NOT_LOADED);
                });
    }

    @Test
    void crl_fail_closed_true_aborts_startup_when_initial_fetch_fails() throws Exception {
        Instant now = Instant.now();
        Path lic = writeLicense(crypto.signLicense("lic_ok", now, now.plus(Duration.ofDays(30))));
        server.serveStatus(503);

        runner.withPropertyValues(
                        "app.license.audience=" + TestCrypto.AUDIENCE,
                        "app.license.issuer=" + TestCrypto.ISSUER,
                        "app.license.path=" + lic,
                        "app.license.strict=false",
                        "app.license.crl-fail-closed=true",
                        "app.license.crl-url=" + server.url())
                .run(ctx -> assertThat(ctx).hasFailed());
    }

    @Test
    void blank_crl_with_strict_aborts_startup() throws Exception {
        Instant now = Instant.now();
        Path lic = writeLicense(crypto.signLicense("lic_ok", now, now.plus(Duration.ofDays(30))));

        runner.withPropertyValues(
                        "app.license.audience=" + TestCrypto.AUDIENCE,
                        "app.license.issuer=" + TestCrypto.ISSUER,
                        "app.license.path=" + lic,
                        "app.license.strict=true")
                // no crl-url -> revocation disabled -> refuse to start in strict mode
                .run(ctx -> assertThat(ctx).hasFailed());
    }

    @Test
    void blank_crl_without_strict_wires_none_checker() throws Exception {
        Instant now = Instant.now();
        Path lic = writeLicense(crypto.signLicense("lic_ok", now, now.plus(Duration.ofDays(30))));

        runner.withPropertyValues(
                        "app.license.audience=" + TestCrypto.AUDIENCE,
                        "app.license.issuer=" + TestCrypto.ISSUER,
                        "app.license.path=" + lic,
                        "app.license.strict=false")
                // no crl-url, not strict -> RevocationChecker.none() wired, WARN logged
                .run(ctx -> {
                    assertThat(ctx).hasNotFailed();
                    RevocationChecker checker = ctx.getBean(RevocationChecker.class);
                    assertThat(checker.isOperational()).isTrue();
                    assertThat(checker.isRevoked("anything")).isFalse();
                    LicenseService svc = ctx.getBean(LicenseService.class);
                    assertThat(svc.status()).isEqualTo(LicenseService.Status.ACTIVE);
                });
    }

    /**
     * Supplies a {@link PublicKeyProvider} matching {@link TestCrypto}'s random signing key so the
     * auto-config's {@code @ConditionalOnMissingBean} key provider is overridden (avoids needing a
     * static {@code classpath:/jwks.json} matched to a per-run key).
     */
    @Configuration
    static class TestKeysConfig {

        static volatile String JWKS;

        @Bean
        PublicKeyProvider licenseKeyProvider() {
            return PublicKeyProvider.fromJwks(
                    new ByteArrayInputStream(JWKS.getBytes(StandardCharsets.UTF_8)));
        }
    }
}
