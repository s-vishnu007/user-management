package com.example.cp.licenses;

import com.example.cp.common.ApiException;
import com.example.cp.common.PageRequestParams;
import com.example.cp.common.SecurityUtils;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
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
    private final LicenseArtifactRepository artifactRepo;
    private final ActivationService activationService;

    public LicenseController(LicenseIssuer issuer,
                             LicenseFileBuilder fileBuilder,
                             LicenseRevocationService revocationService,
                             LicenseTokenRepository tokenRepo,
                             LicenseArtifactRepository artifactRepo,
                             ActivationService activationService) {
        this.issuer = issuer;
        this.fileBuilder = fileBuilder;
        this.revocationService = revocationService;
        this.tokenRepo = tokenRepo;
        this.artifactRepo = artifactRepo;
        this.activationService = activationService;
    }

    @PostMapping("/subscriptions/{subId}/licenses")
    @PreAuthorize("@tenantAccess.canIssueLicenseForSubscription(#subId)")
    public ResponseEntity<Map<String, Object>> issue(@PathVariable("subId") UUID subId,
                                                     @RequestBody(required = false) IssueRequest body) {
        Integer ttl = body == null ? null : body.ttlDays();
        List<String> audience = body == null ? null : body.audience();
        boolean trial = body != null && Boolean.TRUE.equals(body.trial());
        LicenseIssuer.IssuedLicense issued = trial
                ? issuer.issueTrial(subId, ttl, audience)
                : issuer.issue(subId, ttl, audience);
        // The exact signed artifact is persisted by the issuer (license_artifacts) so /download is a
        // pure read of the stored JWT — no in-memory cache and no re-issue on miss.

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
        // PURE READ (audit P1-4): a GET must never have write side-effects. The signed artifact was
        // persisted at issue time, so we simply return it (or 404/410) — we never call issuer.issue()
        // here. This closes the path where a read-only principal could mint a brand-new license (new
        // jti / row / outbox event, zero audit) by downloading a jti that had aged out of a cache.
        LicenseToken token = tokenRepo.findByJti(jti)
                .orElseThrow(() -> ApiException.notFound("License not found"));
        if (token.getStatus() == LicenseToken.Status.REVOKED) {
            // 410 Gone: the resource existed but is no longer downloadable.
            throw new ApiException(HttpStatus.GONE, "Gone", "License is revoked");
        }

        LicenseArtifact artifact = artifactRepo.findByJti(jti)
                .orElseThrow(() -> new ApiException(HttpStatus.GONE, "Gone",
                        "No downloadable artifact for this license"));
        LicenseIssuer.IssuedLicense issued = artifact.toIssuedLicense();

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
                                 @RequestParam(required = false) String status,
                                 @RequestParam(required = false) Integer page,
                                 @RequestParam(required = false) Integer size) {
        // subscriptionId is REQUIRED: an unscoped, cross-org license enumeration is a tenant leak.
        // The caller is authorized against the subscription's owning org by @tenantAccess above.
        // Server-side page/size caps (PageRequestParams.MAX_SIZE) bound the result set so a single
        // subscription cannot return an unbounded license list (P3). Ordering is fixed by the query.
        org.springframework.data.domain.Pageable pageable =
                PageRequestParams.of(page, size, null);
        List<LicenseToken> rows;
        if (status != null) {
            LicenseToken.Status parsed;
            try {
                parsed = LicenseToken.Status.valueOf(status.toUpperCase());
            } catch (IllegalArgumentException e) {
                throw ApiException.badRequest("Invalid status: " + status);
            }
            rows = tokenRepo.findBySubscriptionIdAndStatusOrderByIssuedAtDesc(
                    subscriptionId, parsed, pageable);
        } else {
            rows = tokenRepo.findBySubscriptionIdOrderByIssuedAtDesc(subscriptionId, pageable);
        }
        return rows.stream().map(LicenseDto::from).toList();
    }

    @GetMapping("/licenses/{jti}")
    @PreAuthorize("@tenantAccess.canReadLicenseByJti(#jti)")
    public LicenseDto getOne(@PathVariable String jti) {
        LicenseToken token = tokenRepo.findByJti(jti)
                .orElseThrow(() -> ApiException.notFound("License not found"));
        return LicenseDto.from(token, activationService.activeSeatCount(jti));
    }

    public record IssueRequest(Integer ttlDays, List<String> audience, String notes, Boolean trial) {}

    public record RevokeRequest(String reason) {}
}
