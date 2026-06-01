package com.example.sample.api;

import com.example.licenseverifier.spring.RequiresPermission;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class ProtectedController {

    @RequiresPermission("export.pdf")
    @GetMapping(value = "/export/pdf", produces = MediaType.APPLICATION_PDF_VALUE)
    public ResponseEntity<byte[]> exportPdf() {
        byte[] body = ("%PDF-1.4\n% Sample license-gated PDF\n1 0 obj <<>> endobj\n%%EOF\n")
                .getBytes();
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .body(body);
    }

    @RequiresPermission("api.v2")
    @GetMapping("/v2/data")
    public Map<String, Object> v2Data() {
        return Map.of(
                "version", "v2",
                "generatedAt", Instant.now().toString(),
                "items", List.of(
                        Map.of("id", 1, "name", "alpha"),
                        Map.of("id", 2, "name", "beta")
                )
        );
    }

    @RequiresPermission(value = "admin.users.invite")
    @PostMapping("/admin/users/invite")
    public ResponseEntity<Map<String, Object>> invite(@RequestBody Map<String, Object> body) {
        Object email = body == null ? null : body.get("email");
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(Map.of(
                        "status", "accepted",
                        "email", email == null ? "" : email.toString()
                ));
    }
}
