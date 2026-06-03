package com.example.licenseverifier;

import com.example.licenseverifier.exceptions.LicenseFileMalformedException;
import com.example.licenseverifier.exceptions.LicenseIssuerMismatchException;
import com.example.licenseverifier.exceptions.LicenseSignatureInvalidException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.JWSVerifier;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;

import java.text.ParseException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Parses and cryptographically verifies a signed CRL JWS against a {@link PublicKeyProvider} (the
 * same JWKS used to verify licenses), enforcing {@code typ=crl+jwt}, the EdDSA algorithm, a known
 * {@code kid}, and (optionally) an issuer match. Mirrors {@link LicenseVerifier}'s verifier
 * resolution via the shared {@link Ed25519Verifiers} helper so the two never diverge.
 */
public final class CrlVerifier {

    private static final String EXPECTED_TYP = "crl+jwt";

    private final PublicKeyProvider keyProvider;
    private final String expectedIssuer;

    /**
     * @param keyProvider    JWKS provider; the CRL must verify against the same keys as licenses
     * @param expectedIssuer required issuer ({@code iss}); may be {@code null} to skip the check
     */
    public CrlVerifier(PublicKeyProvider keyProvider, String expectedIssuer) {
        this.keyProvider = Objects.requireNonNull(keyProvider, "keyProvider");
        this.expectedIssuer = expectedIssuer;
    }

    public RevocationList verify(String crlJws) {
        if (crlJws == null || crlJws.isBlank()) {
            throw new LicenseFileMalformedException("CRL content is empty");
        }

        SignedJWT signedJwt;
        try {
            signedJwt = SignedJWT.parse(crlJws.trim());
        } catch (ParseException e) {
            throw new LicenseFileMalformedException("CRL is not a valid JWS token", e);
        }

        JWSHeader header = signedJwt.getHeader();
        if (header.getAlgorithm() == null || !JWSAlgorithm.EdDSA.equals(header.getAlgorithm())) {
            throw new LicenseSignatureInvalidException(
                    "Unsupported CRL JWS algorithm: " + header.getAlgorithm() + " (expected EdDSA)");
        }
        if (header.getType() == null || !EXPECTED_TYP.equalsIgnoreCase(header.getType().getType())) {
            throw new LicenseFileMalformedException(
                    "Unexpected CRL typ: " + (header.getType() == null ? "null" : header.getType().getType())
                            + " (expected " + EXPECTED_TYP + ")");
        }
        String kid = header.getKeyID();
        if (kid == null || kid.isBlank()) {
            throw new LicenseFileMalformedException("CRL JWS header is missing 'kid'");
        }

        JWSVerifier verifier = Ed25519Verifiers.resolve(keyProvider, kid);
        try {
            if (!signedJwt.verify(verifier)) {
                throw new LicenseSignatureInvalidException("CRL JWS signature is invalid");
            }
        } catch (LicenseSignatureInvalidException e) {
            throw e;
        } catch (Exception e) {
            throw new LicenseSignatureInvalidException("Failed to verify CRL JWS signature", e);
        }

        JWTClaimsSet claims;
        try {
            claims = signedJwt.getJWTClaimsSet();
        } catch (ParseException e) {
            throw new LicenseFileMalformedException("Failed to parse CRL claims", e);
        }

        String issuer = claims.getIssuer();
        if (expectedIssuer != null && !expectedIssuer.equals(issuer)) {
            throw new LicenseIssuerMismatchException(expectedIssuer, issuer);
        }

        Instant issuedAt = readEpochSeconds(claims, "iat", false);
        Instant nextUpdate = readEpochSeconds(claims, "nextUpdate", true);

        List<String> revoked = readRevoked(claims);

        return new RevocationList(issuer, issuedAt, nextUpdate, new java.util.HashSet<>(revoked));
    }

    private static Instant readEpochSeconds(JWTClaimsSet claims, String name, boolean required) {
        Object raw = claims.getClaim(name);
        if (raw == null) {
            if (required) {
                throw new LicenseFileMalformedException("CRL is missing the '" + name + "' claim");
            }
            return null;
        }
        if (raw instanceof Number n) {
            return Instant.ofEpochSecond(n.longValue());
        }
        try {
            return Instant.ofEpochSecond(Long.parseLong(raw.toString()));
        } catch (NumberFormatException e) {
            throw new LicenseFileMalformedException(
                    "CRL claim '" + name + "' is not a numeric epoch-seconds value", e);
        }
    }

    private static List<String> readRevoked(JWTClaimsSet claims) {
        Object raw = claims.getClaim("revoked");
        if (raw == null) {
            return List.of();
        }
        if (!(raw instanceof List<?> list)) {
            throw new LicenseFileMalformedException("CRL 'revoked' claim must be a JSON array of strings");
        }
        List<String> jtis = new ArrayList<>(list.size());
        for (Object element : list) {
            if (!(element instanceof String s)) {
                throw new LicenseFileMalformedException("CRL 'revoked' claim must contain only strings");
            }
            jtis.add(s);
        }
        return jtis;
    }
}
