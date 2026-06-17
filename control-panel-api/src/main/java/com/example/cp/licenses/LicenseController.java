package com.example.cp.licenses;

import com.example.cp.common.ApiException;
import com.example.cp.common.PageRequestParams;
import com.example.cp.common.SecurityUtils;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Size;
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
    private final LicenseGrantService grantService;
    private final LicenseFileBuilder fileBuilder;
    private final LicenseRevocationService revocationService;
    private final LicenseTokenRepository tokenRepo;
    private final LicenseArtifactRepository artifactRepo;
    private final ActivationService activationService;

    public LicenseController(LicenseIssuer issuer,
                             LicenseGrantService grantService,
                             LicenseFileBuilder fileBuilder,
                             LicenseRevocationService revocationService,
                             LicenseTokenRepository tokenRepo,
                             LicenseArtifactRepository artifactRepo,
                             ActivationService activationService) {
        this.issuer = issuer;
        this.grantService = grantService;
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

        return ResponseEntity.status(201).body(issuedBody(issued));
    }

    /**
     * Per-user license issuance (the primary Licenses workspace flow): mints a JWT for a specific user
     * inside {@code orgId} carrying a hand-picked RBAC grant set — no plan, no subscription. The
     * subject may be identified by {@code userId} or provisioned from {@code email} (invite-by-email);
     * the grant set is {@code expand(roleCodes) ∪ permissions}, validated against the RBAC catalog.
     */
    @PostMapping("/orgs/{orgId}/licenses")
    @PreAuthorize("@tenantAccess.canIssueLicenseForOrg(#orgId)")
    public ResponseEntity<Map<String, Object>> issueForOrg(@PathVariable("orgId") UUID orgId,
                                                           @Valid @RequestBody(required = false) IssueForUserRequest body) {
        IssueForUserRequest req = body == null ? new IssueForUserRequest(null, null, null, null, null, null, null, null) : body;
        boolean trial = Boolean.TRUE.equals(req.trial());
        LicenseIssuer.IssuedLicense issued = grantService.issue(
                orgId, req.userId(), req.email(),
                req.roleCodes(), req.permissions(),
                req.ttlDays(), req.audience(), trial);
        return ResponseEntity.status(201).body(issuedBody(issued));
    }

    @GetMapping("/orgs/{orgId}/licenses")
    @PreAuthorize("@tenantAccess.canAccessOrg(#orgId)")
    public List<LicenseDto> listByOrg(@PathVariable("orgId") UUID orgId,
                                      @RequestParam(required = false) String status,
                                      @RequestParam(required = false) Integer page,
                                      @RequestParam(required = false) Integer size) {
        // Org is the direct tenant anchor; the caller is authorized against it by @tenantAccess above.
        // Server-side page/size caps bound the result set (mirrors the subscription-scoped list).
        org.springframework.data.domain.Pageable pageable = PageRequestParams.of(page, size, null);
        List<LicenseToken> rows;
        if (status != null) {
            LicenseToken.Status parsed;
            try {
                parsed = LicenseToken.Status.valueOf(status.toUpperCase());
            } catch (IllegalArgumentException e) {
                throw ApiException.badRequest("Invalid status: " + status);
            }
            rows = tokenRepo.findByOrgIdAndStatusOrderByIssuedAtDesc(orgId, parsed, pageable);
        } else {
            rows = tokenRepo.findByOrgIdOrderByIssuedAtDesc(orgId, pageable);
        }
        return rows.stream().map(LicenseDto::from).toList();
    }

    /**
     * The catalog of grants an admin may bake into a per-user license: the permission catalog plus
     * roles with their expanded permission codes (so the UI can offer "role preset, then fine-tune").
     * Authenticated-only — it exposes the static RBAC vocabulary, not any assignment.
     */
    @GetMapping("/licenses/assignable-grants")
    @PreAuthorize("hasAuthority('license.issue') or hasAuthority('SUPER_ADMIN')")
    public LicenseGrantService.AssignableGrants assignableGrants() {
        return grantService.assignableGrants();
    }

    private static Map<String, Object> issuedBody(LicenseIssuer.IssuedLicense issued) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("jti", issued.jti());
        out.put("kid", issued.kid());
        out.put("issuedAt", issued.issuedAt());
        out.put("expiresAt", issued.expiresAt());
        // Returned in-response so callers can save the exact JWT immediately without
        // depending on the cache. The .lic envelope is also available via downloadUrl.
        out.put("license", issued.jwt());
        out.put("downloadUrl", "/api/v1/licenses/" + issued.jti() + "/download");
        return out;
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

    /**
     * Body for per-user issuance ({@code POST /orgs/{orgId}/licenses}). Identify the subject by
     * {@code userId} OR {@code email} (an unknown email is provisioned + added to the org). The grant
     * set is {@code roleCodes} (presets, expanded server-side) unioned with individual {@code
     * permissions}. {@code ttlDays}/{@code audience}/{@code trial} mirror the subscription issue body.
     */
    public record IssueForUserRequest(
            UUID userId,
            @Email(message = "email must be a valid address")
            @Size(max = 320, message = "email too long")
            String email,
            @Size(max = 50, message = "at most 50 roleCodes")
            List<@Size(max = 64) String> roleCodes,
            @Size(max = 500, message = "at most 500 permissions")
            List<@Size(max = 128) String> permissions,
            Integer ttlDays,
            @Size(max = 50, message = "at most 50 audiences")
            List<@Size(max = 256) String> audience,
            Boolean trial,
            @Size(max = 1000) String notes) {}

    public record RevokeRequest(String reason) {}
}
