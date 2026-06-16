package com.example.licenseverifier;

import com.example.licenseverifier.exceptions.LicenseAudienceMismatchException;
import com.example.licenseverifier.exceptions.LicenseExpiredException;
import com.example.licenseverifier.exceptions.LicenseFileMalformedException;
import com.example.licenseverifier.exceptions.LicenseIssuerMismatchException;
import com.example.licenseverifier.exceptions.LicenseKidUnknownException;
import com.example.licenseverifier.exceptions.LicenseNotYetValidException;
import com.example.licenseverifier.exceptions.LicenseRevokedException;
import com.example.licenseverifier.exceptions.LicenseSignatureInvalidException;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LicenseVerifierTest {

    private static final String AUDIENCE = "docker-app-prod";
    private static final String ISSUER = "https://control-panel.example.com";
    private static final String KID = "key-2026-06-01";

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
    void verifies_raw_jwt_and_extracts_all_claims() throws Exception {
        Instant now = Instant.parse("2026-06-01T10:00:00Z");
        Clock clock = Clock.fixed(now, ZoneOffset.UTC);

        String jwt = signLicense(now, now.plus(Duration.ofDays(365)));

        LicenseVerifier verifier = buildVerifier(clock);
        License license = verifier.verify(jwt);

        assertThat(license.getJti()).isEqualTo("lic_test_001");
        assertThat(license.getIssuer()).isEqualTo(ISSUER);
        assertThat(license.getSubject()).isEqualTo("org_example");
        assertThat(license.getSubscriptionId()).isEqualTo("sub_example_pro");
        assertThat(license.getPlan()).isEqualTo("pro");
        assertThat(license.getAudience()).containsExactly(AUDIENCE);
        assertThat(license.getPermissions()).containsExactlyInAnyOrder("export.pdf", "api.v2", "admin.users.invite");
        assertThat(license.getSeats()).isEqualTo(25);
        assertThat(license.getVersion()).isEqualTo(1);
        assertThat(license.getKid()).isEqualTo(KID);
        assertThat(license.getCustomer()).isNotNull();
        assertThat(license.getCustomer().orgName()).isEqualTo("Example Corp");
        assertThat(license.getCustomer().contactEmail()).isEqualTo("billing@example.com");

        assertThat(license.hasPermission("export.pdf")).isTrue();
        assertThat(license.hasPermission("not-granted")).isFalse();

        assertThat(license.feature("max_users", Integer.class)).isEqualTo(50);
        assertThat(license.feature("max_storage_gb", Integer.class)).isEqualTo(100);
        assertThat(license.feature("ai_assistant", Boolean.class)).isTrue();
        assertThat(license.feature("does_not_exist", String.class)).isNull();

        assertThat(license.status(clock)).isEqualTo(License.Status.ACTIVE);
        assertThat(license.isExpired(clock)).isFalse();
    }

    @Test
    void verifies_jwt_wrapped_in_envelope() throws Exception {
        Instant now = Instant.parse("2026-06-01T10:00:00Z");
        Clock clock = Clock.fixed(now, ZoneOffset.UTC);

        String jwt = signLicense(now, now.plus(Duration.ofDays(365)));
        String envelope = """
                {
                  "license": "%s",
                  "issued_at": "2026-06-01T10:00:00Z",
                  "customer": "Example Corp",
                  "plan": "Pro",
                  "expires_at": "2027-06-01T10:00:00Z",
                  "notes": "Drop this file at /etc/app/license.lic."
                }
                """.formatted(jwt);

        License license = buildVerifier(clock).verify(envelope);
        assertThat(license.getPlan()).isEqualTo("pro");
        assertThat(license.getJti()).isEqualTo("lic_test_001");
    }

    @Test
    void tampered_signature_throws_signature_invalid() throws Exception {
        Instant now = Instant.parse("2026-06-01T10:00:00Z");
        Clock clock = Clock.fixed(now, ZoneOffset.UTC);

        String jwt = signLicense(now, now.plus(Duration.ofDays(365)));
        String[] parts = jwt.split("\\.");
        // Tamper the FIRST signature char: the last base64url char of a 64-byte Ed25519 signature
        // only carries padding bits the decoder ignores, so flipping it would be a no-op.
        char[] sig = parts[2].toCharArray();
        sig[0] = sig[0] == 'A' ? 'B' : 'A';
        String tampered = parts[0] + "." + parts[1] + "." + new String(sig);

        LicenseVerifier verifier = buildVerifier(clock);
        assertThatThrownBy(() -> verifier.verify(tampered))
                .isInstanceOf(LicenseSignatureInvalidException.class);
    }

    @Test
    void tampered_payload_throws_signature_invalid() throws Exception {
        Instant now = Instant.parse("2026-06-01T10:00:00Z");
        Clock clock = Clock.fixed(now, ZoneOffset.UTC);

        String jwt = signLicense(now, now.plus(Duration.ofDays(365)));
        String[] parts = jwt.split("\\.");
        // Flip a single character in the payload
        char[] payload = parts[1].toCharArray();
        payload[5] = payload[5] == 'A' ? 'B' : 'A';
        String tampered = parts[0] + "." + new String(payload) + "." + parts[2];

        LicenseVerifier verifier = buildVerifier(clock);
        assertThatThrownBy(() -> verifier.verify(tampered))
                .isInstanceOf(LicenseSignatureInvalidException.class);
    }

    @Test
    void expired_license_throws_expired_exception() throws Exception {
        Instant signingTime = Instant.parse("2025-01-01T10:00:00Z");
        Instant verifyTime = Instant.parse("2026-06-01T10:00:00Z");
        Clock clock = Clock.fixed(verifyTime, ZoneOffset.UTC);

        // exp = 1 day after signing → well before verification time
        String jwt = signLicense(signingTime, signingTime.plus(Duration.ofDays(1)));

        LicenseVerifier verifier = buildVerifier(clock);
        assertThatThrownBy(() -> verifier.verify(jwt))
                .isInstanceOf(LicenseExpiredException.class);
    }

    @Test
    void not_yet_valid_license_throws() throws Exception {
        Instant now = Instant.parse("2026-06-01T10:00:00Z");
        Instant nbf = now.plus(Duration.ofDays(7));
        Clock clock = Clock.fixed(now, ZoneOffset.UTC);

        String jwt = signLicenseWithNbf(now, now.plus(Duration.ofDays(365)), nbf);

        LicenseVerifier verifier = buildVerifier(clock);
        assertThatThrownBy(() -> verifier.verify(jwt))
                .isInstanceOf(LicenseNotYetValidException.class);
    }

    @Test
    void wrong_audience_throws_audience_mismatch() throws Exception {
        Instant now = Instant.parse("2026-06-01T10:00:00Z");
        Clock clock = Clock.fixed(now, ZoneOffset.UTC);

        String jwt = signLicense(now, now.plus(Duration.ofDays(365)));

        LicenseVerifier verifier = LicenseVerifier.builder()
                .publicKeys(PublicKeyProvider.fromJwks(jwksStream()))
                .audience("some-other-app")
                .clock(clock)
                .build();

        assertThatThrownBy(() -> verifier.verify(jwt))
                .isInstanceOf(LicenseAudienceMismatchException.class);
    }

    @Test
    void wrong_issuer_throws_issuer_mismatch() throws Exception {
        Instant now = Instant.parse("2026-06-01T10:00:00Z");
        Clock clock = Clock.fixed(now, ZoneOffset.UTC);

        String jwt = signLicense(now, now.plus(Duration.ofDays(365)));

        LicenseVerifier verifier = LicenseVerifier.builder()
                .publicKeys(PublicKeyProvider.fromJwks(jwksStream()))
                .audience(AUDIENCE)
                .issuer("https://other-control-panel.example.com")
                .clock(clock)
                .build();

        assertThatThrownBy(() -> verifier.verify(jwt))
                .isInstanceOf(LicenseIssuerMismatchException.class);
    }

    @Test
    void unknown_kid_throws_kid_unknown() throws Exception {
        Instant now = Instant.parse("2026-06-01T10:00:00Z");
        Clock clock = Clock.fixed(now, ZoneOffset.UTC);

        // Sign with a key whose kid isn't in the published JWKS
        OctetKeyPair otherKey = new OctetKeyPairGenerator(Curve.Ed25519)
                .keyID("rotated-out-key")
                .generate();
        String jwt = signLicenseWithKey(now, now.plus(Duration.ofDays(365)), otherKey);

        LicenseVerifier verifier = buildVerifier(clock);
        assertThatThrownBy(() -> verifier.verify(jwt))
                .isInstanceOf(LicenseKidUnknownException.class);
    }

    @Test
    void malformed_envelope_throws_malformed() {
        Clock clock = Clock.fixed(Instant.parse("2026-06-01T10:00:00Z"), ZoneOffset.UTC);
        LicenseVerifier verifier = buildVerifier(clock);
        assertThatThrownBy(() -> verifier.verify("{ this is not json"))
                .isInstanceOf(LicenseFileMalformedException.class);
    }

    @Test
    void empty_content_throws_malformed() {
        Clock clock = Clock.fixed(Instant.parse("2026-06-01T10:00:00Z"), ZoneOffset.UTC);
        LicenseVerifier verifier = buildVerifier(clock);
        assertThatThrownBy(() -> verifier.verify(""))
                .isInstanceOf(LicenseFileMalformedException.class);
    }

    @Test
    void missing_typ_header_throws_malformed() throws Exception {
        Instant now = Instant.parse("2026-06-01T10:00:00Z");
        Clock clock = Clock.fixed(now, ZoneOffset.UTC);

        // A token signed by the legitimate key but with NO typ header must be rejected: an
        // attacker who can produce a same-key token of another type (CRL, session) could otherwise
        // strip the typ and have it accepted as a license.
        String jwt = signLicenseWithTyp(now, now.plus(Duration.ofDays(365)), signingKey, null);

        LicenseVerifier verifier = buildVerifier(clock);
        assertThatThrownBy(() -> verifier.verify(jwt))
                .isInstanceOf(LicenseFileMalformedException.class)
                .hasMessageContaining("typ");
    }

    @Test
    void wrong_typ_header_throws_malformed() throws Exception {
        Instant now = Instant.parse("2026-06-01T10:00:00Z");
        Clock clock = Clock.fixed(now, ZoneOffset.UTC);

        String jwt = signLicenseWithTyp(now, now.plus(Duration.ofDays(365)), signingKey, "crl+jwt");

        LicenseVerifier verifier = buildVerifier(clock);
        assertThatThrownBy(() -> verifier.verify(jwt))
                .isInstanceOf(LicenseFileMalformedException.class)
                .hasMessageContaining("typ");
    }

    @Test
    void verify_allowing_expired_returns_expired_but_valid_license() throws Exception {
        Instant signingTime = Instant.parse("2025-01-01T10:00:00Z");
        Instant exp = signingTime.plus(Duration.ofDays(1)); // long expired
        Instant verifyTime = Instant.parse("2026-06-01T10:00:00Z");
        Clock clock = Clock.fixed(verifyTime, ZoneOffset.UTC);

        String jwt = signLicense(signingTime, exp);
        LicenseVerifier verifier = buildVerifier(clock);

        // Plain verify() still hard-rejects expiry.
        assertThatThrownBy(() -> verifier.verify(jwt))
                .isInstanceOf(LicenseExpiredException.class);

        // verifyAllowingExpired() returns the parsed license carrying the real (past) expiry so a
        // consumer can enter a READ_ONLY grace at startup.
        License license = verifier.verifyAllowingExpired(jwt);
        assertThat(license.getJti()).isEqualTo("lic_test_001");
        assertThat(license.getExpiresAt()).isEqualTo(exp);
        assertThat(license.isExpired(clock)).isTrue();
    }

    @Test
    void verify_allowing_expired_still_enforces_signature_and_audience() throws Exception {
        Instant signingTime = Instant.parse("2025-01-01T10:00:00Z");
        Instant exp = signingTime.plus(Duration.ofDays(1));
        Instant verifyTime = Instant.parse("2026-06-01T10:00:00Z");
        Clock clock = Clock.fixed(verifyTime, ZoneOffset.UTC);

        // Tampered signature must still be rejected even when expiry is tolerated.
        String jwt = signLicense(signingTime, exp);
        String[] parts = jwt.split("\\.");
        char[] sig = parts[2].toCharArray();
        sig[0] = sig[0] == 'A' ? 'B' : 'A';
        String tampered = parts[0] + "." + parts[1] + "." + new String(sig);

        LicenseVerifier verifier = buildVerifier(clock);
        assertThatThrownBy(() -> verifier.verifyAllowingExpired(tampered))
                .isInstanceOf(LicenseSignatureInvalidException.class);

        // Wrong audience must still be rejected.
        LicenseVerifier wrongAud = LicenseVerifier.builder()
                .publicKeys(PublicKeyProvider.fromJwks(jwksStream()))
                .audience("some-other-app")
                .clock(clock)
                .build();
        assertThatThrownBy(() -> wrongAud.verifyAllowingExpired(jwt))
                .isInstanceOf(LicenseAudienceMismatchException.class);
    }

    @Test
    void verify_allowing_expired_still_rejects_missing_exp() throws Exception {
        Instant now = Instant.parse("2026-06-01T10:00:00Z");
        Clock clock = Clock.fixed(now, ZoneOffset.UTC);

        String jwt = signLicenseWithoutExp(now);
        LicenseVerifier verifier = buildVerifier(clock);

        assertThatThrownBy(() -> verifier.verifyAllowingExpired(jwt))
                .isInstanceOf(LicenseExpiredException.class)
                .hasMessageContaining("mandatory exp");
    }

    @Test
    void clock_skew_tolerates_slight_expiration() throws Exception {
        Instant signingTime = Instant.parse("2026-06-01T10:00:00Z");
        Instant exp = signingTime.plus(Duration.ofMinutes(10));
        Instant verifyTime = exp.plus(Duration.ofMinutes(2));  // 2 minutes after exp
        Clock clock = Clock.fixed(verifyTime, ZoneOffset.UTC);

        String jwt = signLicense(signingTime, exp);

        LicenseVerifier verifier = LicenseVerifier.builder()
                .publicKeys(PublicKeyProvider.fromJwks(jwksStream()))
                .audience(AUDIENCE)
                .clockSkew(Duration.ofMinutes(5))
                .clock(clock)
                .build();

        License license = verifier.verify(jwt);
        assertThat(license.getJti()).isEqualTo("lic_test_001");
    }

    private LicenseVerifier buildVerifier(Clock clock) {
        return LicenseVerifier.builder()
                .publicKeys(PublicKeyProvider.fromJwks(jwksStream()))
                .audience(AUDIENCE)
                .issuer(ISSUER)
                .clockSkew(Duration.ofMinutes(5))
                .clock(clock)
                .build();
    }

    private ByteArrayInputStream jwksStream() {
        return new ByteArrayInputStream(jwksJson.getBytes(StandardCharsets.UTF_8));
    }

    private String signLicense(Instant iat, Instant exp) throws JOSEException {
        return signLicenseWithKey(iat, exp, signingKey);
    }

    private String signLicenseWithNbf(Instant iat, Instant exp, Instant nbf) throws JOSEException {
        return signLicenseInternal(iat, exp, nbf, signingKey, "license+jwt", true);
    }

    private String signLicenseWithKey(Instant iat, Instant exp, OctetKeyPair key) throws JOSEException {
        return signLicenseInternal(iat, exp, iat, key, "license+jwt", true);
    }

    private String signLicenseWithTyp(Instant iat, Instant exp, OctetKeyPair key, String typ)
            throws JOSEException {
        return signLicenseInternal(iat, exp, iat, key, typ, true);
    }

    private String signLicenseWithoutExp(Instant iat) throws JOSEException {
        return signLicenseInternal(iat, null, iat, signingKey, "license+jwt", false);
    }

    private String signLicenseInternal(Instant iat, Instant exp, Instant nbf, OctetKeyPair key,
                                       String typ, boolean includeExp) throws JOSEException {
        Map<String, Object> features = new LinkedHashMap<>();
        features.put("max_users", 50);
        features.put("max_storage_gb", 100);
        features.put("ai_assistant", true);

        Map<String, Object> customer = new LinkedHashMap<>();
        customer.put("org_name", "Example Corp");
        customer.put("contact_email", "billing@example.com");

        JWTClaimsSet.Builder claimsBuilder = new JWTClaimsSet.Builder()
                .issuer(ISSUER)
                .audience(AUDIENCE)
                .subject("org_example")
                .jwtID("lic_test_001")
                .issueTime(Date.from(iat))
                .notBeforeTime(Date.from(nbf))
                .claim("subscription_id", "sub_example_pro")
                .claim("plan", "pro")
                .claim("permissions", List.of("export.pdf", "api.v2", "admin.users.invite"))
                .claim("features", features)
                .claim("seats", 25)
                .claim("customer", customer)
                .claim("version", 1);
        if (includeExp && exp != null) {
            claimsBuilder.expirationTime(Date.from(exp));
        }

        JWSHeader.Builder headerBuilder = new JWSHeader.Builder(JWSAlgorithm.EdDSA)
                .keyID(key.getKeyID());
        if (typ != null) {
            headerBuilder.type(new JOSEObjectType(typ));
        }

        SignedJWT jwt = new SignedJWT(headerBuilder.build(), claimsBuilder.build());
        JWSSigner signer = new Ed25519Signer(key);
        jwt.sign(signer);
        return jwt.serialize();
    }
}
