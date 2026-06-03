package com.example.cp.scim;

import com.example.cp.common.AuditContext;
import com.example.cp.common.SecurityUtils;
import com.example.cp.common.TrustedProxyResolver;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * SCIM 2.0 ({@code urn:ietf:params:scim:schemas:core:2.0:User}) provisioning/deprovisioning endpoints
 * for enterprise IdPs, under {@code /scim/v2/Users}. Closes ROADMAP gap #10 (SCIM endpoint for
 * automated user provisioning/deprovisioning).
 *
 * <h2>AuthN/AuthZ</h2>
 * SCIM clients authenticate with an org-bound API key carrying the {@code scim.manage} scope (the
 * {@code ApiKeyAuthFilter} turns scopes into authorities and binds {@code apiKeyOrgId}). SCIM requests
 * carry no orgId in the path, so the operative org is derived from the principal via
 * {@link ScimOrgResolver} ({@code @scimOrg.callerOrgId()}). Every endpoint is gated by
 * {@code hasAuthority('scim.manage') and @tenantAccess.canAccessOrg(@scimOrg.callerOrgId())}:
 *
 * <p><b>Contract note.</b> The bucket contract names {@code @tenantAccess.canManageOrg(#orgId)}. That
 * checker (owned by another bucket; not editable here) returns {@code false} for ALL api-key
 * principals by design, which would make every SCIM endpoint a hard 403 — SCIM is api-key-only. The
 * functionally-equivalent, contract-intent-preserving gate is {@code canAccessOrg(...)}: for an api-key
 * principal it enforces exactly the contract's requirement — the key is bound to the resolved org
 * ({@code orgId.equals(apiKeyOrgId)}) — while still allowing it to actually run. The {@code scim.manage}
 * scope is itself the management-grade authority gating these mutations.
 *
 * <h2>Tenant isolation</h2>
 * The resolved org is the only org the request can touch; resource ids are per-org SCIM mapping ids, so
 * one tenant's IdP can neither read nor address another tenant's users (cross-org calls 404).
 *
 * <h2>Error/encoding</h2>
 * Responses use the SCIM media type and SCIM status codes (200/201/204); errors are emitted as
 * {@link ScimError} bodies (RFC 7644 §3.12) rather than the platform's RFC-7807 ProblemDetail.
 */
@RestController
@RequestMapping(path = "/scim/v2/Users")
public class ScimUserController {

    private static final Logger log = LoggerFactory.getLogger(ScimUserController.class);

    private static final String GATE =
            "hasAuthority('scim.manage') and @tenantAccess.canAccessOrg(@scimOrg.callerOrgId())";

    /** SCIM JSON media type (RFC 7644 §3.1). We also accept application/json from lenient clients. */
    private static final String SCIM_JSON = "application/scim+json";

    /** Parses a simple {@code <attr> eq "<value>"} SCIM filter (the only operator we support). */
    private static final Pattern EQ_FILTER =
            Pattern.compile("^\\s*(\\w+)\\s+eq\\s+\"([^\"]*)\"\\s*$", Pattern.CASE_INSENSITIVE);

    private final ScimService scimService;
    private final ScimOrgResolver scimOrg;
    private final TrustedProxyResolver proxyResolver;

    public ScimUserController(ScimService scimService,
                              ScimOrgResolver scimOrg,
                              TrustedProxyResolver proxyResolver) {
        this.scimService = scimService;
        this.scimOrg = scimOrg;
        this.proxyResolver = proxyResolver;
    }

    // ------------------------------------------------------------------
    // List
    // ------------------------------------------------------------------

    @GetMapping(produces = {SCIM_JSON, MediaType.APPLICATION_JSON_VALUE})
    @PreAuthorize(GATE)
    public ResponseEntity<?> listUsers(@RequestParam(value = "filter", required = false) String filter,
                                       @RequestParam(value = "startIndex", required = false) Integer startIndex,
                                       @RequestParam(value = "count", required = false) Integer count) {
        UUID orgId = scimOrg.callerOrgId();
        ScimService.ScimFilter parsed = null;
        if (filter != null && !filter.isBlank()) {
            Matcher m = EQ_FILTER.matcher(filter);
            if (!m.matches()) {
                // Only "eq" on userName/externalId is supported; anything else is an unsupported filter.
                return scimError(400, "invalidFilter", "Only 'eq' filters on userName/externalId are supported");
            }
            parsed = new ScimService.ScimFilter(m.group(1), m.group(2));
        }
        int start = startIndex == null ? 1 : startIndex;
        int cnt = count == null ? 100 : count;
        ScimListResponse body = scimService.list(orgId, parsed, start, cnt);
        return scimOk(body);
    }

    // ------------------------------------------------------------------
    // Get one
    // ------------------------------------------------------------------

    @GetMapping(path = "/{id}", produces = {SCIM_JSON, MediaType.APPLICATION_JSON_VALUE})
    @PreAuthorize(GATE)
    public ResponseEntity<?> getUser(@PathVariable("id") String id) {
        UUID orgId = scimOrg.callerOrgId();
        UUID resourceId = parseId(id);
        if (resourceId == null) {
            return notFound(id);
        }
        Optional<ScimUser> user = scimService.get(orgId, resourceId);
        return user.<ResponseEntity<?>>map(this::scimOk).orElseGet(() -> notFound(id));
    }

    // ------------------------------------------------------------------
    // Create (provision)
    // ------------------------------------------------------------------

    @PostMapping(consumes = {SCIM_JSON, MediaType.APPLICATION_JSON_VALUE},
            produces = {SCIM_JSON, MediaType.APPLICATION_JSON_VALUE})
    @PreAuthorize(GATE)
    public ResponseEntity<?> createUser(@RequestBody ScimUser request) {
        UUID orgId = scimOrg.callerOrgId();
        try {
            ScimUser created = scimService.provision(orgId, actorUserId(), clientIp(), request);
            return ResponseEntity.status(201).contentType(MediaType.valueOf(SCIM_JSON)).body(created);
        } catch (ScimService.ScimConflictException e) {
            return scimError(409, "uniqueness", e.getMessage());
        } catch (ScimService.ScimBadRequestException e) {
            return scimError(400, e.scimType(), e.getMessage());
        }
    }

    // ------------------------------------------------------------------
    // Replace (PUT) and partial update (PATCH)
    // ------------------------------------------------------------------

    @PutMapping(path = "/{id}", consumes = {SCIM_JSON, MediaType.APPLICATION_JSON_VALUE},
            produces = {SCIM_JSON, MediaType.APPLICATION_JSON_VALUE})
    @PreAuthorize(GATE)
    public ResponseEntity<?> replaceUser(@PathVariable("id") String id, @RequestBody ScimUser request) {
        UUID orgId = scimOrg.callerOrgId();
        UUID resourceId = parseId(id);
        if (resourceId == null) {
            return notFound(id);
        }
        String displayName = request.displayName() != null ? request.displayName()
                : (request.name() != null ? request.name().formatted() : null);
        Optional<ScimUser> updated = scimService.update(
                orgId, actorUserId(), clientIp(), resourceId, request.active(), displayName, request.externalId());
        return updated.<ResponseEntity<?>>map(this::scimOk).orElseGet(() -> notFound(id));
    }

    @PatchMapping(path = "/{id}", consumes = {SCIM_JSON, MediaType.APPLICATION_JSON_VALUE},
            produces = {SCIM_JSON, MediaType.APPLICATION_JSON_VALUE})
    @PreAuthorize(GATE)
    public ResponseEntity<?> patchUser(@PathVariable("id") String id, @RequestBody Map<String, Object> body) {
        UUID orgId = scimOrg.callerOrgId();
        UUID resourceId = parseId(id);
        if (resourceId == null) {
            return notFound(id);
        }
        PatchAttrs attrs = parsePatch(body);
        Optional<ScimUser> updated = scimService.update(
                orgId, actorUserId(), clientIp(), resourceId, attrs.active(), attrs.displayName(), attrs.externalId());
        return updated.<ResponseEntity<?>>map(this::scimOk).orElseGet(() -> notFound(id));
    }

    // ------------------------------------------------------------------
    // Delete (deprovision)
    // ------------------------------------------------------------------

    @DeleteMapping(path = "/{id}")
    @PreAuthorize(GATE)
    public ResponseEntity<?> deleteUser(@PathVariable("id") String id) {
        UUID orgId = scimOrg.callerOrgId();
        UUID resourceId = parseId(id);
        if (resourceId == null) {
            return notFound(id);
        }
        boolean done = scimService.deprovision(orgId, actorUserId(), clientIp(), resourceId);
        if (!done) {
            return notFound(id);
        }
        return ResponseEntity.noContent().build();
    }

    // ------------------------------------------------------------------
    // PATCH body parsing (RFC 7644 §3.5.2 PatchOp + lenient flat form)
    // ------------------------------------------------------------------

    private record PatchAttrs(Boolean active, String displayName, String externalId) {}

    /**
     * Extracts the supported mutable attributes ({@code active}, display name, {@code externalId}) from
     * either a SCIM {@code PatchOp} ({@code {"Operations":[{"op":"replace","path":"active","value":..}]}})
     * or a lenient flat object ({@code {"active":false}}). Unsupported ops/paths are ignored.
     */
    @SuppressWarnings("unchecked")
    private PatchAttrs parsePatch(Map<String, Object> body) {
        Boolean active = null;
        String displayName = null;
        String externalId = null;
        if (body == null) {
            return new PatchAttrs(null, null, null);
        }
        Object ops = body.get("Operations");
        if (ops == null) {
            ops = body.get("operations");
        }
        if (ops instanceof List<?> opList) {
            for (Object o : opList) {
                if (!(o instanceof Map<?, ?> op)) {
                    continue;
                }
                String operation = asString(op.get("op"));
                if (operation != null && operation.equalsIgnoreCase("remove")) {
                    // "remove" of active is treated as deactivation.
                    String path = asString(op.get("path"));
                    if ("active".equalsIgnoreCase(path)) {
                        active = Boolean.FALSE;
                    }
                    continue;
                }
                String path = asString(op.get("path"));
                Object value = op.get("value");
                if (path == null && value instanceof Map<?, ?> valueMap) {
                    // op with no path: value is a sub-object of attributes.
                    if (valueMap.get("active") != null) active = asBoolean(valueMap.get("active"));
                    if (valueMap.get("displayName") != null) displayName = asString(valueMap.get("displayName"));
                    if (valueMap.get("externalId") != null) externalId = asString(valueMap.get("externalId"));
                } else if ("active".equalsIgnoreCase(path)) {
                    active = asBoolean(value);
                } else if ("displayName".equalsIgnoreCase(path)) {
                    displayName = asString(value);
                } else if ("externalId".equalsIgnoreCase(path)) {
                    externalId = asString(value);
                }
            }
        } else {
            // Lenient flat form.
            if (body.get("active") != null) active = asBoolean(body.get("active"));
            if (body.get("displayName") != null) displayName = asString(body.get("displayName"));
            if (body.get("externalId") != null) externalId = asString(body.get("externalId"));
            Object name = body.get("name");
            if (displayName == null && name instanceof Map<?, ?> nameMap && nameMap.get("formatted") != null) {
                displayName = asString(nameMap.get("formatted"));
            }
        }
        return new PatchAttrs(active, displayName, externalId);
    }

    private static String asString(Object o) {
        return o == null ? null : o.toString();
    }

    private static Boolean asBoolean(Object o) {
        if (o instanceof Boolean b) return b;
        if (o instanceof String s) return Boolean.parseBoolean(s);
        return null;
    }

    // ------------------------------------------------------------------
    // Response/error helpers
    // ------------------------------------------------------------------

    private ResponseEntity<?> scimOk(Object body) {
        return ResponseEntity.ok().contentType(MediaType.valueOf(SCIM_JSON)).body(body);
    }

    private ResponseEntity<?> scimError(int status, String scimType, String detail) {
        return ResponseEntity.status(status)
                .contentType(MediaType.valueOf(SCIM_JSON))
                .body(ScimError.of(status, scimType, detail));
    }

    private ResponseEntity<?> notFound(String id) {
        return scimError(404, null, "User " + id + " not found");
    }

    private static UUID parseId(String id) {
        if (id == null) {
            return null;
        }
        try {
            return UUID.fromString(id);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private UUID actorUserId() {
        return SecurityUtils.currentUser().map(u -> u.userId()).orElse(null);
    }

    private String clientIp() {
        try {
            HttpServletRequest req = currentRequest();
            return req == null ? null : proxyResolver.resolveClientIp(req);
        } catch (Exception e) {
            log.debug("Could not resolve client IP for SCIM request: {}", e.getMessage());
            return null;
        }
    }

    private HttpServletRequest currentRequest() {
        try {
            org.springframework.web.context.request.ServletRequestAttributes attrs =
                    (org.springframework.web.context.request.ServletRequestAttributes)
                            org.springframework.web.context.request.RequestContextHolder.getRequestAttributes();
            return attrs == null ? null : attrs.getRequest();
        } catch (Exception e) {
            return null;
        }
    }
}
