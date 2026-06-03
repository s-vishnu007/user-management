package com.example.cp.webhooks;

import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;

/**
 * Computes the {@code X-CP-Signature} header value for an outbound webhook.
 *
 * <p>The signature is {@code "sha256=" + hex(HMAC_SHA256(secret, signingInput))} where the signing
 * input binds the delivery timestamp to the body — {@code "<timestamp>.<body>"} — so a captured
 * signature cannot be replayed with a different timestamp (the receiver recomputes over the same
 * concatenation using the {@code X-CP-Timestamp} header it received). This mirrors the
 * timestamped-signature scheme used by common webhook providers.
 */
@Component
public class WebhookSigner {

    private static final String HMAC_ALG = "HmacSHA256";
    private static final char[] HEX = "0123456789abcdef".toCharArray();

    /**
     * @param secret    the per-subscription HMAC secret (decrypted plaintext)
     * @param timestamp the value sent in the {@code X-CP-Timestamp} header (epoch seconds as a string)
     * @param body      the exact request body bytes' UTF-8 string
     * @return {@code "sha256=" + lowercase-hex HMAC} suitable for the {@code X-CP-Signature} header
     */
    public String sign(byte[] secret, String timestamp, String body) {
        if (secret == null) {
            throw new IllegalArgumentException("secret is required");
        }
        String signingInput = (timestamp == null ? "" : timestamp) + "." + (body == null ? "" : body);
        byte[] mac = hmac(secret, signingInput.getBytes(StandardCharsets.UTF_8));
        return "sha256=" + toHex(mac);
    }

    private static byte[] hmac(byte[] secret, byte[] data) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALG);
            mac.init(new SecretKeySpec(secret, HMAC_ALG));
            return mac.doFinal(data);
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            // HmacSHA256 is mandated by the JCA spec, so this is unreachable in practice.
            throw new IllegalStateException("HMAC-SHA256 unavailable", e);
        }
    }

    private static String toHex(byte[] bytes) {
        char[] out = new char[bytes.length * 2];
        for (int i = 0; i < bytes.length; i++) {
            int v = bytes[i] & 0xFF;
            out[i * 2] = HEX[v >>> 4];
            out[i * 2 + 1] = HEX[v & 0x0F];
        }
        return new String(out);
    }
}
