package com.example.cp.compliance;

import com.example.cp.audit.AuditOutcome;
import com.example.cp.audit.AuditWriter;
import com.example.cp.common.ApiException;
import com.example.cp.common.AuditContext;
import com.example.cp.common.AuthenticatedUser;
import com.example.cp.common.SecurityUtils;
import com.example.cp.common.TrustedProxyResolver;
import com.example.cp.orgs.OrgService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * GDPR/CCPA data-subject endpoints: right of access / portability ({@code export}), right to erasure
 * ({@code erase}), and tenant off-boarding ({@code tenant/{orgId}/delete}).
 *
 * <p>Authorization is performed explicitly in-method (rather than via {@code @PreAuthorize}) because
 * the export endpoint's subject is selected by query parameter (userId XOR orgId) and the allowed
 * caller differs per subject ("super_admin OR the subject themselves OR an org admin"). Each method
 * sets the audit action BEFORE any forbidden throw so denials are captured by the
 * {@code GlobalExceptionHandler} fallback, and writes an explicit fail-closed SUCCESS row on the
 * mutating paths.
 */
@RestController
@RequestMapping("/api/v1/privacy")
public class DataPrivacyController {

    private final DataExportService exportService;
    private final ErasureService erasureService;
    private final OrgService orgService;
    private final AuditWriter auditWriter;
    private final TrustedProxyResolver proxyResolver;

    public DataPrivacyController(DataExportService exportService,
                                 ErasureService erasureService,
                                 OrgService orgService,
                                 AuditWriter auditWriter,
                                 TrustedProxyResolver proxyResolver) {
        this.exportService = exportService;
        this.erasureService = erasureService;
        this.orgService = orgService;
        this.auditWriter = auditWriter;
        this.proxyResolver = proxyResolver;
    }

    /**
     * Right-of-access export. Exactly one of {@code userId} / {@code orgId} must be supplied.
     * <ul>
     *   <li>user export: allowed for super_admin or the subject themselves;</li>
     *   <li>org export: allowed for super_admin or an OWNER/ADMIN of that org.</li>
     * </ul>
     */
    @GetMapping("/export")
    public Map<String, Object> export(@RequestParam(required = false) UUID userId,
                                      @RequestParam(required = false) UUID orgId,
                                      HttpServletRequest request) {
        AuthenticatedUser me = SecurityUtils.requireUser();

        if ((userId == null) == (orgId == null)) {
            throw ApiException.badRequest("Exactly one of userId or orgId must be provided");
        }

        Map<String, Object> result;
        if (userId != null) {
            AuditContext.set("privacy.export");
            AuditContext.setTarget("user", userId.toString());
            if (!canExportUser(me, userId)) {
                throw ApiException.forbidden("Not permitted to export this subject");
            }
            result = exportService.exportUser(userId);
            recordSuccess("privacy.export", "user", userId, me, request,
                    Map.of("subjectType", "user"));
        } else {
            AuditContext.set("privacy.export");
            AuditContext.setTarget("org", orgId.toString());
            if (!canExportOrg(me, orgId)) {
                throw ApiException.forbidden("Not permitted to export this organization");
            }
            result = exportService.exportOrg(orgId);
            recordSuccess("privacy.export", "org", orgId, me, request,
                    Map.of("subjectType", "org"));
        }
        return result;
    }

    /** Right to erasure for a single human subject. Super-admin only. */
    @PostMapping("/erase")
    public ResponseEntity<Map<String, Object>> erase(@RequestBody EraseRequest body,
                                                     HttpServletRequest request) {
        AuditContext.set("privacy.erase");
        AuthenticatedUser me = SecurityUtils.requireUser();
        if (body == null || body.userId() == null) {
            throw ApiException.badRequest("userId is required");
        }
        UUID userId = body.userId();
        AuditContext.setTarget("user", userId.toString());
        requireSuperAdmin(me);

        ErasureLog ledger = erasureService.eraseUser(userId, me.userId());
        recordFailClosedSuccess("privacy.erase", "user", userId, me, request,
                Map.of("erasureLogId", ledger.getId().toString()));

        Map<String, Object> resp = new HashMap<>();
        resp.put("status", "erased");
        resp.put("userId", userId.toString());
        resp.put("erasureLogId", ledger.getId().toString());
        return ResponseEntity.ok(resp);
    }

    /** Tenant off-boarding: erase member PII + mark the org DELETED. Super-admin only; audited fail-closed. */
    @PostMapping("/tenant/{orgId}/delete")
    public ResponseEntity<Map<String, Object>> deleteTenant(@PathVariable UUID orgId,
                                                            HttpServletRequest request) {
        AuditContext.set("privacy.tenant.deleted");
        AuditContext.setTarget("org", orgId.toString());
        AuthenticatedUser me = SecurityUtils.requireUser();
        requireSuperAdmin(me);

        ErasureLog ledger = erasureService.deleteTenant(orgId, me.userId());
        recordFailClosedSuccess("privacy.tenant.deleted", "org", orgId, me, request,
                Map.of("erasureLogId", ledger.getId().toString()));

        Map<String, Object> resp = new HashMap<>();
        resp.put("status", "deleted");
        resp.put("orgId", orgId.toString());
        resp.put("erasureLogId", ledger.getId().toString());
        return ResponseEntity.ok(resp);
    }

    // ------------------------------------------------------------------
    // authorization
    // ------------------------------------------------------------------

    private boolean canExportUser(AuthenticatedUser me, UUID userId) {
        if (me.superAdmin()) return true;
        // The subject themselves (human principals only; an api-key has no user id).
        return !me.isApiKey() && userId.equals(me.userId());
    }

    private boolean canExportOrg(AuthenticatedUser me, UUID orgId) {
        if (me.superAdmin()) return true;
        if (me.isApiKey()) return false;
        if (me.userId() == null) return false;
        return orgService.roleOf(orgId, me.userId())
                .map(r -> r == com.example.cp.orgs.OrgMember.Role.OWNER
                        || r == com.example.cp.orgs.OrgMember.Role.ADMIN)
                .orElse(false);
    }

    private void requireSuperAdmin(AuthenticatedUser me) {
        if (!me.superAdmin()) {
            throw ApiException.forbidden("Super-admin required");
        }
    }

    // ------------------------------------------------------------------
    // audit helpers
    // ------------------------------------------------------------------

    private void recordSuccess(String action, String targetType, UUID targetId,
                               AuthenticatedUser me, HttpServletRequest request,
                               Map<String, Object> payload) {
        Map<String, Object> p = new HashMap<>(payload);
        String ip = proxyResolver.resolveClientIp(request);
        auditWriter.record(actorId(me), me.isApiKey() ? me.apiKeyOrgId() : null,
                action, targetType, targetId.toString(), p, ip, AuditOutcome.SUCCESS, false);
        AuditContext.markRecorded();
    }

    private void recordFailClosedSuccess(String action, String targetType, UUID targetId,
                                         AuthenticatedUser me, HttpServletRequest request,
                                         Map<String, Object> payload) {
        Map<String, Object> p = new HashMap<>(payload);
        String ip = proxyResolver.resolveClientIp(request);
        auditWriter.record(actorId(me), me.isApiKey() ? me.apiKeyOrgId() : null,
                action, targetType, targetId.toString(), p, ip, AuditOutcome.SUCCESS, true);
        // Suppress the AuditInterceptor's @AfterReturning duplicate for these mutating endpoints.
        AuditContext.markRecorded();
    }

    private UUID actorId(AuthenticatedUser me) {
        return me.userId();
    }

    public record EraseRequest(@NotNull UUID userId) {}
}
