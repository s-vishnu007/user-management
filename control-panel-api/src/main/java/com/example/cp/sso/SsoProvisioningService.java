package com.example.cp.sso;

import com.example.cp.audit.AuditOutcome;
import com.example.cp.audit.AuditWriter;
import com.example.cp.common.Ids;
import com.example.cp.orgs.OrgMember;
import com.example.cp.orgs.OrgMemberRepository;
import com.example.cp.users.User;
import com.example.cp.users.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Just-in-time SSO provisioning, extracted from {@link SsoSuccessHandler} so the
 * user/identity/membership writes (and their fail-closed audit rows) commit as ONE transaction.
 *
 * <p>Before this seam existed, {@code SsoSuccessHandler} was a transaction-script with no boundary:
 * each repository {@code save} committed independently, so a failure mid-sequence (e.g. the identity
 * insert after the user insert) left partially-provisioned state (a user with no identity binding, or
 * an identity with no membership). Wrapping the three writes in {@code @Transactional} here makes the
 * sequence all-or-nothing — a mid-sequence failure rolls back the whole provisioning and propagates
 * to the handler, which redirects to an error page rather than leaving orphaned rows.
 *
 * <p>The HTTP concerns (provider/subject/email extraction, the email-verified and domain allow-list
 * gates, cookie minting, and redirects) deliberately stay in {@code SsoSuccessHandler}: only the
 * durable writes move here.
 */
@Service
public class SsoProvisioningService {

    private final UserRepository userRepo;
    private final OrgMemberRepository memberRepo;
    private final SsoIdentityRepository identityRepo;
    private final AuditWriter auditWriter;

    public SsoProvisioningService(UserRepository userRepo, OrgMemberRepository memberRepo,
                                  SsoIdentityRepository identityRepo, AuditWriter auditWriter) {
        this.userRepo = userRepo;
        this.memberRepo = memberRepo;
        this.identityRepo = identityRepo;
        this.auditWriter = auditWriter;
    }

    /** The resolved/provisioned user for a successful SSO login (after the handler's gating passed). */
    public record ProvisionResult(User user) {}

    /**
     * Resolve the user for an authenticated SSO principal, JIT-creating it and/or linking the
     * (provider, subject) identity and org membership as needed — all in one transaction.
     *
     * <p>The caller (handler) has already enforced the email-verified and domain allow-list gates;
     * by the time we get here the (email, domain) is trusted to JIT/link.
     *
     * @param provider     the matched SSO provider (non-null; the handler errors out otherwise)
     * @param subject      the stable IdP subject (may be null for some assertions)
     * @param email        the asserted, gated email
     * @param name         the asserted display name (nullable)
     * @param orgId        the org to add membership in (nullable; no membership when null)
     * @param maskedEmail  a pre-masked email for audit payloads (never the raw value)
     * @param ip           the resolved client IP for audit rows
     */
    @Transactional
    public ProvisionResult provision(SsoProvider provider, String subject, String email, String name,
                                     UUID orgId, String maskedEmail, String ip) {
        // 1) Strong binding first: an existing (provider, subject) identity wins regardless of the
        //    asserted email, so a hostile IdP that changes the email it sends cannot hijack an account.
        User user = null;
        if (subject != null) {
            Optional<SsoIdentity> bound = identityRepo.findByProviderIdAndSubject(provider.getId(), subject);
            if (bound.isPresent()) {
                user = userRepo.findById(bound.get().getUserId()).orElse(null);
            }
        }

        if (user == null) {
            // 2) First login for this (provider, subject). Auto-link by email or JIT-create.
            Optional<User> existing = userRepo.findByEmail(email);
            boolean created = existing.isEmpty();
            // Never auto-promote to super_admin: jitCreateUser sets superAdmin=false; an existing
            // user keeps its current flags (we only link, never elevate).
            user = existing.orElseGet(() -> jitCreateUser(email, name));
            if (created) {
                // JIT provisioning must be audited (bypasses UserService.createUser).
                auditWriter.record(user.getId(), orgId, "user.created", "user", user.getId().toString(),
                        Map.of("via", "sso", "email", maskedEmail), ip, AuditOutcome.SUCCESS, false);
            }
            // 3) Persist the (provider, subject) -> user binding so future logins skip the email path.
            if (subject != null) {
                identityRepo.save(SsoIdentity.builder()
                        .id(Ids.newId())
                        .providerId(provider.getId())
                        .subject(subject)
                        .userId(user.getId())
                        .createdAt(OffsetDateTime.now())
                        .build());
                auditWriter.record(user.getId(), provider.getOrgId(), "sso.identity.linked", "sso_identity",
                        user.getId().toString(),
                        Map.of("provider_id", provider.getId().toString(), "via", "sso"), ip,
                        AuditOutcome.SUCCESS, false);
            }
        }

        // 4) Ensure org membership (idempotent).
        if (orgId != null && ensureMembership(orgId, user.getId())) {
            auditWriter.record(user.getId(), orgId, "org.member.added", "org_member", user.getId().toString(),
                    Map.of("via", "sso", "role", OrgMember.Role.MEMBER.name()), ip, AuditOutcome.SUCCESS, false);
        }
        // 5) The successful-login audit row commits atomically with the provisioning above.
        auditWriter.record(user.getId(), orgId, "sso.login", "user", user.getId().toString(),
                Map.of("email", maskedEmail), ip, AuditOutcome.SUCCESS, false);

        return new ProvisionResult(user);
    }

    private User jitCreateUser(String email, String name) {
        User u = User.builder()
                .id(Ids.newId())
                .email(email)
                .fullName(name)
                .status(User.Status.ACTIVE)
                .superAdmin(false)
                .createdAt(OffsetDateTime.now())
                .build();
        return userRepo.save(u);
    }

    /** @return true if a new membership was created, false if it already existed. */
    private boolean ensureMembership(UUID orgId, UUID userId) {
        if (memberRepo.findByOrgIdAndUserId(orgId, userId).isPresent()) return false;
        memberRepo.save(OrgMember.builder()
                .orgId(orgId)
                .userId(userId)
                .role(OrgMember.Role.MEMBER)
                .addedAt(OffsetDateTime.now())
                .build());
        return true;
    }
}
