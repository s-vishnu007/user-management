package com.example.licenseverifier;

import com.example.licenseverifier.exceptions.LicenseFileMalformedException;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSet;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.PublicKey;
import java.util.LinkedHashMap;
import java.util.Map;

public final class JwksParser {

    private JwksParser() {
    }

    public static Map<String, PublicKey> parse(InputStream input) {
        return toPublicKeys(parseJwks(readAll(input)));
    }

    public static Map<String, PublicKey> parse(String json) {
        return toPublicKeys(parseJwks(json));
    }

    static Map<String, JWK> parseJwks(InputStream input) {
        return parseJwks(readAll(input));
    }

    static Map<String, JWK> parseJwks(String json) {
        try {
            JWKSet jwkSet = JWKSet.parse(json);
            Map<String, JWK> result = new LinkedHashMap<>();
            for (JWK jwk : jwkSet.getKeys()) {
                String kid = jwk.getKeyID();
                if (kid == null || kid.isBlank()) {
                    throw new LicenseFileMalformedException("JWKS entry is missing 'kid'");
                }
                result.put(kid, jwk);
            }
            return result;
        } catch (LicenseFileMalformedException e) {
            throw e;
        } catch (Exception e) {
            throw new LicenseFileMalformedException("Failed to parse JWKS JSON", e);
        }
    }

    /**
     * Exports each JWK to a {@link PublicKey} where the type supports it (RSA/EC). Ed25519/X25519
     * keys cannot be exported to a JCA {@link PublicKey} via Nimbus and are omitted here — they are
     * consumed through the JWK directly by {@link PublicKeyProvider}/{@link LicenseVerifier}.
     */
    private static Map<String, PublicKey> toPublicKeys(Map<String, JWK> jwks) {
        Map<String, PublicKey> result = new LinkedHashMap<>();
        for (Map.Entry<String, JWK> entry : jwks.entrySet()) {
            PublicKey publicKey = PublicKeyProvider.exportPublicKey(entry.getValue());
            if (publicKey != null) {
                result.put(entry.getKey(), publicKey);
            }
        }
        return result;
    }

    private static String readAll(InputStream input) {
        if (input == null) {
            throw new LicenseFileMalformedException("JWKS input stream is null");
        }
        try {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new LicenseFileMalformedException("Failed to read JWKS input", e);
        }
    }
}
