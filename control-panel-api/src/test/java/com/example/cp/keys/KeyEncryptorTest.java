package com.example.cp.keys;

import org.junit.jupiter.api.Test;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Pure unit tests for {@link KeyEncryptor}'s versioned (envelope) key-encryption-key support:
 *
 * <ul>
 *   <li>versioned round-trip under the active KEK,</li>
 *   <li>back-compat decrypt of a legacy <em>unversioned</em> blob (the format the pre-KEK-rotation
 *       code wrote) under the reserved {@code default} KEK,</li>
 *   <li>multi-KEK decrypt: a blob written under an older KEK still decrypts after the active KEK
 *       changes,</li>
 *   <li>active-KEK selection / mis-configuration guards.</li>
 * </ul>
 *
 * These never touch Spring; each {@link KeyEncryptor} is constructed directly and {@code init()} is
 * invoked reflectively (it is package-private).
 */
class KeyEncryptorTest {

    // 32-byte AES keys, base64. Distinct so a cross-KEK decrypt under the wrong key would fail the GCM tag.
    private static final String KEK_A_B64 =
            Base64.getEncoder().encodeToString("AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA".getBytes(StandardCharsets.UTF_8));
    private static final String KEK_B_B64 =
            Base64.getEncoder().encodeToString("BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB".getBytes(StandardCharsets.UTF_8));
    private static final String LEGACY_B64 =
            Base64.getEncoder().encodeToString("LLLLLLLLLLLLLLLLLLLLLLLLLLLLLLLL".getBytes(StandardCharsets.UTF_8));

    private static KeyEncryptor init(String legacyMasterKey, String masterKeys, String activeId) {
        KeyEncryptor e = new KeyEncryptor(legacyMasterKey, masterKeys, activeId);
        try {
            Method m = KeyEncryptor.class.getDeclaredMethod("init");
            m.setAccessible(true);
            m.invoke(e);
        } catch (java.lang.reflect.InvocationTargetException ex) {
            // Surface the real exception init() threw (e.g. IllegalStateException) instead of the
            // reflection wrapper, so fail-fast assertions can match on it.
            Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
            if (cause instanceof RuntimeException re) {
                throw re;
            }
            throw new RuntimeException(cause);
        } catch (ReflectiveOperationException ex) {
            throw new RuntimeException("init() reflection failed", ex);
        }
        return e;
    }

    @Test
    void versionedRoundTrip_underActiveKek() {
        KeyEncryptor e = init("", "v1:" + KEK_A_B64 + ",v2:" + KEK_B_B64, "v2");
        assertThat(e.activeKekId()).isEqualTo("v2");

        byte[] plaintext = "super-secret-private-key-bytes".getBytes(StandardCharsets.UTF_8);
        byte[] blob = e.encrypt(plaintext);

        // Versioned blobs start with the 0x01 magic followed by the active KEK id "v2".
        assertThat(blob[0]).isEqualTo((byte) 0x01);
        assertThat(blob.length).isGreaterThan(plaintext.length);

        assertThat(e.decrypt(blob)).isEqualTo(plaintext);
    }

    @Test
    void backCompat_decryptsLegacyUnversionedBlob_underDefaultKek() throws Exception {
        // Encryptor whose ONLY key is the legacy app.signing.master-key (registered as id "default").
        KeyEncryptor e = init(LEGACY_B64, "", "");
        assertThat(e.activeKekId()).isEqualTo(KeyEncryptor.DEFAULT_KEK_ID);

        byte[] plaintext = "legacy-blob-plaintext".getBytes(StandardCharsets.UTF_8);

        // Hand-build a legacy blob exactly as the pre-KEK-rotation KeyEncryptor did: [IV(12)][ct||tag],
        // with NO magic/version prefix, encrypted under the same AES key the legacy master key derives.
        byte[] legacyBlob = legacyEncrypt(LEGACY_B64, plaintext);
        assertThat(legacyBlob[0]).isNotEqualTo((byte) 0x01).as("test must exercise the legacy fallback path");

        assertThat(e.decrypt(legacyBlob)).isEqualTo(plaintext);

        // And a newly-encrypted (versioned) blob also round-trips on the same encryptor.
        assertThat(e.decrypt(e.encrypt(plaintext))).isEqualTo(plaintext);
    }

    @Test
    void multiKek_oldBlobStillDecryptsAfterActiveKekRotates() {
        // Encrypt under v1 while v1 is active...
        KeyEncryptor before = init("", "v1:" + KEK_A_B64, "v1");
        byte[] plaintext = "rotate-me".getBytes(StandardCharsets.UTF_8);
        byte[] blobUnderV1 = before.encrypt(plaintext);
        assertThat(blobUnderV1[0]).isEqualTo((byte) 0x01);

        // ...then deploy with BOTH v1 and v2 present and v2 active. The v1-tagged blob must still
        // decrypt (its KEK id is embedded), and new encryptions go under v2.
        KeyEncryptor after = init("", "v1:" + KEK_A_B64 + ",v2:" + KEK_B_B64, "v2");
        assertThat(after.activeKekId()).isEqualTo("v2");
        assertThat(after.decrypt(blobUnderV1)).isEqualTo(plaintext);

        byte[] reEncrypted = after.encrypt(after.decrypt(blobUnderV1));
        assertThat(reEncrypted[0]).isEqualTo((byte) 0x01);
        assertThat(after.decrypt(reEncrypted)).isEqualTo(plaintext);
    }

    @Test
    void unknownActiveKekId_failsFast() {
        assertThatThrownBy(() -> init("", "v1:" + KEK_A_B64, "nope"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("active-master-key-id");
    }

    @Test
    void noKeysConfigured_failsFast() {
        assertThatThrownBy(() -> init("", "", ""))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("No key-encryption-key configured");
    }

    @Test
    void versionedBlobReferencingUnknownKek_isReported() {
        // Blob written under v2, but the running encryptor only knows v1 -> must surface a clear error,
        // NOT silently fall back to the legacy layout.
        KeyEncryptor writer = init("", "v2:" + KEK_B_B64, "v2");
        byte[] blob = writer.encrypt("x".getBytes(StandardCharsets.UTF_8));

        KeyEncryptor reader = init("", "v1:" + KEK_A_B64, "v1");
        assertThatThrownBy(() -> reader.decrypt(blob))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("unknown KEK id");
    }

    /** Replicates the exact legacy (pre-versioning) blob layout: [IV(12)][ciphertext||GCM tag]. */
    private static byte[] legacyEncrypt(String masterKeyB64, byte[] plaintext) throws Exception {
        byte[] aesKey = Base64.getDecoder().decode(masterKeyB64);
        SecretKeySpec keySpec = new SecretKeySpec(aesKey, "AES");
        byte[] iv = new byte[12];
        new SecureRandom().nextBytes(iv);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, keySpec, new GCMParameterSpec(128, iv));
        byte[] ct = cipher.doFinal(plaintext);
        return ByteBuffer.allocate(iv.length + ct.length).put(iv).put(ct).array();
    }
}
