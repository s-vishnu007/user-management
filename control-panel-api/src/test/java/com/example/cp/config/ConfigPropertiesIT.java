package com.example.cp.config;

import com.example.cp.keys.KeyEncryptor;
import com.example.cp.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the configuration shape owned by the CONFIG bucket actually resolves under the {@code test}
 * profile (application.yml + application-test.yml), and that the Wave-3 properties bind to the values the
 * KEYS and BILLING buckets read.
 *
 * <p>This is the regression net for the YAML this bucket owns: a typo in the {@code app.signing.*} /
 * {@code app.billing.*} keys, or accidentally shaping {@code app.signing.master-keys} as a YAML map
 * (which would NOT bind to {@link KeyEncryptor}'s {@code @Value} String), would slip past
 * {@code ContextLoadsTest} for billing and silently change KEK behaviour for keys — this test catches it.
 */
class ConfigPropertiesIT extends AbstractIntegrationTest {

    @Autowired Environment env;
    @Autowired KeyEncryptor keyEncryptor;

    // The KEK list MUST bind as a single String of "id:base64" entries (not a map).
    @Value("${app.signing.master-keys:}") String masterKeysSpec;
    @Value("${app.signing.active-master-key-id:}") String activeMasterKeyId;
    @Value("${app.signing.master-key:}") String legacyMasterKey;

    @Value("${app.billing.currency:}") String billingCurrency;
    @Value("${app.billing.default-unit-amount:-1}") long billingDefaultUnitAmount;

    @Test
    void signingKekPropertiesBindToTheShapeKeyEncryptorReads() {
        // master-keys is a scalar "id:base64" string, not a map (a map bound to String renders as
        // "{v1=...}"). Check for the map braces, NOT "=" — base64 values legitimately end in "=" padding.
        assertThat(masterKeysSpec).isNotBlank();
        assertThat(masterKeysSpec).startsWith("v1:");
        assertThat(masterKeysSpec).doesNotContain("{").doesNotContain("}");

        // active id is present and IS one of the configured ids (else KeyEncryptor would have failed to boot).
        assertThat(activeMasterKeyId).isEqualTo("v1");
        assertThat(masterKeysSpec).contains(activeMasterKeyId + ":");

        // legacy single-key stays configured for back-compat (the reserved "default" KEK).
        assertThat(legacyMasterKey).isNotBlank();

        // The wired KeyEncryptor bootstrapped from this config (it failed fast at startup otherwise) and
        // round-trips under its active KEK via the contracted encrypt/decrypt API.
        byte[] plaintext = "config-bucket-roundtrip".getBytes(StandardCharsets.UTF_8);
        assertThat(keyEncryptor.decrypt(keyEncryptor.encrypt(plaintext))).isEqualTo(plaintext);
    }

    @Test
    void billingDefaultsBind() {
        assertThat(billingCurrency).isEqualTo("USD");
        assertThat(billingDefaultUnitAmount).isEqualTo(0L);
    }
}
