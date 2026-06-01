package com.example.cp.keys;

import com.nimbusds.jose.jwk.JWK;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
public class JwksController {

    private final KeyService keyService;

    public JwksController(KeyService keyService) {
        this.keyService = keyService;
    }

    @GetMapping(value = "/.well-known/jwks.json", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> jwks() {
        List<SigningKey> rows = keyService.listPublishedKeys();
        List<Map<String, Object>> keys = new ArrayList<>(rows.size());
        for (SigningKey row : rows) {
            JWK jwk = keyService.toJwk(row);
            keys.add(jwk.toJSONObject());
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("keys", keys);
        return ResponseEntity.ok()
                .header("Cache-Control", "public, max-age=300")
                .body(out);
    }
}
