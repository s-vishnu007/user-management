package com.example.cp.webhooks;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Pure, hermetic unit tests for {@link WebhookSigner}. The expected digests are precomputed test
 * vectors (independent HMAC-SHA256 of {@code "<timestamp>.<body>"}), so this both pins the exact
 * wire format of the {@code X-CP-Signature} header and guards against accidental signing-input drift.
 */
class WebhookSignerTest {

    private final WebhookSigner signer = new WebhookSigner();

    @Test
    void sign_matchesKnownHmacVector() {
        byte[] secret = "topsecret".getBytes(StandardCharsets.UTF_8);
        String sig = signer.sign(secret, "1717200000", "{\"hello\":\"world\"}");
        assertThat(sig).isEqualTo(
                "sha256=78aea795e09face2eccc06b181261b990bcc477c068d85e47bdef83a02bba39c");
    }

    @Test
    void sign_isPrefixedAndLowerHex() {
        byte[] secret = "k".getBytes(StandardCharsets.UTF_8);
        String sig = signer.sign(secret, "1", "body");
        assertThat(sig).startsWith("sha256=");
        String hex = sig.substring("sha256=".length());
        assertThat(hex).hasSize(64);                 // 32-byte digest -> 64 hex chars
        assertThat(hex).matches("[0-9a-f]{64}");     // lowercase hex only
    }

    @Test
    void sign_bindsTimestampToBody() {
        byte[] secret = "k".getBytes(StandardCharsets.UTF_8);
        // Same body, different timestamps must produce different signatures (replay binding).
        assertThat(signer.sign(secret, "100", "body"))
                .isNotEqualTo(signer.sign(secret, "200", "body"));
        // Same timestamp, different bodies must differ.
        assertThat(signer.sign(secret, "100", "a"))
                .isNotEqualTo(signer.sign(secret, "100", "b"));
    }

    @Test
    void sign_isDeterministicForSameInputs() {
        byte[] secret = "abc".getBytes(StandardCharsets.UTF_8);
        assertThat(signer.sign(secret, "42", "payload"))
                .isEqualTo(signer.sign(secret, "42", "payload"));
    }

    @Test
    void sign_nullTimestampOrBody_treatedAsEmpty() {
        byte[] secret = "topsecret".getBytes(StandardCharsets.UTF_8);
        // null timestamp + null body collapses to signing input "." -> known vector.
        assertThat(signer.sign(secret, null, null)).isEqualTo(
                "sha256=d3a4de24e9549894634f19007ee5ce056b63ac648f5d8c55f109ea44ba973858");
    }

    @Test
    void sign_nullSecret_rejected() {
        assertThatThrownBy(() -> signer.sign(null, "1", "body"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
