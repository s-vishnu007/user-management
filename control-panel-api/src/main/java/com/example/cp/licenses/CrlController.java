package com.example.cp.licenses;

import com.example.cp.keys.JwsSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/licenses")
public class CrlController {

    private final LicenseRevocationService revocationService;
    private final JwsSigner jwsSigner;
    private final String issuer;
    private final Duration crlTtl;

    public CrlController(LicenseRevocationService revocationService,
                         JwsSigner jwsSigner,
                         @Value("${app.signing.issuer}") String issuer,
                         @Value("${app.signing.crl-ttl:PT1H}") Duration crlTtl) {
        this.revocationService = revocationService;
        this.jwsSigner = jwsSigner;
        this.issuer = issuer;
        this.crlTtl = crlTtl;
    }

    /**
     * Returns the signed certificate-revocation list as a compact JWS (typ=crl+jwt) carrying the
     * full set of REVOKED jti. Signed with the active Ed25519 key so it verifies against the same
     * /.well-known/jwks.json used for licenses. Public (see SecurityConfig allow-list).
     */
    @GetMapping(value = "/crl", produces = "application/jwt")
    @PreAuthorize("permitAll()")
    public ResponseEntity<String> crl() {
        List<LicenseToken> rows = revocationService.listRevokedSince(null);
        List<String> jtis = rows.stream().map(LicenseToken::getJti).toList();

        Instant now = Instant.now();
        Instant next = now.plus(crlTtl);
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .issuer(issuer)
                .claim("iat", now.getEpochSecond())
                .claim("nextUpdate", next.getEpochSecond())
                .claim("revoked", jtis)
                .build();

        String jws = jwsSigner.sign(claims, "crl+jwt");

        return ResponseEntity.ok()
                .header("Cache-Control", "public, max-age=" + crlTtl.toSeconds())
                .contentType(MediaType.valueOf("application/jwt"))
                .body(jws);
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
