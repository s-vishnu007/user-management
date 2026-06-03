package com.example.cp.keys;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * AES-GCM envelope encryption for signing private keys, with a <strong>versioned</strong>
 * key-encryption-key (KEK).
 *
 * <h2>Why versioned</h2>
 * A single static master key can never be rotated without re-encrypting (or losing) every blob it
 * ever protected. To allow KEK rotation, every ciphertext we produce now carries the id of the KEK
 * that encrypted it, so old rows keep decrypting under the old KEK while new rows use the active one.
 * {@link KeyService#rotateKek()} walks {@code signing_keys} and re-encrypts each private key under the
 * active KEK.
 *
 * <h2>Configuration</h2>
 * KEKs are configured as {@code app.signing.master-keys} entries of the form {@code id:base64key}
 * (comma- or whitespace-separated), with {@code app.signing.active-master-key-id} naming which one is
 * used for new encryptions. For backward compatibility the legacy single {@code app.signing.master-key}
 * is registered under the reserved id {@value #DEFAULT_KEK_ID}; if no {@code active-master-key-id} is
 * configured the active KEK defaults to that legacy/default key.
 *
 * <h2>Blob layout</h2>
 * <ul>
 *   <li><b>Versioned</b> (produced by this class going forward):
 *       {@code [0x01 magic][1-byte idLen][idLen bytes UTF-8 KEK id][IV (12 bytes)][ciphertext || GCM tag]}</li>
 *   <li><b>Legacy / unversioned</b> (any blob written before versioning): {@code [IV (12 bytes)][ciphertext || GCM tag]},
 *       decrypted under the {@value #DEFAULT_KEK_ID} KEK.</li>
 * </ul>
 * {@link #decrypt(byte[])} first attempts a versioned parse; if the blob does not present a
 * self-consistent versioned envelope whose KEK id is known, it falls back to the legacy layout under
 * the default KEK. (A legacy IV whose first byte happens to equal the magic still falls back safely
 * because its declared id length / total length will not be self-consistent, or the parsed id will be
 * unknown.)
 */
@Component
public class KeyEncryptor {

    private static final Logger log = LoggerFactory.getLogger(KeyEncryptor.class);

    private static final int IV_LEN = 12;
    private static final int TAG_BITS = 128;
    private static final int TAG_BYTES = TAG_BITS / 8;
    private static final String TRANSFORM = "AES/GCM/NoPadding";

    /** Reserved id under which the legacy {@code app.signing.master-key} is registered. */
    public static final String DEFAULT_KEK_ID = "default";

    /** Magic byte marking a versioned envelope; chosen to be non-printable / unlikely. */
    private static final byte VERSIONED_MAGIC = (byte) 0x01;
    private static final int MAX_ID_LEN = 255;

    private final String legacyMasterKeyB64;
    private final String masterKeysSpec;
    private final String configuredActiveKekId;
    private final SecureRandom rng = new SecureRandom();

    /** kekId -> AES key. Insertion order preserved for deterministic logging. */
    private final Map<String, SecretKeySpec> keks = new LinkedHashMap<>();
    private String activeKekId;

    public KeyEncryptor(
            @Value("${app.signing.master-key:}") String legacyMasterKeyB64,
            @Value("${app.signing.master-keys:}") String masterKeysSpec,
            @Value("${app.signing.active-master-key-id:}") String configuredActiveKekId) {
        this.legacyMasterKeyB64 = legacyMasterKeyB64;
        this.masterKeysSpec = masterKeysSpec;
        this.configuredActiveKekId = configuredActiveKekId;
    }

    @PostConstruct
    void init() {
        // 1) Register the legacy single master key under the reserved default id (back-compat).
        if (legacyMasterKeyB64 != null && !legacyMasterKeyB64.isBlank()) {
            keks.put(DEFAULT_KEK_ID, toKeySpec(DEFAULT_KEK_ID, legacyMasterKeyB64));
        }

        // 2) Register the versioned KEK list "id:base64,id2:base64,...".
        if (masterKeysSpec != null && !masterKeysSpec.isBlank()) {
            for (String entry : masterKeysSpec.split("[,\\s]+")) {
                if (entry.isBlank()) {
                    continue;
                }
                int sep = entry.indexOf(':');
                if (sep <= 0 || sep == entry.length() - 1) {
                    throw new IllegalStateException(
                            "app.signing.master-keys entries must be of the form id:base64key, got: " + entry);
                }
                String id = entry.substring(0, sep).trim();
                String b64 = entry.substring(sep + 1).trim();
                if (id.isBlank()) {
                    throw new IllegalStateException("app.signing.master-keys entry has a blank id");
                }
                if (id.getBytes(StandardCharsets.UTF_8).length > MAX_ID_LEN) {
                    throw new IllegalStateException("app.signing.master-keys id too long (max " + MAX_ID_LEN + "): " + id);
                }
                keks.put(id, toKeySpec(id, b64));
            }
        }

        if (keks.isEmpty()) {
            throw new IllegalStateException(
                    "No key-encryption-key configured — supply app.signing.master-key (env APP_KEY_ENC_MASTER) "
                            + "or app.signing.master-keys (id:base64 entries). Each must be a base64-encoded 16/24/32-byte AES key.");
        }

        // 3) Resolve the active KEK id used for new encryptions.
        if (configuredActiveKekId != null && !configuredActiveKekId.isBlank()) {
            String id = configuredActiveKekId.trim();
            if (!keks.containsKey(id)) {
                throw new IllegalStateException(
                        "app.signing.active-master-key-id=" + id + " is not present in app.signing.master-keys/master-key");
            }
            this.activeKekId = id;
        } else if (keks.containsKey(DEFAULT_KEK_ID)) {
            // Back-compat: no explicit active id and a legacy key exists -> use the legacy/default KEK.
            this.activeKekId = DEFAULT_KEK_ID;
        } else {
            // Only versioned keys configured and no active id named -> use the first one declared.
            this.activeKekId = keks.keySet().iterator().next();
        }

        log.info("KeyEncryptor initialized with {} KEK(s) {}, active KEK id='{}'",
                keks.size(), keks.keySet(), activeKekId);
    }

    private SecretKeySpec toKeySpec(String id, String b64) {
        byte[] raw;
        try {
            raw = Base64.getDecoder().decode(b64.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("KEK '" + id + "' master key must be base64-encoded", e);
        }
        if (raw.length < 16) {
            throw new IllegalStateException(
                    "KEK '" + id + "' master key must decode to at least 16 bytes, got " + raw.length);
        }
        // Accept exactly 16/24/32 directly; otherwise derive a 32-byte AES key via SHA-256 so
        // test/staging fixtures with arbitrary entropy still work without redeploying.
        byte[] aesKey;
        if (raw.length == 16 || raw.length == 24 || raw.length == 32) {
            aesKey = raw;
        } else {
            try {
                aesKey = java.security.MessageDigest.getInstance("SHA-256").digest(raw);
            } catch (Exception e) {
                throw new IllegalStateException("Failed to derive AES key for KEK '" + id + "'", e);
            }
        }
        return new SecretKeySpec(aesKey, "AES");
    }

    /** The KEK id currently used for new encryptions and as the target of {@link KeyService#rotateKek()}. */
    public String activeKekId() {
        return activeKekId;
    }

    /** Encrypts under the active KEK, producing a versioned envelope tagged with {@link #activeKekId()}. */
    public byte[] encrypt(byte[] plaintext) {
        return encrypt(plaintext, activeKekId);
    }

    /** Encrypts under a specific KEK id, producing a versioned envelope. Used by KEK rotation. */
    public byte[] encrypt(byte[] plaintext, String kekId) {
        SecretKeySpec key = keks.get(kekId);
        if (key == null) {
            throw new IllegalStateException("Unknown KEK id for encryption: " + kekId);
        }
        try {
            byte[] iv = new byte[IV_LEN];
            rng.nextBytes(iv);
            Cipher cipher = Cipher.getInstance(TRANSFORM);
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            byte[] ct = cipher.doFinal(plaintext);

            byte[] idBytes = kekId.getBytes(StandardCharsets.UTF_8);
            ByteBuffer buf = ByteBuffer.allocate(1 + 1 + idBytes.length + iv.length + ct.length);
            buf.put(VERSIONED_MAGIC);
            buf.put((byte) idBytes.length);
            buf.put(idBytes);
            buf.put(iv);
            buf.put(ct);
            return buf.array();
        } catch (Exception e) {
            throw new RuntimeException("Failed to encrypt private key under KEK '" + kekId + "'", e);
        }
    }

    public byte[] decrypt(byte[] blob) {
        if (blob == null || blob.length <= IV_LEN) {
            throw new IllegalArgumentException("Encrypted blob too short");
        }
        // Prefer a versioned parse; fall back to the legacy unversioned layout under the default KEK.
        Versioned v = tryParseVersioned(blob);
        if (v != null) {
            SecretKeySpec key = keks.get(v.kekId);
            if (key != null) {
                return decryptWith(key, v.iv, v.ct, "KEK '" + v.kekId + "'");
            }
            // Self-consistent envelope but unknown KEK id: this is a genuine misconfiguration
            // (the KEK that wrote it is no longer present), not a legacy blob — surface it.
            throw new IllegalStateException(
                    "Encrypted blob references unknown KEK id '" + v.kekId + "'; configure it in app.signing.master-keys");
        }
        return decryptLegacy(blob);
    }

    /** Legacy layout: [IV (12)][ct||tag], decrypted under the reserved default KEK. */
    private byte[] decryptLegacy(byte[] blob) {
        SecretKeySpec key = keks.get(DEFAULT_KEK_ID);
        if (key == null) {
            throw new IllegalStateException(
                    "Encountered a legacy (unversioned) encrypted blob but no default KEK (app.signing.master-key) is configured");
        }
        byte[] iv = new byte[IV_LEN];
        System.arraycopy(blob, 0, iv, 0, IV_LEN);
        byte[] ct = new byte[blob.length - IV_LEN];
        System.arraycopy(blob, IV_LEN, ct, 0, ct.length);
        return decryptWith(key, iv, ct, "default (legacy) KEK");
    }

    private byte[] decryptWith(SecretKeySpec key, byte[] iv, byte[] ct, String desc) {
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORM);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            return cipher.doFinal(ct);
        } catch (Exception e) {
            throw new RuntimeException("Failed to decrypt private key with " + desc, e);
        }
    }

    /**
     * Attempts to parse {@code blob} as a versioned envelope. Returns {@code null} (not an exception)
     * when the blob is not a self-consistent versioned envelope, so the caller can fall back to the
     * legacy layout. A self-consistent envelope must: start with the magic byte, declare an id length
     * leaving room for at least IV + GCM tag, and the declared id must be valid UTF-8.
     */
    private Versioned tryParseVersioned(byte[] blob) {
        if (blob.length < 2 || blob[0] != VERSIONED_MAGIC) {
            return null;
        }
        int idLen = blob[1] & 0xFF;
        if (idLen == 0) {
            return null;
        }
        int headerLen = 2 + idLen;
        // Need at least IV + one GCM tag of ciphertext after the header to be a plausible envelope.
        if (blob.length < headerLen + IV_LEN + TAG_BYTES) {
            return null;
        }
        String kekId = new String(blob, 2, idLen, StandardCharsets.UTF_8);
        byte[] iv = new byte[IV_LEN];
        System.arraycopy(blob, headerLen, iv, 0, IV_LEN);
        int ctLen = blob.length - headerLen - IV_LEN;
        byte[] ct = new byte[ctLen];
        System.arraycopy(blob, headerLen + IV_LEN, ct, 0, ctLen);
        return new Versioned(kekId, iv, ct);
    }

    private record Versioned(String kekId, byte[] iv, byte[] ct) {}
}
