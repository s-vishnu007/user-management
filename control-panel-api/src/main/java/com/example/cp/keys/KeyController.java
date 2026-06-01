package com.example.cp.keys;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
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

    @PostMapping("/rotate")
    @PreAuthorize("hasAuthority('key.rotate')")
    public ResponseEntity<KeyService.SigningKeyView> rotate() {
        SigningKey k = keyService.rotate();
        return ResponseEntity.ok(new KeyService.SigningKeyView(
                k.getId(), k.getKid(), k.getAlgorithm(),
                k.getStatus().name(), k.getCreatedAt(), k.getRetiredAt(),
                k.getPublicKeyPem()
        ));
    }

    @GetMapping
    @PreAuthorize("hasAuthority('key.rotate') or hasAuthority('key.read')")
    public List<KeyService.SigningKeyView> list() {
        return keyService.listForAdmin();
    }
}
