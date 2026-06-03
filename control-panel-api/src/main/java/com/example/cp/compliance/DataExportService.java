package com.example.cp.compliance;

import com.example.cp.apikeys.ApiKey;
import com.example.cp.apikeys.ApiKeyRepository;
import com.example.cp.audit.AuditLog;
import com.example.cp.audit.AuditLogRepository;
import com.example.cp.common.ApiException;
import com.example.cp.orgs.OrgMember;
import com.example.cp.orgs.OrgMemberRepository;
import com.example.cp.orgs.Organization;
import com.example.cp.orgs.OrganizationRepository;
import com.example.cp.subscriptions.Subscription;
import com.example.cp.subscriptions.SubscriptionRepository;
import com.example.cp.users.User;
import com.example.cp.users.UserRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Assembles a GDPR/CCPA data-subject access (right of access / portability) export.
 *
 * <p>Two shapes are produced:
 * <ul>
 *   <li><b>user export</b> ({@link #exportUser(UUID)}) — everything personal to ONE human: their
 *       profile, org memberships, SSO identities, API-key <i>metadata</i> for the orgs they belong
 *       to, and the audit rows where they are the actor.</li>
 *   <li><b>org export</b> ({@link #exportOrg(UUID)}) — the tenant's record: org profile, members,
 *       subscriptions, API-key metadata, SSO providers (config redacted), and audit rows scoped to
 *       the org.</li>
 * </ul>
 *
 * <p>The export is a machine-readable {@code Map} (serialised to JSON by the controller). It NEVER
 * includes secret material: password hashes, API-key hashes, SSO client secrets, and TOTP secrets
 * are excluded by construction (only metadata / non-secret fields are read).
 */
@Service
public class DataExportService {

    private static final int MAX_AUDIT_ROWS = 5000;

    private final UserRepository userRepository;
    private final OrganizationRepository organizationRepository;
    private final OrgMemberRepository orgMemberRepository;
    private final SubscriptionRepository subscriptionRepository;
    private final ApiKeyRepository apiKeyRepository;
    private final AuditLogRepository auditLogRepository;
    private final JdbcTemplate jdbc;

    public DataExportService(UserRepository userRepository,
                             OrganizationRepository organizationRepository,
                             OrgMemberRepository orgMemberRepository,
                             SubscriptionRepository subscriptionRepository,
                             ApiKeyRepository apiKeyRepository,
                             AuditLogRepository auditLogRepository,
                             JdbcTemplate jdbc) {
        this.userRepository = userRepository;
        this.organizationRepository = organizationRepository;
        this.orgMemberRepository = orgMemberRepository;
        this.subscriptionRepository = subscriptionRepository;
        this.apiKeyRepository = apiKeyRepository;
        this.auditLogRepository = auditLogRepository;
        this.jdbc = jdbc;
    }

    /** Right-of-access export for a single human data subject. */
    @Transactional(readOnly = true)
    public Map<String, Object> exportUser(UUID userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> ApiException.notFound("User not found"));

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("exportType", "user");
        out.put("generatedAt", OffsetDateTime.now().toString());
        out.put("subjectId", userId.toString());

        out.put("profile", userProfile(user));
        out.put("orgMemberships", membershipsForUser(userId));
        out.put("ssoIdentities", ssoIdentitiesForUser(userId));
        out.put("apiKeys", apiKeysForUserOrgs(userId));
        out.put("auditEvents", auditEventsForActor(userId));
        return out;
    }

    /** Tenant export: the org's record plus the personal data it holds. */
    @Transactional(readOnly = true)
    public Map<String, Object> exportOrg(UUID orgId) {
        Organization org = organizationRepository.findById(orgId)
                .orElseThrow(() -> ApiException.notFound("Organization not found"));

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("exportType", "org");
        out.put("generatedAt", OffsetDateTime.now().toString());
        out.put("subjectId", orgId.toString());

        Map<String, Object> profile = new LinkedHashMap<>();
        profile.put("id", org.getId().toString());
        profile.put("slug", org.getSlug());
        profile.put("name", org.getName());
        profile.put("status", org.getStatus() == null ? null : org.getStatus().name());
        profile.put("createdAt", str(org.getCreatedAt()));
        profile.put("updatedAt", str(org.getUpdatedAt()));
        out.put("profile", profile);

        out.put("members", membersOfOrg(orgId));
        out.put("subscriptions", subscriptionsOfOrg(orgId));
        out.put("apiKeys", apiKeysOfOrg(orgId));
        out.put("ssoProviders", ssoProvidersOfOrg(orgId));
        out.put("auditEvents", auditEventsForOrg(orgId));
        return out;
    }

    // ------------------------------------------------------------------
    // user-scoped sections
    // ------------------------------------------------------------------

    private Map<String, Object> userProfile(User user) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", user.getId().toString());
        m.put("email", user.getEmail());
        m.put("fullName", user.getFullName());
        m.put("status", user.getStatus() == null ? null : user.getStatus().name());
        m.put("superAdmin", user.isSuperAdmin());
        m.put("createdAt", str(user.getCreatedAt()));
        m.put("lastLoginAt", str(user.getLastLoginAt()));
        // Deliberately omitted: passwordHash, tokenVersion (internal/secret).
        return m;
    }

    private List<Map<String, Object>> membershipsForUser(UUID userId) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (OrgMember m : orgMemberRepository.findByUserId(userId)) {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("orgId", m.getOrgId().toString());
            organizationRepository.findById(m.getOrgId())
                    .ifPresent(o -> r.put("orgName", o.getName()));
            r.put("role", m.getRole() == null ? null : m.getRole().name());
            r.put("addedAt", str(m.getAddedAt()));
            rows.add(r);
        }
        return rows;
    }

    /**
     * SSO (provider, subject) identities linked to this user. {@code SsoIdentityRepository} (owned
     * by the sso bucket) exposes no by-user finder, so this read goes through {@link JdbcTemplate}
     * directly — no schema change, no cross-bucket edit.
     */
    private List<Map<String, Object>> ssoIdentitiesForUser(UUID userId) {
        return jdbc.query(
                "SELECT id, provider_id, subject, created_at FROM sso_identities WHERE user_id = ?",
                (rs, i) -> {
                    Map<String, Object> r = new LinkedHashMap<>();
                    r.put("id", rs.getString("id"));
                    r.put("providerId", rs.getString("provider_id"));
                    r.put("subject", rs.getString("subject"));
                    r.put("createdAt", rs.getString("created_at"));
                    return r;
                },
                userId);
    }

    /** API-key metadata for every org the user belongs to (never the hash). */
    private List<Map<String, Object>> apiKeysForUserOrgs(UUID userId) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (OrgMember m : orgMemberRepository.findByUserId(userId)) {
            rows.addAll(apiKeysOfOrg(m.getOrgId()));
        }
        return rows;
    }

    private List<Map<String, Object>> auditEventsForActor(UUID userId) {
        var page = auditLogRepository.search(
                null, userId, null, null, null, null,
                PageRequest.of(0, MAX_AUDIT_ROWS)); // ORDER BY occurred_at DESC is fixed in the native query
        return page.getContent().stream().map(this::auditRow).toList();
    }

    // ------------------------------------------------------------------
    // org-scoped sections
    // ------------------------------------------------------------------

    private List<Map<String, Object>> membersOfOrg(UUID orgId) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (OrgMember m : orgMemberRepository.findByOrgId(orgId)) {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("userId", m.getUserId().toString());
            userRepository.findById(m.getUserId()).ifPresent(u -> {
                r.put("email", u.getEmail());
                r.put("fullName", u.getFullName());
            });
            r.put("role", m.getRole() == null ? null : m.getRole().name());
            r.put("addedAt", str(m.getAddedAt()));
            rows.add(r);
        }
        return rows;
    }

    private List<Map<String, Object>> subscriptionsOfOrg(UUID orgId) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Subscription s : subscriptionRepository.findByOrgId(orgId)) {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("id", s.getId().toString());
            r.put("planId", s.getPlanId() == null ? null : s.getPlanId().toString());
            r.put("status", s.getStatus() == null ? null : s.getStatus().name());
            r.put("startsAt", str(s.getStartsAt()));
            r.put("endsAt", str(s.getEndsAt()));
            r.put("seats", s.getSeats());
            rows.add(r);
        }
        return rows;
    }

    private List<Map<String, Object>> apiKeysOfOrg(UUID orgId) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (ApiKey k : apiKeyRepository.findByOrgId(orgId)) {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("id", k.getId().toString());
            r.put("orgId", k.getOrgId().toString());
            r.put("name", k.getName());
            r.put("keyPrefix", k.getKeyPrefix());
            r.put("scopes", k.getScopesJson());
            r.put("createdAt", str(k.getCreatedAt()));
            r.put("lastUsedAt", str(k.getLastUsedAt()));
            r.put("revokedAt", str(k.getRevokedAt()));
            // Deliberately omitted: keyHash (secret).
            rows.add(r);
        }
        return rows;
    }

    /** SSO provider config for the org, with secret material redacted. */
    private List<Map<String, Object>> ssoProvidersOfOrg(UUID orgId) {
        return jdbc.query(
                "SELECT id, type, enabled, created_at FROM sso_providers WHERE org_id = ?",
                (rs, i) -> {
                    Map<String, Object> r = new LinkedHashMap<>();
                    r.put("id", rs.getString("id"));
                    r.put("type", rs.getString("type"));
                    r.put("enabled", rs.getBoolean("enabled"));
                    r.put("createdAt", rs.getString("created_at"));
                    // config_json / client_secret_enc deliberately omitted (may contain secrets).
                    return r;
                },
                orgId);
    }

    private List<Map<String, Object>> auditEventsForOrg(UUID orgId) {
        var page = auditLogRepository.searchForOrg(
                orgId, null, null, null, null, null, null,
                PageRequest.of(0, MAX_AUDIT_ROWS)); // ORDER BY occurred_at DESC is fixed in the native query
        return page.getContent().stream().map(this::auditRow).toList();
    }

    // ------------------------------------------------------------------
    // shared
    // ------------------------------------------------------------------

    private Map<String, Object> auditRow(AuditLog a) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("id", a.getId().toString());
        r.put("action", a.getAction());
        r.put("actorUserId", a.getActorUserId() == null ? null : a.getActorUserId().toString());
        r.put("actorOrgId", a.getActorOrgId() == null ? null : a.getActorOrgId().toString());
        r.put("targetType", a.getTargetType());
        r.put("targetId", a.getTargetId());
        r.put("payloadJson", a.getPayloadJson());
        r.put("ipAddress", a.getIpAddress());
        r.put("outcome", a.getOutcome() == null ? null : a.getOutcome().name());
        r.put("occurredAt", str(a.getOccurredAt()));
        return r;
    }

    private static String str(OffsetDateTime t) {
        return t == null ? null : t.toString();
    }
}
