package com.example.cp.common;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * Shared helpers for opaque, single-use security tokens (password reset, email verification, ...).
 *
 * <p>A token is a high-entropy random value handed to the user out-of-band; only its SHA-256 hash is
 * persisted, so a database read cannot recover a usable token. Centralised here so every flow uses
 * the same generation + hashing (URL-safe, unpadded Base64) rather than re-implementing it.
 */
public final class TokenHashing {

    private static final SecureRandom RNG = new SecureRandom();

    private TokenHashing() {
    }

    /** Generates a URL-safe, unpadded Base64 token from {@code numBytes} of CSPRNG entropy. */
    public static String generateRawToken(int numBytes) {
        byte[] buf = new byte[numBytes];
        RNG.nextBytes(buf);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(buf);
    }

    /** SHA-256 of the input, URL-safe Base64 (unpadded) — the form stored in token tables. */
    public static String sha256(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] out = md.digest(s.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(out);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
