package com.example.cp.scim;

import com.example.cp.audit.AuditOutcome;
import com.example.cp.audit.AuditWriter;
import com.example.cp.common.AuditContext;
import com.example.cp.common.Ids;
import com.example.cp.orgs.OrgMember;
import com.example.cp.orgs.OrgMemberRepository;
import com.example.cp.users.User;
import com.example.cp.users.UserRepository;
import com.example.cp.users.UserService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Business logic for SCIM 2.0 user provisioning/deprovisioning, always scoped to a single org (the
 * caller's API-key org, resolved by the controller). It owns the mapping between an IdP's user
 * namespace and the control-panel {@code users}/{@code org_members} tables, calling the platform's
 * {@link UserService} for the actual user lifecycle (create/deactivate) so SCIM never bypasses the
 * password policy, session-revocation, or audit semantics that bucket A enforces there.
 *
 * <p>Tenant isolation invariant: every public method takes the resolved {@code orgId} and only ever
 * touches {@link ScimUserMapping} rows for that org. A SCIM resource id is the mapping id (per-org),
 * so an IdP can never address — or even discover the existence of — another tenant's users.
 */
@Service
public class ScimService {

    private static final SecureRandom RNG = new SecureRandom();

    private final UserService userService;
    private final UserRepository userRepository;
    private final OrgMemberRepository orgMemberRepository;
    private final ScimUserMappingRepository mappingRepository;
    private final AuditWriter auditWriter;

    public ScimService(UserService userService,
                       UserRepository userRepository,
                       OrgMemberRepository orgMemberRepository,
                       ScimUserMappingRepository mappingRepository,
                       AuditWriter auditWriter) {
        this.userService = userService;
        this.userRepository = userRepository;
        this.orgMemberRepository = orgMemberRepository;
        this.mappingRepository = mappingRepository;
        this.auditWriter = auditWriter;
    }

    // ------------------------------------------------------------------
    // List / get
    // ------------------------------------------------------------------

    /**
     * Lists the org's SCIM users with optional {@code eq} filter on {@code userName} or
     * {@code externalId}, and 1-based {@code startIndex}/{@code count} pagination per RFC 7644.
     */
    @Transactional(readOnly = true)
    public ScimListResponse list(UUID orgId, ScimFilter filter, int startIndex, int count) {
        int safeStart = startIndex < 1 ? 1 : startIndex;
        int safeCount = count < 0 ? 0 : count;

        // An eq filter on userName/externalId resolves to at most one mapping in the org.
        if (filter != null && filter.attribute() != null) {
            Optional<ScimUserMapping> match = resolveFilter(orgId, filter);
            List<ScimUser> resources = match
                    .flatMap(m -> toScimUser(orgId, m))
                    .map(List::of)
                    .orElseGet(List::of);
            // Apply the 1-based window to the (0 or 1) results so pagination stays well-defined.
            List<ScimUser> page = (safeStart > resources.size() || safeCount == 0) ? List.of() : resources;
            return ScimListResponse.of(page, resources.size(), safeStart, page.size());
        }

        long total = mappingRepository.countByOrgId(orgId);
        if (safeCount == 0) {
            return ScimListResponse.of(List.of(), (int) total, safeStart, 0);
        }
        // P3: SCIM startIndex is a 1-based ABSOLUTE offset (RFC 7644 §3.4.2.4), not a page number. The
        // previous code treated it as page-aligned ((start-1)/count), so an unaligned startIndex returned
        // a shifted/overlapping window. Use OffsetPageRequest to honor the absolute offset exactly.
        int offset = safeStart - 1;
        Pageable pageable = new OffsetPageRequest(offset, Math.max(safeCount, 1),
                Sort.by(Sort.Direction.ASC, "createdAt"));
        Page<ScimUserMapping> mappings = mappingRepository.findByOrgId(orgId, pageable);
        List<ScimUser> resources = new ArrayList<>();
        for (ScimUserMapping m : mappings.getContent()) {
            // Skip mappings whose linked user has been GDPR-erased (DELETED): the resource is gone.
            toScimUser(orgId, m).ifPresent(resources::add);
        }
        return ScimListResponse.of(resources, (int) total, safeStart, resources.size());
    }

    /** Fetches one SCIM user by its (per-org) resource id; empty when not in this org. */
    @Transactional(readOnly = true)
    public Optional<ScimUser> get(UUID orgId, UUID resourceId) {
        return mappingRepository.findByIdAndOrgId(resourceId, orgId)
                .flatMap(m -> toScimUser(orgId, m));
    }

    // ------------------------------------------------------------------
    // Provision (create) / link
    // ------------------------------------------------------------------

    /**
     * Provisions a SCIM user in {@code orgId}. If no control-panel user exists for the requested
     * {@code userName} (email), one is created via {@link UserService#createUser}; otherwise the
     * existing user is linked. In both cases an org membership and a {@link ScimUserMapping} are
     * created so the IdP can subsequently address the resource.
     *
     * @throws ScimConflictException if a mapping for this email/externalId already exists in the org
     *                               (SCIM {@code uniqueness}).
     */
    @Transactional
    public ScimUser provision(UUID orgId, UUID actorUserId, String ip, ScimUser request) {
        String email = normalizeEmail(extractEmail(request));
        if (email == null || email.isBlank()) {
            throw new ScimBadRequestException("invalidValue", "userName (or a primary email) is required");
        }
        String externalId = trimToNull(request.externalId());

        // Reject a duplicate provision: same externalId already linked, or the email is already mapped
        // in this org. This is the SCIM uniqueness contract and keeps (org_id, external_id) clean.
        if (externalId != null && mappingRepository.findByOrgIdAndExternalId(orgId, externalId).isPresent()) {
            throw new ScimConflictException("A user with that externalId already exists");
        }

        boolean newUser;
        User user = userRepository.findByEmail(email).orElse(null);
        if (user == null) {
            // SCIM-provisioned users authenticate via the IdP (SSO), not a password: assign a strong
            // random password that satisfies the policy but is never disclosed. createUser also writes
            // the user.created audit row and enforces email uniqueness.
            user = userService.createUser(email, displayNameOf(request), randomPassword());
            newUser = true;
        } else {
            newUser = false;
            // If this user is already mapped in the org, the IdP is double-provisioning -> conflict.
            if (mappingRepository.existsByOrgIdAndUserId(orgId, user.getId())) {
                throw new ScimConflictException("A user with that userName already exists");
            }
            // Re-provisioning a previously deprovisioned (DELETE'd mapping → SUSPENDED) user: bring the
            // account back to ACTIVE so the re-linked SCIM resource reflects a usable user, unless the
            // request explicitly creates it inactive (handled below).
            if (user.getStatus() == User.Status.SUSPENDED) {
                user.setStatus(User.Status.ACTIVE);
                userRepository.save(user);
            }
        }

        // Ensure org membership (idempotent: an already-present membership is left as-is).
        if (orgMemberRepository.findByOrgIdAndUserId(orgId, user.getId()).isEmpty()) {
            orgMemberRepository.save(OrgMember.builder()
                    .orgId(orgId)
                    .userId(user.getId())
                    .role(OrgMember.Role.MEMBER)
                    .addedAt(OffsetDateTime.now())
                    .build());
        }

        // If the IdP set active=false on create, deprovision immediately after linking.
        boolean active = request.active() == null || request.active();
        if (!active) {
            userService.deactivate(user.getId());
        }

        ScimUserMapping mapping = ScimUserMapping.builder()
                .id(Ids.newId())
                .orgId(orgId)
                .externalId(externalId)
                .userId(user.getId())
                .createdAt(OffsetDateTime.now())
                .build();
        ScimUserMapping saved = saveMappingHandlingUniqueness(mapping);

        final User provisioned = user; // capture for the lambda (user is reassigned above)
        audit(actorUserId, orgId, "scim.user.provisioned", saved, ip, payload -> {
            payload.put("user_id", provisioned.getId().toString());
            payload.put("email", email);
            payload.put("external_id", externalId);
            payload.put("new_user", newUser);
        });
        // Suppress the mutating-endpoint interceptor's duplicate row: the fail-closed write above is the
        // canonical record for this provision (createUser's "user.created" AuditContext would otherwise
        // be emitted by the interceptor as a second, less specific row).
        AuditContext.markRecorded();

        return buildScimUser(saved, userRepository.findById(user.getId()).orElse(user));
    }

    // ------------------------------------------------------------------
    // Update (PUT / PATCH active + name)
    // ------------------------------------------------------------------

    /**
     * Partial ({@code PATCH}) update of a SCIM user: only the explicitly-supplied mutable attributes
     * ({@code active}, display name, {@code externalId}) are changed; a {@code null} argument means
     * "leave unchanged". Email ({@code userName}) is immutable here (changing it would be a
     * re-provision, not an update).
     */
    @Transactional
    public Optional<ScimUser> update(UUID orgId, UUID actorUserId, String ip, UUID resourceId,
                                     Boolean active, String displayName, String externalId) {
        return apply(orgId, actorUserId, ip, resourceId, active, displayName, externalId, false);
    }

    /**
     * Full-resource ({@code PUT}) replace of a SCIM user (RFC 7644 §3.5.1): attributes ABSENT from the
     * representation are reset to their defaults, not left as-is. {@code active} defaults to true,
     * {@code displayName} clears to null, and {@code externalId} clears to null when omitted.
     */
    @Transactional
    public Optional<ScimUser> replace(UUID orgId, UUID actorUserId, String ip, UUID resourceId,
                                      Boolean active, String displayName, String externalId) {
        // Full replace: an absent active means "true" (the SCIM default for a present resource).
        Boolean effectiveActive = active != null ? active : Boolean.TRUE;
        return apply(orgId, actorUserId, ip, resourceId, effectiveActive, displayName, externalId, true);
    }

    /**
     * Shared update engine. When {@code fullReplace} is true, a {@code null} {@code displayName} /
     * {@code externalId} clears the stored value (PUT semantics); otherwise {@code null} leaves it
     * unchanged (PATCH semantics).
     */
    private Optional<ScimUser> apply(UUID orgId, UUID actorUserId, String ip, UUID resourceId,
                                     Boolean active, String displayName, String externalId,
                                     boolean fullReplace) {
        ScimUserMapping mapping = mappingRepository.findByIdAndOrgId(resourceId, orgId).orElse(null);
        if (mapping == null) {
            return Optional.empty();
        }
        User user = userRepository.findById(mapping.getUserId()).orElse(null);
        if (user == null || user.getStatus() == User.Status.DELETED) {
            // A GDPR-erased user has no addressable SCIM resource.
            return Optional.empty();
        }

        boolean changed = false;

        // Display name: on full replace an absent (null) display name clears it; on PATCH null = no-op.
        if (fullReplace) {
            if (!java.util.Objects.equals(displayName, user.getFullName())) {
                user.setFullName(displayName);
                userRepository.save(user);
                changed = true;
            }
        } else if (displayName != null && !displayName.equals(user.getFullName())) {
            // updateProfile lives in UserService (bucket A); name-only updates are a simple setter here
            // on the user we own a reference to, persisted via the repository (no password/policy touch).
            user.setFullName(displayName);
            userRepository.save(user);
            changed = true;
        }

        // externalId: on full replace an absent (null) externalId clears it; on PATCH null = no-op.
        if (fullReplace && externalId == null) {
            if (mapping.getExternalId() != null) {
                mapping.setExternalId(null);
                mappingRepository.save(mapping);
                changed = true;
            }
        } else if (externalId != null) {
            String normalized = trimToNull(externalId);
            if (normalized != null && !normalized.equals(mapping.getExternalId())) {
                // A collision with another mapping's externalId in this org violates the
                // (org_id, external_id) unique constraint — surface it as a SCIM 409 uniqueness conflict
                // rather than a raw 500 (P3). Pre-check for a friendly message, then let the DB enforce.
                if (mappingRepository.findByOrgIdAndExternalId(orgId, normalized)
                        .filter(m -> !m.getId().equals(mapping.getId())).isPresent()) {
                    throw new ScimConflictException("A user with that externalId already exists");
                }
                mapping.setExternalId(normalized);
                try {
                    mappingRepository.saveAndFlush(mapping);
                } catch (org.springframework.dao.DataIntegrityViolationException e) {
                    throw new ScimConflictException("A user with that externalId already exists");
                }
                changed = true;
            }
        }

        if (active != null) {
            boolean currentlyActive = user.getStatus() == User.Status.ACTIVE;
            if (active && !currentlyActive) {
                user.setStatus(User.Status.ACTIVE);
                userRepository.save(user);
                changed = true;
                audit(actorUserId, orgId, "scim.user.reactivated", mapping, ip, p ->
                        p.put("user_id", user.getId().toString()));
            } else if (!active && currentlyActive) {
                // Deprovision through UserService so session revocation (token_version bump) fires.
                userService.deactivate(user.getId());
                changed = true;
                audit(actorUserId, orgId, "scim.user.deprovisioned", mapping, ip, p ->
                        p.put("user_id", user.getId().toString()));
            }
        }

        if (changed) {
            AuditContext.markRecorded();
        }
        User refreshed = userRepository.findById(user.getId()).orElse(user);
        return Optional.of(buildScimUser(mapping, refreshed));
    }

    // ------------------------------------------------------------------
    // Deprovision (DELETE)
    // ------------------------------------------------------------------

    /**
     * Deprovisions the SCIM user: the linked control-panel user is deactivated (status SUSPENDED +
     * token_version bump for session revocation) via {@link UserService#deactivate}, and the SCIM
     * mapping row is DELETED. Returns false if the resource is not in the caller's org (the controller
     * maps that to a 404).
     *
     * <p>The mapping is removed (not soft-retained) so SCIM DELETE is conformant: a subsequent GET on
     * the resource id returns 404, and re-provisioning the same {@code userName}/{@code externalId}
     * succeeds (re-linking the same — now reactivated — user) instead of hitting a permanent 409. The
     * user's SUSPENDED status remains the durable deprovisioned state and the {@code scim.user.*} audit
     * rows preserve the externalId correlation in their payloads.
     */
    @Transactional
    public boolean deprovision(UUID orgId, UUID actorUserId, String ip, UUID resourceId) {
        ScimUserMapping mapping = mappingRepository.findByIdAndOrgId(resourceId, orgId).orElse(null);
        if (mapping == null) {
            return false;
        }
        userService.deactivate(mapping.getUserId());
        // Capture identifiers for the audit payload BEFORE deleting the mapping row.
        UUID mappedUserId = mapping.getUserId();
        String externalId = mapping.getExternalId();
        audit(actorUserId, orgId, "scim.user.deprovisioned", mapping, ip, p -> {
            p.put("user_id", mappedUserId.toString());
            if (externalId != null) {
                p.put("external_id", externalId);
            }
        });
        mappingRepository.delete(mapping);
        AuditContext.markRecorded();
        return true;
    }

    // ------------------------------------------------------------------
    // Internal helpers
    // ------------------------------------------------------------------

    /**
     * Persist a new mapping, translating a DB unique-constraint violation (either
     * {@code (org_id, external_id)} or {@code (org_id, user_id)}) into a SCIM 409 uniqueness conflict
     * instead of letting it surface as a raw 500. Guards the check-then-insert race the in-memory
     * {@code existsBy*}/{@code findBy*} guards above cannot fully close under concurrency.
     */
    private ScimUserMapping saveMappingHandlingUniqueness(ScimUserMapping mapping) {
        try {
            return mappingRepository.saveAndFlush(mapping);
        } catch (org.springframework.dao.DataIntegrityViolationException e) {
            throw new ScimConflictException("A user with that userName or externalId already exists");
        }
    }

    private Optional<ScimUserMapping> resolveFilter(UUID orgId, ScimFilter filter) {
        String attr = filter.attribute();
        String value = filter.value();
        if (value == null) {
            return Optional.empty();
        }
        if ("externalid".equalsIgnoreCase(attr)) {
            return mappingRepository.findByOrgIdAndExternalId(orgId, value);
        }
        if ("username".equalsIgnoreCase(attr)) {
            return userRepository.findByEmail(normalizeEmail(value))
                    .flatMap(u -> mappingRepository.findByOrgIdAndUserId(orgId, u.getId()));
        }
        return Optional.empty();
    }

    private Optional<ScimUser> toScimUser(UUID orgId, ScimUserMapping mapping) {
        return userRepository.findById(mapping.getUserId())
                // A GDPR-erased (DELETED) user has no SCIM resource: treat the mapping as absent so GET
                // returns 404 and list omits it. SUSPENDED (deprovisioned) users still render with
                // active=false until truly deleted.
                .filter(u -> u.getStatus() != User.Status.DELETED)
                .map(u -> buildScimUser(mapping, u));
    }

    private ScimUser buildScimUser(ScimUserMapping mapping, User user) {
        boolean active = user.getStatus() == User.Status.ACTIVE;
        String fullName = user.getFullName();
        ScimName name = fullName == null ? null : new ScimName(fullName, null, null);
        return new ScimUser(
                List.of(ScimUser.SCHEMA_USER),
                mapping.getId().toString(),
                mapping.getExternalId(),
                user.getEmail(),
                name,
                fullName,
                List.of(ScimEmail.primaryWork(user.getEmail())),
                active,
                ScimUser.ScimMeta.forUser(mapping.getId().toString()));
    }

    private void audit(UUID actorUserId, UUID orgId, String action, ScimUserMapping mapping, String ip,
                       java.util.function.Consumer<Map<String, Object>> payloadFiller) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("org_id", orgId.toString());
        payload.put("scim_mapping_id", mapping.getId().toString());
        payloadFiller.accept(payload);
        // Fail-closed: provisioning/deprovisioning are identity-lifecycle events that must leave a
        // durable trail committed atomically with the change.
        auditWriter.record(actorUserId, orgId, action, "scim_user_mapping",
                mapping.getId().toString(), payload, ip, AuditOutcome.SUCCESS, true);
    }

    private static String extractEmail(ScimUser request) {
        if (request == null) {
            return null;
        }
        if (request.userName() != null && !request.userName().isBlank()) {
            return request.userName();
        }
        if (request.emails() != null) {
            // Prefer the primary email; otherwise the first present value.
            String firstValue = null;
            for (ScimEmail e : request.emails()) {
                if (e == null || e.value() == null || e.value().isBlank()) {
                    continue;
                }
                if (Boolean.TRUE.equals(e.primary())) {
                    return e.value();
                }
                if (firstValue == null) {
                    firstValue = e.value();
                }
            }
            return firstValue;
        }
        return null;
    }

    private static String displayNameOf(ScimUser request) {
        if (request == null) {
            return null;
        }
        if (request.displayName() != null && !request.displayName().isBlank()) {
            return request.displayName();
        }
        if (request.name() != null && request.name().formatted() != null && !request.name().formatted().isBlank()) {
            return request.name().formatted();
        }
        return null;
    }

    private static String normalizeEmail(String email) {
        return email == null ? null : email.trim();
    }

    private static String trimToNull(String s) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    /**
     * Generates a strong random password that satisfies {@code PasswordPolicy} (>=12 chars, mixed
     * case, digit, symbol). SCIM users authenticate via the IdP, so this value is intentionally never
     * surfaced — it just lets {@code UserService.createUser} create a user without a known password.
     */
    private static String randomPassword() {
        String upper = "ABCDEFGHJKLMNPQRSTUVWXYZ";
        String lower = "abcdefghijkmnpqrstuvwxyz";
        String digit = "23456789";
        String symbol = "!@#$%^&*-_=+";
        String all = upper + lower + digit + symbol;
        StringBuilder sb = new StringBuilder();
        sb.append(upper.charAt(RNG.nextInt(upper.length())));
        sb.append(lower.charAt(RNG.nextInt(lower.length())));
        sb.append(digit.charAt(RNG.nextInt(digit.length())));
        sb.append(symbol.charAt(RNG.nextInt(symbol.length())));
        for (int i = 0; i < 28; i++) {
            sb.append(all.charAt(RNG.nextInt(all.length())));
        }
        return sb.toString();
    }

    /** A parsed SCIM {@code eq} filter, e.g. {@code userName eq "alice@example.com"}. */
    public record ScimFilter(String attribute, String value) {}

    /** SCIM 4xx the controller renders as a {@link ScimError} body (400 invalidValue by default). */
    public static class ScimBadRequestException extends RuntimeException {
        private final String scimType;
        public ScimBadRequestException(String scimType, String message) {
            super(message);
            this.scimType = scimType;
        }
        public String scimType() { return scimType; }
    }

    /** SCIM 409 uniqueness conflict the controller renders as a {@link ScimError} body. */
    public static class ScimConflictException extends RuntimeException {
        public ScimConflictException(String message) {
            super(message);
        }
    }
}
