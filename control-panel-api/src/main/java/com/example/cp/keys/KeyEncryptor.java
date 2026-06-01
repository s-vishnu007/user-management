package com.example.cp.keys;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * AES-GCM envelope encryption for signing private keys.
 *
 * Blob layout: [IV (12 bytes) || ciphertext || GCM tag (16 bytes embedded by JCA)]
 * Master key supplied via env var app.signing.master-key (base64-encoded 16/24/32 bytes).
 */
@Component
public class KeyEncryptor {

    private static final int IV_LEN = 12;
    private static final int TAG_BITS = 128;
    private static final String TRANSFORM = "AES/GCM/NoPadding";

    private final String masterKeyB64;
    private final SecureRandom rng = new SecureRandom();
    private SecretKeySpec keySpec;

    public KeyEncryptor(@Value("${app.signing.master-key:}") String masterKeyB64) {
        this.masterKeyB64 = masterKeyB64;
    }

    @PostConstruct
    void init() {
        if (masterKeyB64 == null || masterKeyB64.isBlank()) {
            throw new IllegalStateException(
                    "app.signing.master-key (env APP_KEY_ENC_MASTER) is required — supply a base64-encoded 16/24/32-byte AES key");
        }
        byte[] raw;
        try {
            raw = Base64.getDecoder().decode(masterKeyB64.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("app.signing.master-key must be base64-encoded", e);
        }
        if (raw.length < 16) {
            throw new IllegalStateException(
                    "app.signing.master-key must decode to at least 16 bytes, got " + raw.length);
        }
        // Accept exactly 16/24/32 directly; otherwise derive a 32-byte AES key via SHA-256
        // so test/staging fixtures with arbitrary entropy still work without redeploying.
        byte[] aesKey;
        if (raw.length == 16 || raw.length == 24 || raw.length == 32) {
            aesKey = raw;
        } else {
            try {
                aesKey = java.security.MessageDigest.getInstance("SHA-256").digest(raw);
            } catch (Exception e) {
                throw new IllegalStateException("Failed to derive AES key from master secret", e);
            }
        }
        this.keySpec = new SecretKeySpec(aesKey, "AES");
    }

    public byte[] encrypt(byte[] plaintext) {
        try {
            byte[] iv = new byte[IV_LEN];
            rng.nextBytes(iv);
            Cipher cipher = Cipher.getInstance(TRANSFORM);
            cipher.init(Cipher.ENCRYPT_MODE, keySpec, new GCMParameterSpec(TAG_BITS, iv));
            byte[] ct = cipher.doFinal(plaintext);
            ByteBuffer buf = ByteBuffer.allocate(iv.length + ct.length);
            buf.put(iv).put(ct);
            return buf.array();
        } catch (Exception e) {
            throw new RuntimeException("Failed to encrypt private key", e);
        }
    }

    public byte[] decrypt(byte[] blob) {
        try {
            if (blob == null || blob.length <= IV_LEN) {
                throw new IllegalArgumentException("Encrypted blob too short");
            }
            byte[] iv = new byte[IV_LEN];
            System.arraycopy(blob, 0, iv, 0, IV_LEN);
            byte[] ct = new byte[blob.length - IV_LEN];
            System.arraycopy(blob, IV_LEN, ct, 0, ct.length);
            Cipher cipher = Cipher.getInstance(TRANSFORM);
            cipher.init(Cipher.DECRYPT_MODE, keySpec, new GCMParameterSpec(TAG_BITS, iv));
            return cipher.doFinal(ct);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("Failed to decrypt private key", e);
        }
    }
}
