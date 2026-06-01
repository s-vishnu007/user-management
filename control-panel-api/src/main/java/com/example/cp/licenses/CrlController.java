package com.example.cp.licenses;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/licenses")
public class CrlController {

    private final LicenseRevocationService revocationService;

    public CrlController(LicenseRevocationService revocationService) {
        this.revocationService = revocationService;
    }

    @GetMapping("/revoked")
    @PreAuthorize("permitAll()")
    public Map<String, Object> revoked(
            @RequestParam(value = "since", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime since
    ) {
        List<LicenseToken> rows = revocationService.listRevokedSince(since);
        List<Map<String, Object>> items = new ArrayList<>(rows.size());
        for (LicenseToken t : rows) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("jti", t.getJti());
            item.put("revokedAt", t.getRevokedAt());
            item.put("reason", t.getRevokeReason());
            items.add(item);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("revokedSince", since);
        out.put("items", items);
        out.put("generatedAt", OffsetDateTime.now());
        return out;
    }
}
