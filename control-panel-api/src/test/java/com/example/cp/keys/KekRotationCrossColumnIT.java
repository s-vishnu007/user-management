package com.example.cp.keys;

import com.example.cp.support.AbstractIntegrationTest;
import com.example.cp.users.User;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration coverage for P1-6: a single {@link KeyEncryptor}/KEK protects four secret categories
 * (Ed25519 signing keys, TOTP secrets, webhook HMAC secrets, OIDC client secrets), but the old
 * {@code rotateKek()} re-encrypted only {@code signing_keys}. Once an operator dropped the old KEK
 * every MFA/webhook/SSO secret would become permanently undecryptable.
 *
 * <p>These tests drive {@link KeyService#rotateKek()} and assert it re-encrypts EVERY registered
 * encrypted column (not just signing keys) so that all blobs end up tagged with the active KEK and
 * survive a subsequent decrypt. The non-{@code keys} blobs are seeded GENERICALLY via JDBC using the
 * real {@link KeyEncryptor} bean (no mfa/webhooks/sso Java is touched), mirroring exactly how those
 * packages persist their secrets.
 */
class KekRotationCrossColumnIT extends AbstractIntegrationTest {

    @Autowired private KeyService keyService;
    @Autowired private KeyEncryptor keyEncryptor;
    @Autowired private JdbcTemplate jdbc;

    @Test
    void rotateKek_reEncryptsEveryEncryptedColumn_notJustSigningKeys() {
        // Seed one secret in each non-signing-key encrypted column, encrypted under the active KEK
        // exactly as the owning service would.
        User mfaUser = seedUser("mfa-rot-" + rnd() + "@example.com", "MFA Rot", false);
        String mfaSecret = "MFA-TOTP-" + rnd();
        jdbc.update("INSERT INTO user_mfa (user_id, secret_enc, enabled, created_at) VALUES (?,?,?,?)",
                mfaUser.getId(),
                keyEncryptor.encrypt(mfaSecret.getBytes(StandardCharsets.UTF_8)),
                true, OffsetDateTime.now());

        var org = seedOrg("kek-rot");
        java.util.UUID webhookId = com.example.cp.common.Ids.newId();
        String webhookSecret = "WH-HMAC-" + rnd();
        jdbc.update("INSERT INTO webhook_subscriptions (id, org_id, url, secret_enc, active, created_at) "
                        + "VALUES (?,?,?,?,?,?)",
                webhookId, org.getId(), "https://example.invalid/hook",
                keyEncryptor.encrypt(webhookSecret.getBytes(StandardCharsets.UTF_8)),
                true, OffsetDateTime.now());

        java.util.UUID ssoId = com.example.cp.common.Ids.newId();
        String ssoSecret = "OIDC-CLIENT-" + rnd();
        jdbc.update("INSERT INTO sso_providers (id, org_id, type, config_json, enabled, client_secret_enc, created_at) "
                        + "VALUES (?,?,?,?::jsonb,?,?,?)",
                ssoId, org.getId(), "OIDC", "{}", true,
                keyEncryptor.encrypt(ssoSecret.getBytes(StandardCharsets.UTF_8)),
                OffsetDateTime.now());

        // Rotate the KEK. With the fix this walks ALL registered columns, not only signing_keys.
        int reEncrypted = keyService.rotateKek();
        assertThat(reEncrypted).isGreaterThanOrEqualTo(4); // >=1 signing key + the 3 seeded secrets

        // Every seeded secret still decrypts to its original plaintext after rotation...
        assertThat(decrypt("user_mfa", "secret_enc", "user_id", mfaUser.getId())).isEqualTo(mfaSecret);
        assertThat(decrypt("webhook_subscriptions", "secret_enc", "id", webhookId)).isEqualTo(webhookSecret);
        assertThat(decrypt("sso_providers", "client_secret_enc", "id", ssoId)).isEqualTo(ssoSecret);

        // ...and every blob is now tagged with the ACTIVE KEK id (the whole point of rotation: the old
        // KEK can be dropped afterward without orphaning any of these secrets).
        String active = keyEncryptor.activeKekId();
        assertThat(referencedKek("user_mfa", "secret_enc", "user_id", mfaUser.getId())).isEqualTo(active);
        assertThat(referencedKek("webhook_subscriptions", "secret_enc", "id", webhookId)).isEqualTo(active);
        assertThat(referencedKek("sso_providers", "client_secret_enc", "id", ssoId)).isEqualTo(active);
    }

    @Test
    void dropGuard_passesWhenEveryBlobReferencesAConfiguredKek() {
        // Seed a blob under the active KEK, then assert the startup drop-guard does not object: every
        // referenced KEK is configured. (A negative case — a blob under a now-missing KEK — cannot be
        // exercised against the running context because the KEK set is fixed at boot; that path is
        // covered by the unit test KeyEncryptorTest#versionedBlobReferencingUnknownKek_isReported plus
        // the guard's own scan logic asserted here on the happy path.)
        User u = seedUser("guard-" + rnd() + "@example.com", "Guard", false);
        jdbc.update("INSERT INTO user_mfa (user_id, secret_enc, enabled, created_at) VALUES (?,?,?,?)",
                u.getId(),
                keyEncryptor.encrypt("guard-secret".getBytes(StandardCharsets.UTF_8)),
                true, OffsetDateTime.now());

        // Should not throw: all referenced KEKs (active + default) are configured under the test profile.
        keyService.assertNoOrphanedKekReferences();

        // Sanity: the blob we just wrote references a configured KEK id.
        String kek = referencedKek("user_mfa", "secret_enc", "user_id", u.getId());
        assertThat(keyEncryptor.configuredKekIds()).contains(kek);
    }

    private String decrypt(String table, String blobCol, String pkCol, Object pk) {
        byte[] blob = jdbc.queryForObject(
                "SELECT " + blobCol + " FROM " + table + " WHERE " + pkCol + " = ?", byte[].class, pk);
        return new String(keyEncryptor.decrypt(blob), StandardCharsets.UTF_8);
    }

    private String referencedKek(String table, String blobCol, String pkCol, Object pk) {
        byte[] blob = jdbc.queryForObject(
                "SELECT " + blobCol + " FROM " + table + " WHERE " + pkCol + " = ?", byte[].class, pk);
        return keyEncryptor.referencedKekId(blob);
    }
}
