package com.example.licenseverifier;

import com.example.licenseverifier.exceptions.LicenseRevokedException;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.JWSSigner;
import com.nimbusds.jose.crypto.Ed25519Signer;
import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.OctetKeyPair;
import com.nimbusds.jose.jwk.gen.OctetKeyPairGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Date;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Focused tests for the revocation path the {@link LicenseVerifier} runs after temporal/aud/iss
 * checks: a license whose jti is in the revoked set must throw {@link LicenseRevokedException},
 * a non-operational (fail-closed) checker rejects every license, and the default
 * {@link RevocationChecker#none()} never revokes.
 */
class LicenseVerifierRevocationTest {

    private static final String AUDIENCE = "docker-app-prod";
    private static final String ISSUER = "https://control-panel.example.com";
    private static final String KID = "key-2026-06-01";
    private static final String JTI = "lic_test_001";

    private static final Instant NOW = Instant.parse("2026-06-01T10:00:00Z");

    private static OctetKeyPair signingKey;
    private static String jwksJson;

    @BeforeAll
    static void setupKeys() throws Exception {
        signingKey = new OctetKeyPairGenerator(Curve.Ed25519)
                .keyID(KID)
                .generate();

        OctetKeyPair publicJwk = signingKey.toPublicJWK();
        JWKSet jwkSet = new JWKSet(List.of(publicJwk));
        jwksJson = jwkSet.toString(true);
    }

    @Test
    void revoked_jti_throws_license_revoked_exception() throws Exception {
        String jwt = signLicense(NOW, NOW.plus(Duration.ofDays(365)));

        // Back the checker with a CRL-derived RevocationList that contains this license's jti.
        RevocationList revocationList = new RevocationList(
                ISSUER, NOW, NOW.plus(Duration.ofHours(1)), Set.of(JTI, "lic_other_002"));
        RevocationChecker checker = new RevocationChecker() {
            @Override
            public boolean isRevoked(String jti) {
                return revocationList.isRevoked(jti);
            }
        };

        LicenseVerifier verifier = builder(clock()).revocationChecker(checker).build();

        assertThatThrownBy(() -> verifier.verify(jwt))
                .isInstanceOf(LicenseRevokedException.class)
                .extracting(e -> ((LicenseRevokedException) e).getJti())
                .isEqualTo(JTI);
    }

    @Test
    void non_revoked_jti_passes_with_a_populated_checker() throws Exception {
        String jwt = signLicense(NOW, NOW.plus(Duration.ofDays(365)));

        RevocationChecker checker = new RevocationChecker() {
            @Override
            public boolean isRevoked(String jti) {
                return Set.of("some_other_jti").contains(jti);
            }
        };

        LicenseVerifier verifier = builder(clock()).revocationChecker(checker).build();
        License license = verifier.verify(jwt);

        assertThat(license.getJti()).isEqualTo(JTI);
    }

    @Test
    void non_operational_checker_fails_closed_and_rejects_valid_license() throws Exception {
        String jwt = signLicense(NOW, NOW.plus(Duration.ofDays(365)));

        // e.g. a CRL-backed checker whose cache is stale: not operational -> reject everything.
        RevocationChecker failClosed = new RevocationChecker() {
            @Override
            public boolean isRevoked(String jti) {
                return false;
            }

            @Override
            public boolean isOperational() {
                return false;
            }
        };

        LicenseVerifier verifier = builder(clock()).revocationChecker(failClosed).build();

        assertThatThrownBy(() -> verifier.verify(jwt))
                .isInstanceOf(LicenseRevokedException.class);
    }

    @Test
    void default_checker_none_never_revokes() throws Exception {
        String jwt = signLicense(NOW, NOW.plus(Duration.ofDays(365)));

        // No .revocationChecker(...) configured -> defaults to RevocationChecker.none().
        LicenseVerifier verifier = builder(clock()).build();

        assertThatCode(() -> verifier.verify(jwt)).doesNotThrowAnyException();
    }

    private Clock clock() {
        return Clock.fixed(NOW, ZoneOffset.UTC);
    }

    private LicenseVerifier.Builder builder(Clock clock) {
        return LicenseVerifier.builder()
                .publicKeys(PublicKeyProvider.fromJwks(jwksStream()))
                .audience(AUDIENCE)
                .issuer(ISSUER)
                .clockSkew(Duration.ofMinutes(5))
                .clock(clock);
    }

    private ByteArrayInputStream jwksStream() {
        return new ByteArrayInputStream(jwksJson.getBytes(StandardCharsets.UTF_8));
    }

    private String signLicense(Instant iat, Instant exp) throws JOSEException {
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .issuer(ISSUER)
                .audience(AUDIENCE)
                .subject("org_example")
                .jwtID(JTI)
                .issueTime(Date.from(iat))
                .notBeforeTime(Date.from(iat))
                .expirationTime(Date.from(exp))
                .claim("subscription_id", "sub_example_pro")
                .claim("plan", "pro")
                .claim("permissions", List.of("export.pdf"))
                .claim("version", 1)
                .build();

        JWSHeader header = new JWSHeader.Builder(JWSAlgorithm.EdDSA)
                .keyID(signingKey.getKeyID())
                .type(new JOSEObjectType("license+jwt"))
                .build();

        SignedJWT jwt = new SignedJWT(header, claims);
        JWSSigner signer = new Ed25519Signer(signingKey);
        jwt.sign(signer);
        return jwt.serialize();
    }
}
