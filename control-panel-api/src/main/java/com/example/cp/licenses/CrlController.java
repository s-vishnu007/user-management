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
import java.util.concurrent.atomic.AtomicReference;

@RestController
@RequestMapping("/api/v1/licenses")
public class CrlController {

    private final LicenseRevocationService revocationService;
    private final JwsSigner jwsSigner;
    private final String issuer;
    private final Duration crlTtl;

    /**
     * Cached signed CRL (audit P2). The endpoint is {@code permitAll}, and previously re-scanned the
     * revoked set, decrypted the signing key, and Ed25519-signed on EVERY request — anonymous DoS
     * amplification. We now cache the signed JWS and only re-sign when (a) the revoked set changes
     * (tracked by {@code revocationStateKey}) or (b) the cached JWS is older than {@code crlTtl}, so
     * {@code iat}/{@code nextUpdate} stay fresh. The cache is a single immutable snapshot swapped
     * atomically; concurrent readers see a consistent value.
     */
    private final AtomicReference<CachedCrl> cache = new AtomicReference<>();

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
     * Returns the signed certificate-revocation list as a compact JWS (typ=crl+jwt) carrying the set
     * of REVOKED, still-unexpired jti. Signed with the active Ed25519 key so it verifies against the
     * same /.well-known/jwks.json used for licenses. Public (see SecurityConfig allow-list) and
     * cached (see {@link #cache}) so it is not re-signed on every anonymous hit.
     */
    @GetMapping(value = "/crl", produces = "application/jwt")
    @PreAuthorize("permitAll()")
    public ResponseEntity<String> crl() {
        Instant now = Instant.now();
        String stateKey = revocationService.revocationStateKey();

        CachedCrl current = cache.get();
        if (current == null
                || !current.stateKey().equals(stateKey)
                || current.signedAt().plus(crlTtl).isBefore(now)) {
            current = regenerate(stateKey, now);
        }

        return ResponseEntity.ok()
                .header("Cache-Control", "public, max-age=" + crlTtl.toSeconds())
                .contentType(MediaType.valueOf("application/jwt"))
                .body(current.jws());
    }

    private CachedCrl regenerate(String stateKey, Instant now) {
        // Prune expired-but-revoked jtis (P3): an offline verifier already rejects them on expiry, so
        // the signed CRL only needs to carry still-unexpired revoked jtis and cannot grow unbounded.
        List<LicenseToken> rows = revocationService.listActiveRevocations(OffsetDateTime.now());
        List<String> jtis = rows.stream().map(LicenseToken::getJti).toList();

        Instant next = now.plus(crlTtl);
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .issuer(issuer)
                .claim("iat", now.getEpochSecond())
                .claim("nextUpdate", next.getEpochSecond())
                .claim("revoked", jtis)
                .build();

        String jws = jwsSigner.sign(claims, "crl+jwt");
        CachedCrl fresh = new CachedCrl(stateKey, now, jws);
        cache.set(fresh);
        return fresh;
    }

    /**
     * Invalidates the cached CRL so the next request re-signs. Called when the active signing key is
     * rotated/compromised (the cached JWS would otherwise stay signed under the old kid until TTL).
     * The keys fixer can invoke this via the bean if it wires a rotation callback; it is also safe
     * to call defensively.
     */
    public void invalidateCache() {
        cache.set(null);
    }

    private record CachedCrl(String stateKey, Instant signedAt, String jws) {}

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
