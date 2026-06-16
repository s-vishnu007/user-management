package com.example.licenseverifier.spring;

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
import java.time.Instant;
import java.util.Date;
import java.util.List;

/**
 * Shared test fixture: an Ed25519 signing key, its public JWKS, and helpers that mint signed
 * {@code license+jwt} and {@code crl+jwt} tokens identical in shape to what the control panel
 * issues. Used by the starter's offline-verification integration tests so they exercise the real
 * {@link com.example.licenseverifier.LicenseVerifier} / {@link com.example.licenseverifier.CrlVerifier}
 * crypto rather than mocks.
 */
final class TestCrypto {

    static final String AUDIENCE = "docker-app-prod";
    static final String ISSUER = "https://control-panel.example.com";
    static final String KID = "key-test";

    private final OctetKeyPair signingKey;
    private final String jwksJson;

    TestCrypto() {
        try {
            this.signingKey = new OctetKeyPairGenerator(Curve.Ed25519).keyID(KID).generate();
            JWKSet jwkSet = new JWKSet(List.of(signingKey.toPublicJWK()));
            this.jwksJson = jwkSet.toString(true);
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
    }

    String jwksJson() {
        return jwksJson;
    }

    /** A valid, non-expired license with the given jti. */
    String signLicense(String jti, Instant iat, Instant exp) {
        return signLicense(jti, iat, exp, signingKey);
    }

    String signLicense(String jti, Instant iat, Instant exp, OctetKeyPair key) {
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .issuer(ISSUER)
                .audience(AUDIENCE)
                .subject("org_example")
                .jwtID(jti)
                .issueTime(Date.from(iat))
                .notBeforeTime(Date.from(iat))
                .expirationTime(Date.from(exp))
                .claim("subscription_id", "sub_example_pro")
                .claim("plan", "pro")
                .claim("permissions", List.of("export.pdf", "api.v2"))
                .claim("seats", 5)
                .claim("version", 1)
                .build();
        return sign(claims, "license+jwt", key);
    }

    /** A signed CRL (typ=crl+jwt) with the given issuedAt/nextUpdate and revoked jti set. */
    String signCrl(Instant issuedAt, Instant nextUpdate, List<String> revoked) {
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .issuer(ISSUER)
                .claim("iat", issuedAt.getEpochSecond())
                .claim("nextUpdate", nextUpdate.getEpochSecond())
                .claim("revoked", revoked)
                .build();
        return sign(claims, "crl+jwt", signingKey);
    }

    private String sign(JWTClaimsSet claims, String typ, OctetKeyPair key) {
        try {
            JWSHeader header = new JWSHeader.Builder(JWSAlgorithm.EdDSA)
                    .keyID(key.getKeyID())
                    .type(new JOSEObjectType(typ))
                    .build();
            SignedJWT jwt = new SignedJWT(header, claims);
            JWSSigner signer = new Ed25519Signer(key);
            jwt.sign(signer);
            return jwt.serialize();
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
    }
}
