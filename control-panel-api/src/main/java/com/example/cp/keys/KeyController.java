package com.example.cp.keys;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/admin/keys")
public class KeyController {

    private final KeyService keyService;

    public KeyController(KeyService keyService) {
        this.keyService = keyService;
    }

    /** Rotate the signing key: retire the current ACTIVE key and generate a fresh ACTIVE Ed25519 key. */
    @PostMapping("/rotate")
    @PreAuthorize("hasAuthority('key.rotate')")
    public ResponseEntity<KeyService.SigningKeyView> rotate() {
        SigningKey k = keyService.rotate();
        return ResponseEntity.ok(view(k));
    }

    /**
     * Rotate the key-encryption-key (KEK): re-encrypt every signing key's private material under the
     * currently-active KEK. Does not change which signing key is ACTIVE or what the JWKS publishes.
     */
    @PostMapping("/rotate-kek")
    @PreAuthorize("hasAuthority('key.rotate')")
    public ResponseEntity<KekRotationResult> rotateKek() {
        int count = keyService.rotateKek();
        return ResponseEntity.ok(new KekRotationResult(count));
    }

    /**
     * Flag a signing key as COMPROMISED. It is dropped from the JWKS immediately; if it was the
     * ACTIVE key, a fresh ACTIVE key is generated and returned so signing/issuance continues.
     */
    @PostMapping("/{kid}/compromise")
    @PreAuthorize("hasAuthority('key.rotate')")
    public ResponseEntity<CompromiseResult> markCompromised(@PathVariable("kid") String kid) {
        SigningKey replacement = keyService.markCompromised(kid).orElse(null);
        KeyService.SigningKeyView replacementView = replacement == null ? null : view(replacement);
        return ResponseEntity.ok(new CompromiseResult(kid, replacementView != null, replacementView));
    }

    @GetMapping
    @PreAuthorize("hasAuthority('key.rotate') or hasAuthority('key.read')")
    public List<KeyService.SigningKeyView> list() {
        return keyService.listForAdmin();
    }

    private static KeyService.SigningKeyView view(SigningKey k) {
        return new KeyService.SigningKeyView(
                k.getId(), k.getKid(), k.getAlgorithm(),
                k.getStatus().name(), k.getCreatedAt(), k.getRetiredAt(),
                k.getPublicKeyPem());
    }

    /** Result of a KEK rotation: how many signing-key rows were re-encrypted under the active KEK. */
    public record KekRotationResult(int reEncrypted) {}

    /**
     * Result of marking a key compromised: the flagged kid, whether a replacement ACTIVE key was
     * generated (true only when the compromised key had been ACTIVE), and that replacement, if any.
     */
    public record CompromiseResult(String kid, boolean replacementGenerated,
                                   KeyService.SigningKeyView replacement) {}
}
