package com.example.cp.sso;

import com.example.cp.sso.UrlGuard.SsrfException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.URI;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * Pure, hermetic unit tests for {@link UrlGuard#validate(String)} — the parse + scheme + host + port
 * + DNS-resolution + IP-policy path that performs NO network fetch.
 *
 * <p>Hermetic by construction: every host used here is either a literal IP address (resolved locally by
 * {@link java.net.InetAddress#getAllByName(String)} with no DNS query) or {@code localhost} (resolved from
 * the loopback/hosts file). No real network egress occurs and {@code fetchPinned} is never called.
 *
 * <p>{@link UrlGuard} is built directly via its public {@code (boolean allowHttp, String allowedPortsCsv)}
 * constructor rather than through the Spring context, so these tests need no application context and stay
 * fast. The default ({@code allowHttp=false}) mirrors production; an {@code allowHttp=true} variant covers
 * the scheme-allowed path.
 */
class UrlGuardTest {

    /** Production-default guard: https-only, port 443 (plus the default allow-list). */
    private final UrlGuard guard = new UrlGuard(false, "");

    @Test
    @DisplayName("allows a normal public https URL")
    void allowsPublicHttps() {
        // 8.8.8.8 is a routable public address (literal => no DNS lookup, fully hermetic).
        URI uri = guard.validate("https://8.8.8.8/realms/main/.well-known/openid-configuration");

        assertThat(uri).isNotNull();
        assertThat(uri.getScheme()).isEqualTo("https");
        assertThat(uri.getHost()).isEqualTo("8.8.8.8");
    }

    @Test
    @DisplayName("rejects http when allow-http is false (default)")
    void rejectsHttpWhenNotAllowed() {
        SsrfException ex = catchThrowableOfType(
                () -> guard.validate("http://8.8.8.8/.well-known/openid-configuration"),
                SsrfException.class);

        assertThat(ex).isNotNull();
        // Generic message is safe to surface to the admin; detail stays internal.
        assertThat(ex.publicMessage()).isEqualTo("URL scheme not allowed");
        assertThat(ex.internalDetail()).contains("scheme=http");
    }

    @Test
    @DisplayName("allows http only when allow-http is explicitly enabled")
    void allowsHttpWhenEnabled() {
        UrlGuard httpGuard = new UrlGuard(true, "");

        URI uri = httpGuard.validate("http://8.8.8.8/.well-known/openid-configuration");

        assertThat(uri).isNotNull();
        assertThat(uri.getScheme()).isEqualTo("http");
    }

    @Nested
    @DisplayName("rejects loopback addresses")
    class Loopback {

        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {
                "https://127.0.0.1/x",   // IPv4 loopback
                "https://[::1]/x",       // IPv6 loopback
                "https://localhost/x",   // resolves to loopback (hosts file)
        })
        void rejectsLoopback(String url) {
            assertThatExceptionOfType(SsrfException.class)
                    .isThrownBy(() -> guard.validate(url));
        }

        @Test
        @DisplayName("127.0.0.1 surfaces a generic public message, IP only in internal detail")
        void loopbackMessageIsGeneric() {
            SsrfException ex = catchThrowableOfType(
                    () -> guard.validate("https://127.0.0.1/x"), SsrfException.class);

            assertThat(ex).isNotNull();
            assertThat(ex.publicMessage()).isEqualTo("URL host is not allowed");
            // The resolved address leaks only into the internal (logged) detail.
            assertThat(ex.internalDetail()).contains("127.0.0.1");
            assertThat(ex.publicMessage()).doesNotContain("127.0.0.1");
        }
    }

    @Nested
    @DisplayName("rejects RFC1918 private ranges")
    class PrivateRanges {

        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {
                "https://10.0.0.1/x",      // 10.0.0.0/8
                "https://10.255.255.254/x",
                "https://192.168.0.1/x",   // 192.168.0.0/16
                "https://192.168.1.1/x",
                "https://172.16.0.1/x",    // 172.16.0.0/12 lower bound
                "https://172.31.255.254/x",// 172.16.0.0/12 upper bound
        })
        void rejectsPrivate(String url) {
            SsrfException ex = catchThrowableOfType(
                    () -> guard.validate(url), SsrfException.class);

            assertThat(ex).isNotNull();
            assertThat(ex.publicMessage()).isEqualTo("URL host is not allowed");
        }

        @Test
        @DisplayName("172.x just outside the /12 boundary is NOT treated as private")
        void allowsPublic172OutsideRange() {
            // 172.15.x and 172.32.x are public — confirms the 16..31 second-octet bound is correct.
            assertThat(guard.validate("https://172.15.0.1/x")).isNotNull();
            assertThat(guard.validate("https://172.32.0.1/x")).isNotNull();
        }
    }

    @Nested
    @DisplayName("rejects link-local / cloud-metadata addresses")
    class LinkLocalMetadata {

        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {
                "https://169.254.169.254/latest/meta-data/", // AWS/GCP/Azure cloud metadata
                "https://169.254.0.1/x",                     // 169.254.0.0/16 link-local
        })
        void rejectsLinkLocalAndMetadata(String url) {
            SsrfException ex = catchThrowableOfType(
                    () -> guard.validate(url), SsrfException.class);

            assertThat(ex).isNotNull();
            assertThat(ex.publicMessage()).isEqualTo("URL host is not allowed");
        }

        @Test
        @DisplayName("169.254.169.254 metadata IP never appears in the public message")
        void metadataIpStaysInternal() {
            SsrfException ex = catchThrowableOfType(
                    () -> guard.validate("https://169.254.169.254/latest/meta-data/"),
                    SsrfException.class);

            assertThat(ex).isNotNull();
            assertThat(ex.publicMessage()).doesNotContain("169.254.169.254");
            assertThat(ex.internalDetail()).contains("169.254.169.254");
        }
    }
}
