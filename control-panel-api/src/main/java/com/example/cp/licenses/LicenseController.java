package com.example.cp.licenses;

import com.example.cp.common.ApiException;
import com.example.cp.common.SecurityUtils;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1")
public class LicenseController {

    private final LicenseIssuer issuer;
    private final LicenseFileBuilder fileBuilder;
    private final LicenseRevocationService revocationService;
    private final LicenseTokenRepository tokenRepo;
    private final LicenseEnvelopeCache envelopeCache;

    public LicenseController(LicenseIssuer issuer,
                             LicenseFileBuilder fileBuilder,
                             LicenseRevocationService revocationService,
                             LicenseTokenRepository tokenRepo,
                             LicenseEnvelopeCache envelopeCache) {
        this.issuer = issuer;
        this.fileBuilder = fileBuilder;
        this.revocationService = revocationService;
        this.tokenRepo = tokenRepo;
        this.envelopeCache = envelopeCache;
    }

    @PostMapping("/subscriptions/{subId}/licenses")
    @PreAuthorize("@tenantAccess.canIssueLicenseForSubscription(#subId)")
    public ResponseEntity<Map<String, Object>> issue(@PathVariable("subId") UUID subId,
                                                     @RequestBody(required = false) IssueRequest body) {
        Integer ttl = body == null ? null : body.ttlDays();
        List<String> audience = body == null ? null : body.audience();
        LicenseIssuer.IssuedLicense issued = issuer.issue(subId, ttl, audience);
        // Cache the raw JWT so /download returns the same artifact within the cache TTL.
        envelopeCache.put(issued.jti(), issued);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("jti", issued.jti());
        out.put("kid", issued.kid());
        out.put("issuedAt", issued.issuedAt());
        out.put("expiresAt", issued.expiresAt());
        // Returned in-response so callers can save the exact JWT immediately without
        // depending on the cache. The .lic envelope is also available via downloadUrl.
        out.put("license", issued.jwt());
        out.put("downloadUrl", "/api/v1/licenses/" + issued.jti() + "/download");
        return ResponseEntity.status(201).body(out);
    }

    @GetMapping("/licenses/{jti}/download")
    @PreAuthorize("@tenantAccess.canReadLicenseByJti(#jti)")
    public ResponseEntity<byte[]> download(@PathVariable String jti) {
        LicenseToken token = tokenRepo.findByJti(jti)
                .orElseThrow(() -> ApiException.notFound("License not found"));
        if (token.getStatus() == LicenseToken.Status.REVOKED) {
            throw ApiException.badRequest("License is revoked");
        }

        // Serve the exact JWT from the in-memory issuance cache when available.
        // On cache miss (process restart, etc) we re-issue a fresh license bound to
        // the same subscription so the caller still receives a valid envelope; the
        // returned jti will then be a new one and a new license_tokens row exists.
        LicenseIssuer.IssuedLicense issued = envelopeCache.get(jti).orElseGet(() ->
                issuer.issue(token.getSubscriptionId(),
                        computeRemainingDays(token.getExpiresAt()),
                        null)
        );
        byte[] body = fileBuilder.buildEnvelopeBytes(issued, null);
        String filename = fileBuilder.suggestedFilename(issued);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setContentDisposition(
                org.springframework.http.ContentDisposition.attachment().filename(filename).build()
        );
        return new ResponseEntity<>(body, headers, 200);
    }

    @PostMapping("/licenses/{jti}/revoke")
    @PreAuthorize("@tenantAccess.canRevokeLicenseByJti(#jti) or hasAuthority('SUPER_ADMIN')")
    public ResponseEntity<Void> revoke(@PathVariable String jti,
                                       @Valid @RequestBody(required = false) RevokeRequest body) {
        UUID actor = SecurityUtils.currentUser().map(u -> u.userId()).orElse(null);
        revocationService.markRevoked(jti, body == null ? null : body.reason(), actor);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/licenses")
    @PreAuthorize("@tenantAccess.canReadSubscription(#subscriptionId)")
    public List<LicenseDto> list(@RequestParam UUID subscriptionId,
                                 @RequestParam(required = false) String status) {
        // subscriptionId is REQUIRED: an unscoped, cross-org license enumeration is a tenant leak.
        // The caller is authorized against the subscription's owning org by @tenantAccess above.
        List<LicenseToken> rows;
        if (status != null) {
            rows = tokenRepo.findBySubscriptionIdAndStatusOrderByIssuedAtDesc(
                    subscriptionId, LicenseToken.Status.valueOf(status.toUpperCase()));
        } else {
            rows = tokenRepo.findBySubscriptionIdOrderByIssuedAtDesc(subscriptionId);
        }
        return rows.stream().map(LicenseDto::from).toList();
    }

    @GetMapping("/licenses/{jti}")
    @PreAuthorize("@tenantAccess.canReadLicenseByJti(#jti)")
    public LicenseDto getOne(@PathVariable String jti) {
        return LicenseDto.from(
                tokenRepo.findByJti(jti).orElseThrow(() -> ApiException.notFound("License not found"))
        );
    }

    private static Integer computeRemainingDays(OffsetDateTime expiresAt) {
        if (expiresAt == null) return null;
        long days = java.time.Duration.between(OffsetDateTime.now(), expiresAt).toDays();
        return (int) Math.max(1, days);
    }

    public record IssueRequest(Integer ttlDays, List<String> audience, String notes) {}

    public record RevokeRequest(String reason) {}
}
