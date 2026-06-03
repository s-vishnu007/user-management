package com.example.licenseverifier;

import com.example.licenseverifier.exceptions.LicenseFileMalformedException;
import com.example.licenseverifier.exceptions.LicenseIssuerMismatchException;
import com.example.licenseverifier.exceptions.LicenseKidUnknownException;
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
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CrlVerifierTest {

    private static final String ISSUER = "https://control-panel.example.com";
    private static final String KID = "key-2026-06-01";
    private static final String CRL_TYP = "crl+jwt";

    private static final Instant ISSUED_AT = Instant.parse("2026-06-01T10:00:00Z");
    private static final Instant NEXT_UPDATE = Instant.parse("2026-06-01T11:00:00Z");

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
    void verifies_signed_crl_and_extracts_claims() throws Exception {
        String crl = signCrl(ISSUER, KID, CRL_TYP, signingKey,
                List.of("lic_revoked_001", "lic_revoked_002"));

        RevocationList list = newVerifier(ISSUER).verify(crl);

        assertThat(list.issuer()).isEqualTo(ISSUER);
        assertThat(list.issuedAt()).isEqualTo(ISSUED_AT);
        assertThat(list.nextUpdate()).isEqualTo(NEXT_UPDATE);
        assertThat(list.revokedJtis()).containsExactlyInAnyOrder("lic_revoked_001", "lic_revoked_002");
        assertThat(list.isRevoked("lic_revoked_001")).isTrue();
        assertThat(list.isRevoked("lic_active_999")).isFalse();
    }

    @Test
    void verifies_crl_with_empty_revoked_list() throws Exception {
        String crl = signCrl(ISSUER, KID, CRL_TYP, signingKey, List.of());

        RevocationList list = newVerifier(ISSUER).verify(crl);

        assertThat(list.revokedJtis()).isEmpty();
        assertThat(list.isRevoked("anything")).isFalse();
    }

    @Test
    void verifies_crl_when_issuer_check_disabled() throws Exception {
        String crl = signCrl("https://some-other-issuer.example.com", KID, CRL_TYP, signingKey,
                List.of("lic_revoked_001"));

        // expectedIssuer == null skips the issuer check.
        RevocationList list = newVerifier(null).verify(crl);

        assertThat(list.issuer()).isEqualTo("https://some-other-issuer.example.com");
        assertThat(list.isRevoked("lic_revoked_001")).isTrue();
    }

    @Test
    void rejects_wrong_typ() throws Exception {
        // A license+jwt typed token must not be accepted as a CRL.
        String crl = signCrl(ISSUER, KID, "license+jwt", signingKey, List.of("lic_revoked_001"));

        assertThatThrownBy(() -> newVerifier(ISSUER).verify(crl))
                .isInstanceOf(LicenseFileMalformedException.class);
    }

    @Test
    void rejects_missing_typ() throws Exception {
        String crl = signCrl(ISSUER, KID, null, signingKey, List.of("lic_revoked_001"));

        assertThatThrownBy(() -> newVerifier(ISSUER).verify(crl))
                .isInstanceOf(LicenseFileMalformedException.class);
    }

    @Test
    void rejects_tampered_signature() throws Exception {
        String crl = signCrl(ISSUER, KID, CRL_TYP, signingKey, List.of("lic_revoked_001"));
        String[] parts = crl.split("\\.");
        // Tamper the FIRST signature char (the last base64url char only carries ignored padding bits).
        char[] sig = parts[2].toCharArray();
        sig[0] = sig[0] == 'A' ? 'B' : 'A';
        String tampered = parts[0] + "." + parts[1] + "." + new String(sig);

        assertThatThrownBy(() -> newVerifier(ISSUER).verify(tampered))
                .isInstanceOf(LicenseSignatureInvalidException.class);
    }

    @Test
    void rejects_tampered_payload() throws Exception {
        String crl = signCrl(ISSUER, KID, CRL_TYP, signingKey, List.of("lic_revoked_001"));
        String[] parts = crl.split("\\.");
        char[] payload = parts[1].toCharArray();
        payload[5] = payload[5] == 'A' ? 'B' : 'A';
        String tampered = parts[0] + "." + new String(payload) + "." + parts[2];

        assertThatThrownBy(() -> newVerifier(ISSUER).verify(tampered))
                .isInstanceOf(LicenseSignatureInvalidException.class);
    }

    @Test
    void rejects_unknown_kid() throws Exception {
        // Sign with a key whose kid is not in the published JWKS.
        OctetKeyPair otherKey = new OctetKeyPairGenerator(Curve.Ed25519)
                .keyID("rotated-out-key")
                .generate();
        String crl = signCrl(ISSUER, "rotated-out-key", CRL_TYP, otherKey, List.of("lic_revoked_001"));

        assertThatThrownBy(() -> newVerifier(ISSUER).verify(crl))
                .isInstanceOf(LicenseKidUnknownException.class);
    }

    @Test
    void rejects_issuer_mismatch() throws Exception {
        String crl = signCrl("https://attacker.example.com", KID, CRL_TYP, signingKey,
                List.of("lic_revoked_001"));

        assertThatThrownBy(() -> newVerifier(ISSUER).verify(crl))
                .isInstanceOf(LicenseIssuerMismatchException.class);
    }

    @Test
    void rejects_blank_input() {
        assertThatThrownBy(() -> newVerifier(ISSUER).verify("   "))
                .isInstanceOf(LicenseFileMalformedException.class);
    }

    @Test
    void rejects_non_jws_input() {
        assertThatThrownBy(() -> newVerifier(ISSUER).verify("not-a-jws-token"))
                .isInstanceOf(LicenseFileMalformedException.class);
    }

    private CrlVerifier newVerifier(String expectedIssuer) {
        return new CrlVerifier(PublicKeyProvider.fromJwks(jwksStream()), expectedIssuer);
    }

    private ByteArrayInputStream jwksStream() {
        return new ByteArrayInputStream(jwksJson.getBytes(StandardCharsets.UTF_8));
    }

    private static String signCrl(String issuer, String kid, String typ, OctetKeyPair key,
                                  List<String> revoked) throws JOSEException {
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .issuer(issuer)
                // iat / nextUpdate are read by CrlVerifier as numeric epoch-seconds claims.
                .claim("iat", ISSUED_AT.getEpochSecond())
                .claim("nextUpdate", NEXT_UPDATE.getEpochSecond())
                .claim("revoked", revoked)
                .build();

        JWSHeader.Builder headerBuilder = new JWSHeader.Builder(JWSAlgorithm.EdDSA)
                .keyID(kid);
        if (typ != null) {
            headerBuilder.type(new JOSEObjectType(typ));
        }

        SignedJWT jwt = new SignedJWT(headerBuilder.build(), claims);
        JWSSigner signer = new Ed25519Signer(key);
        jwt.sign(signer);
        return jwt.serialize();
    }
}
