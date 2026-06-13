package com.example.sample;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.licenseverifier.PublicKeyProvider;
import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.OctetKeyPair;
import com.nimbusds.jose.jwk.gen.OctetKeyPairGenerator;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;

/**
 * Verifies the actuator lockdown added for the audit P3 finding "sample app exposes the custom
 * license actuator endpoint + health unauthenticated": {@code /actuator/license} now requires HTTP
 * Basic auth, while the public {@code /api/**} endpoints and the {@code /actuator/health} probe
 * stay open.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {
        "app.license.strict=false",
        "app.license.path=/does/not/exist.lic",
        "app.license.crl-url=",
        "spring.security.user.name=actuator",
        "spring.security.user.password=secret"
})
class ActuatorSecurityIT {

    @LocalServerPort
    int port;

    private final TestRestTemplate anonymous = new TestRestTemplate();

    private String url(String path) {
        return "http://127.0.0.1:" + port + path;
    }

    @Test
    void license_endpoint_requires_authentication() {
        ResponseEntity<String> resp = anonymous.getForEntity(url("/actuator/license"), String.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void license_endpoint_accessible_with_basic_auth() {
        ResponseEntity<String> resp = anonymous
                .withBasicAuth("actuator", "secret")
                .getForEntity(url("/actuator/license"), String.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).contains("status");
    }

    @Test
    void license_endpoint_rejects_wrong_password() {
        ResponseEntity<String> resp = anonymous
                .withBasicAuth("actuator", "wrong")
                .getForEntity(url("/actuator/license"), String.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void health_probe_stays_open() {
        ResponseEntity<String> resp = anonymous.getForEntity(url("/actuator/health"), String.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void public_api_endpoints_stay_open() {
        ResponseEntity<String> resp = anonymous.getForEntity(url("/api/license/status"), String.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        // No license loaded in this test -> NOT_LOADED, but the endpoint is reachable anonymously.
        assertThat(resp.getBody()).contains("NOT_LOADED");
    }

    /**
     * The bundled {@code classpath:/jwks.json} ships empty (keys: []), which the verifier rejects.
     * Supply a real Ed25519 JWKS so the auto-config's key provider can be built; the security
     * assertions here do not depend on a license actually being present.
     */
    @TestConfiguration
    static class TestKeys {

        @Bean
        PublicKeyProvider licenseKeyProvider() throws Exception {
            OctetKeyPair key = new OctetKeyPairGenerator(Curve.Ed25519).keyID("test-key").generate();
            String jwks = new JWKSet(List.of(key.toPublicJWK())).toString(true);
            return PublicKeyProvider.fromJwks(
                    new ByteArrayInputStream(jwks.getBytes(StandardCharsets.UTF_8)));
        }
    }
}
