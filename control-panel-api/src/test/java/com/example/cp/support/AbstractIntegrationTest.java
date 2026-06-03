package com.example.cp.support;

import com.example.cp.apikeys.ApiKey;
import com.example.cp.apikeys.ApiKeyRepository;
import com.example.cp.apikeys.ApiKeyService;
import com.example.cp.common.AuthenticatedUser;
import com.example.cp.common.Ids;
import com.example.cp.orgs.OrgMember;
import com.example.cp.orgs.OrgMemberRepository;
import com.example.cp.orgs.Organization;
import com.example.cp.orgs.OrganizationRepository;
import com.example.cp.plans.Plan;
import com.example.cp.plans.PlanRepository;
import com.example.cp.rbac.Permission;
import com.example.cp.rbac.PermissionRepository;
import com.example.cp.rbac.Role;
import com.example.cp.rbac.RolePermission;
import com.example.cp.rbac.RolePermissionRepository;
import com.example.cp.rbac.RoleRepository;
import com.example.cp.rbac.UserRole;
import com.example.cp.rbac.UserRoleRepository;
import com.example.cp.subscriptions.Subscription;
import com.example.cp.subscriptions.SubscriptionRepository;
import com.example.cp.users.User;
import com.example.cp.users.UserRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Base class for control-panel-api integration tests.
 *
 * <p>Boots the full Spring context with {@code @AutoConfigureMockMvc} against the Testcontainers
 * Postgres declared in {@code application-test.yml} (jdbc:tc URL). It exposes a {@link MockMvc},
 * the shared {@link ObjectMapper}, all Spring Data repositories needed for seeding, and a set of
 * reusable seed + auth helpers so concrete tests can construct realistic multi-tenant fixtures and
 * exercise either the real HTTP auth chain (login JWT) or method-security SpEL directly (injected
 * {@link AuthenticatedUser} principal, human or api-key variant).
 *
 * <p>The Liquibase seed (changesets 02/13) already inserts the system roles
 * ({@code SUPER_ADMIN, ORG_OWNER, ORG_ADMIN, ORG_MEMBER, VIEWER}) and the full permission catalog,
 * so the RBAC helpers here reuse those rows rather than recreating them. Tests are NOT transactional
 * by default (MockMvc requests run in their own transactions); use unique slugs/emails per test
 * (e.g. via {@link #rnd()}) to avoid collisions, or clean up explicitly.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
public abstract class AbstractIntegrationTest {

    public static final String DEFAULT_PASSWORD = "Passw0rd!secret";

    @Autowired protected MockMvc mockMvc;
    @Autowired protected ObjectMapper objectMapper;
    @Autowired protected PasswordEncoder passwordEncoder;

    @Autowired protected OrganizationRepository organizationRepository;
    @Autowired protected UserRepository userRepository;
    @Autowired protected OrgMemberRepository orgMemberRepository;
    @Autowired protected PlanRepository planRepository;
    @Autowired protected SubscriptionRepository subscriptionRepository;
    @Autowired protected ApiKeyRepository apiKeyRepository;
    @Autowired protected ApiKeyService apiKeyService;

    @Autowired protected RoleRepository roleRepository;
    @Autowired protected PermissionRepository permissionRepository;
    @Autowired protected RolePermissionRepository rolePermissionRepository;
    @Autowired protected UserRoleRepository userRoleRepository;

    // ------------------------------------------------------------------
    // Seed helpers
    // ------------------------------------------------------------------

    /** Short random suffix for collision-free slugs / emails within a test run. */
    protected String rnd() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    /** Seeds an ACTIVE {@link Organization} with the given slug + name. */
    protected Organization seedOrg(String slug, String name) {
        OffsetDateTime now = OffsetDateTime.now();
        Organization o = Organization.builder()
                .id(Ids.newId())
                .slug(slug)
                .name(name)
                .status(Organization.Status.ACTIVE)
                .createdAt(now)
                .updatedAt(now)
                .build();
        return organizationRepository.save(o);
    }

    /** Convenience: ACTIVE org with a generated unique slug derived from {@code namePrefix}. */
    protected Organization seedOrg(String namePrefix) {
        String slug = (namePrefix.toLowerCase().replaceAll("[^a-z0-9]", "") + "-" + rnd());
        return seedOrg(slug, namePrefix);
    }

    /**
     * Seeds an ACTIVE {@link User} with a bcrypt-hashed {@link #DEFAULT_PASSWORD} and the given
     * super-admin flag. Email must be unique (CITEXT unique column).
     */
    protected User seedUser(String email, String fullName, boolean superAdmin) {
        return seedUser(email, fullName, DEFAULT_PASSWORD, superAdmin);
    }

    /** Seeds an ACTIVE {@link User} with a bcrypt hash of {@code rawPassword} and super-admin flag. */
    protected User seedUser(String email, String fullName, String rawPassword, boolean superAdmin) {
        User u = User.builder()
                .id(Ids.newId())
                .email(email)
                .fullName(fullName)
                .passwordHash(passwordEncoder.encode(rawPassword))
                .status(User.Status.ACTIVE)
                .superAdmin(superAdmin)
                .tokenVersion(0L)
                .createdAt(OffsetDateTime.now())
                .build();
        return userRepository.save(u);
    }

    /** Adds {@code userId} to {@code orgId} as an {@link OrgMember} with the given role. */
    protected OrgMember addOrgMember(UUID orgId, UUID userId, OrgMember.Role role) {
        OrgMember m = OrgMember.builder()
                .orgId(orgId)
                .userId(userId)
                .role(role)
                .addedAt(OffsetDateTime.now())
                .build();
        return orgMemberRepository.save(m);
    }

    /**
     * Grants a seeded system role (by its {@code code}, e.g. {@code "ORG_ADMIN"} or
     * {@code "SUPER_ADMIN"}) to a user, scoped to {@code orgId} (pass {@code null} for a global/
     * platform-wide assignment). This is what drives {@link com.example.cp.rbac.AuthoritiesLoader}
     * /{@link com.example.cp.rbac.PermissionService} so a JWT obtained via login carries the
     * matching authority codes.
     */
    protected UserRole grantRole(UUID userId, String roleCode, UUID orgId) {
        Role role = roleRepository.findByCode(roleCode)
                .orElseThrow(() -> new IllegalStateException("Seed role not found: " + roleCode));
        UserRole ur = UserRole.builder()
                .userId(userId)
                .roleId(role.getId())
                .orgId(orgId)
                .build();
        return userRoleRepository.save(ur);
    }

    /** Looks up a seeded {@link Plan} by code (e.g. {@code "pro"}); fails if absent. */
    protected Plan seedPlan(String code) {
        return planRepository.findByCode(code)
                .orElseThrow(() -> new IllegalStateException("Seed plan not found: " + code));
    }

    /**
     * Creates a brand-new active {@link Plan} with a unique code (useful when a test needs an
     * isolated plan rather than the Liquibase-seeded starter/pro/enterprise rows).
     */
    protected Plan seedNewPlan(String code, int defaultTtlDays) {
        Plan p = Plan.builder()
                .id(Ids.newId())
                .code(code)
                .name(code)
                .description(code)
                .tier(code)
                .active(true)
                .defaultTtlDays(defaultTtlDays)
                .createdAt(OffsetDateTime.now())
                .build();
        return planRepository.save(p);
    }

    /** Seeds an ACTIVE {@link Subscription} for {@code orgId} on {@code planId} (1-year window). */
    protected Subscription seedSubscription(UUID orgId, UUID planId) {
        OffsetDateTime now = OffsetDateTime.now();
        Subscription s = Subscription.builder()
                .id(Ids.newId())
                .orgId(orgId)
                .planId(planId)
                .status(Subscription.Status.ACTIVE)
                .startsAt(now)
                .endsAt(now.plusYears(1))
                .seats(10)
                .notes("seeded by AbstractIntegrationTest")
                .createdAt(now)
                .build();
        return subscriptionRepository.save(s);
    }

    /**
     * Creates an API key bound to {@code orgId} with the given scopes (must be within
     * {@code app.api-keys.creatable-scopes}; default allow-list is
     * {@code usage.ingest, usage.read, license.read}). Returns the service result carrying both the
     * persisted {@link ApiKey} and the one-time plaintext key (use the plaintext as the
     * {@code X-Api-Key}/bearer credential in HTTP tests).
     */
    protected ApiKeyService.CreateResult seedApiKey(UUID orgId, String name, Set<String> scopes) {
        return apiKeyService.create(orgId, name, scopes);
    }

    // ------------------------------------------------------------------
    // Auth helpers
    // ------------------------------------------------------------------

    /**
     * Performs a real {@code POST /api/v1/auth/login} and returns the issued session JWT
     * (the {@code accessToken}). Drives the full filter chain on subsequent calls when sent as
     * {@code Authorization: Bearer <token>}. Asserts a 200.
     */
    protected String loginAndGetToken(String email, String rawPassword) throws Exception {
        String body = objectMapper.writeValueAsString(new LoginBody(email, rawPassword));
        String response = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType("application/json")
                        .content(body))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode node = objectMapper.readTree(response);
        return node.get("accessToken").asText();
    }

    /** {@code "Bearer " + token} convenience for the {@code Authorization} header. */
    protected String bearer(String token) {
        return "Bearer " + token;
    }

    /**
     * MockMvc {@link RequestPostProcessor} that injects a HUMAN {@link AuthenticatedUser} principal
     * directly into the security context (no token, no filter chain), so method-security /
     * {@code @tenantAccess} SpEL can be exercised in isolation. Authorities are mirrored into the
     * {@link GrantedAuthority} collection exactly as {@link com.example.cp.auth.JwtAuthFilter} does.
     */
    protected RequestPostProcessor asUser(User user, String... authorities) {
        return asUser(user.getId(), user.getEmail(), user.isSuperAdmin(), authorities);
    }

    /** Human principal from explicit fields (apiKey=false, apiKeyOrgId=null). */
    protected RequestPostProcessor asUser(UUID userId, String email, boolean superAdmin, String... authorities) {
        Set<String> auth = new LinkedHashSet<>(List.of(authorities));
        Collection<GrantedAuthority> granted = toGranted(auth, superAdmin);
        AuthenticatedUser principal = new AuthenticatedUser(userId, email, superAdmin, auth, granted);
        return authentication(new com.example.cp.support.TestPrincipalToken(principal, granted));
    }

    /** Super-admin human principal (the only global bypass in {@code TenantAccessChecker}). */
    protected RequestPostProcessor asSuperAdmin(User user) {
        return asUser(user.getId(), user.getEmail(), true);
    }

    /**
     * MockMvc {@link RequestPostProcessor} that injects an API-KEY {@link AuthenticatedUser}
     * principal bound to {@code apiKeyOrgId} (apiKey=true). Mirrors the principal that
     * {@code ApiKeyAuthFilter} builds, so {@code @tenantAccess} api-key paths (org-binding equality)
     * can be exercised without minting/sending a real key.
     */
    protected RequestPostProcessor asApiKey(UUID apiKeyOrgId, String... scopes) {
        Set<String> auth = new LinkedHashSet<>(List.of(scopes));
        Collection<GrantedAuthority> granted = toGranted(auth, false);
        AuthenticatedUser principal = new AuthenticatedUser(
                null, "api-key@" + apiKeyOrgId, false, auth, granted, true, apiKeyOrgId);
        return authentication(new com.example.cp.support.TestPrincipalToken(principal, granted));
    }

    private Collection<GrantedAuthority> toGranted(Set<String> authorities, boolean superAdmin) {
        List<GrantedAuthority> out = new ArrayList<>();
        for (String a : authorities) {
            out.add(new SimpleGrantedAuthority(a));
        }
        if (superAdmin) {
            out.add(new SimpleGrantedAuthority("SUPER_ADMIN"));
        }
        return out;
    }

    private record LoginBody(String email, String password) {}
}
