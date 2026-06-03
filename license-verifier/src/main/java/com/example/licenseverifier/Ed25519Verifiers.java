package com.example.licenseverifier;

import com.example.licenseverifier.exceptions.LicenseKidUnknownException;
import com.example.licenseverifier.exceptions.LicenseSignatureInvalidException;
import com.nimbusds.jose.JWSVerifier;
import com.nimbusds.jose.crypto.Ed25519Verifier;
import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.OctetKeyPair;
import com.nimbusds.jose.util.Base64URL;

import java.security.PublicKey;
import java.security.interfaces.EdECPublicKey;
import java.util.Arrays;
import java.util.Optional;

/**
 * Shared Ed25519 JWS verifier-resolution logic used by both {@link LicenseVerifier} and
 * {@link CrlVerifier} so the two never diverge (in particular, both prefer the
 * {@link PublicKeyProvider.JwkProvider} path that preserves the raw Ed25519 encoding for OKP
 * JWKs that cannot be exported to a {@link PublicKey}).
 */
final class Ed25519Verifiers {

    private Ed25519Verifiers() {
    }

    /**
     * Resolve an {@link Ed25519Verifier} for the given kid from the provider, preferring the
     * original JWK when available and otherwise reconstructing from the exported {@link PublicKey}.
     *
     * @throws LicenseKidUnknownException       if the kid is unknown to the provider
     * @throws LicenseSignatureInvalidException if a verifier cannot be built for the kid
     */
    static JWSVerifier resolve(PublicKeyProvider keyProvider, String kid) {
        // Preferred path: built-in providers expose the original JWK so we get the exact raw
        // Ed25519 encoding from the JWKS without going through PublicKey.
        if (keyProvider instanceof PublicKeyProvider.JwkProvider jwkProvider) {
            Optional<JWK> jwk = jwkProvider.findJwkByKid(kid);
            if (jwk.isPresent()) {
                if (jwk.get() instanceof OctetKeyPair okp && Curve.Ed25519.equals(okp.getCurve())) {
                    try {
                        return new Ed25519Verifier(okp);
                    } catch (Exception e) {
                        throw new LicenseSignatureInvalidException(
                                "Failed to build Ed25519 verifier for kid '" + kid + "'", e);
                    }
                }
                throw new LicenseSignatureInvalidException(
                        "JWKS entry for kid '" + kid + "' is not an Ed25519 OctetKeyPair");
            }
            throw new LicenseKidUnknownException(kid, keyProvider.knownKids());
        }

        // Custom provider path: only PublicKey is available, reconstruct OctetKeyPair.
        PublicKey publicKey = keyProvider.findByKid(kid)
                .orElseThrow(() -> new LicenseKidUnknownException(kid, keyProvider.knownKids()));
        if (!(publicKey instanceof EdECPublicKey)) {
            throw new LicenseSignatureInvalidException(
                    "Public key for kid '" + kid + "' is not an Ed25519 key: " + publicKey.getClass().getName());
        }
        try {
            OctetKeyPair okp = new OctetKeyPair.Builder(Curve.Ed25519, encodeEd25519PublicKey(publicKey))
                    .build();
            return new Ed25519Verifier(okp);
        } catch (Exception e) {
            throw new LicenseSignatureInvalidException(
                    "Failed to build Ed25519 verifier for kid '" + kid + "'", e);
        }
    }

    static Base64URL encodeEd25519PublicKey(PublicKey publicKey) {
        // JDK X.509 SubjectPublicKeyInfo for Ed25519 is always 44 bytes:
        //   12-byte ASN.1 header { 30 2A 30 05 06 03 2B 65 70 03 21 00 } || 32 raw key bytes
        // The final 32 bytes are the raw Ed25519 public key per RFC 8032.
        byte[] encoded = publicKey.getEncoded();
        if (encoded == null || encoded.length < 32) {
            throw new LicenseSignatureInvalidException(
                    "Ed25519 public key encoding is too short: " + (encoded == null ? 0 : encoded.length));
        }
        byte[] raw = Arrays.copyOfRange(encoded, encoded.length - 32, encoded.length);
        return Base64URL.encode(raw);
    }
}
