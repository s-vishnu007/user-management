package com.example.cp.sso;

import com.example.cp.audit.AuditOutcome;
import com.example.cp.audit.AuditWriter;
import com.example.cp.common.Ids;
import com.example.cp.mfa.MfaService;
import com.example.cp.orgs.OrgMember;
import com.example.cp.orgs.OrgMemberRepository;
import com.example.cp.orgs.OrgService;
import com.example.cp.orgs.Organization;
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
    private final OrgService orgService;
    private final MfaService mfaService;

    public SsoProvisioningService(UserRepository userRepo, OrgMemberRepository memberRepo,
                                  SsoIdentityRepository identityRepo, AuditWriter auditWriter,
                                  OrgService orgService, MfaService mfaService) {
        this.userRepo = userRepo;
        this.memberRepo = memberRepo;
        this.identityRepo = identityRepo;
        this.auditWriter = auditWriter;
        this.orgService = orgService;
        this.mfaService = mfaService;
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

    /**
     * Provisioning for the <em>global</em> (org-less) Google sign-in: there is no per-org
     * {@link SsoProvider} or domain allow-list, so identity is keyed purely on the verified email.
     *
     * <p>The caller has already enforced that Google asserted a verified email (unverified is blocked
     * upstream), so linking by email is safe here. An existing user simply logs in (no elevation). A
     * brand-new user is JIT-created (ACTIVE, email_verified=true, never super-admin) and given their
     * own organization as OWNER — mirroring password signup. All writes commit in one transaction.
     */
    @Transactional
    public ProvisionResult provisionGlobal(String email, String name, String ip, String maskedEmail) {
        Optional<User> existing = userRepo.findByEmail(email);
        if (existing.isPresent()) {
            User u = existing.get();
            // SECURITY (re-audit blocker): a global Google login must NEVER auto-link to a
            // pre-existing account by email alone — that would let anyone holding a Google identity for
            // a victim's address log straight into the victim's account. Only a genuinely SSO-only
            // account (no password, not a super-admin, ACTIVE, no MFA enrolled) may be resumed this way.
            // Everyone else must use their existing method (password / their org's SSO), which keeps the
            // password check, the super-admin boundary, and the MFA second factor intact.
            boolean ssoOnly = u.getPasswordHash() == null
                    && !u.isSuperAdmin()
                    && u.getStatus() == User.Status.ACTIVE
                    && !mfaService.isEnabled(u.getId());
            if (!ssoOnly) {
                auditWriter.record(u.getId(), null, "sso.login", "user", u.getId().toString(),
                        Map.of("email", maskedEmail, "via", "google", "reason", "existing-non-sso-account"),
                        ip, AuditOutcome.DENIED, false);
                throw new IllegalStateException("An account already exists for this email; sign in with your existing method");
            }
            auditWriter.record(u.getId(), null, "sso.login", "user", u.getId().toString(),
                    Map.of("email", maskedEmail, "via", "google"), ip, AuditOutcome.SUCCESS, false);
            return new ProvisionResult(u);
        }
        User user = userRepo.save(User.builder()
                .id(Ids.newId())
                .email(email)
                .fullName(name)
                .status(User.Status.ACTIVE)
                .superAdmin(false)
                .emailVerified(true)
                .createdAt(OffsetDateTime.now())
                .build());
        auditWriter.record(user.getId(), null, "user.created", "user", user.getId().toString(),
                Map.of("via", "google", "email", maskedEmail), ip, AuditOutcome.SUCCESS, false);

        Organization org = orgService.createOrgFromName(deriveOrgName(name, email), user.getId());
        auditWriter.record(user.getId(), org.getId(), "org.created", "organization", org.getId().toString(),
                Map.of("via", "google"), ip, AuditOutcome.SUCCESS, false);
        auditWriter.record(user.getId(), org.getId(), "sso.login", "user", user.getId().toString(),
                Map.of("email", maskedEmail, "via", "google"), ip, AuditOutcome.SUCCESS, false);
        return new ProvisionResult(user);
    }

    private static String deriveOrgName(String name, String email) {
        if (name != null && !name.isBlank()) {
            return name + "'s Organization";
        }
        String local = email;
        int at = email.indexOf('@');
        if (at > 0) {
            local = email.substring(0, at);
        }
        return local + "'s Organization";
    }

    private User jitCreateUser(String email, String name) {
        // The handler has already enforced the IdP email-verified gate before we get here, so mark the
        // JIT user verified — consistent with provisionGlobal, DevBootstrapAdmin, and migration 19's
        // grandfathering of SSO/JIT users (re-audit medium #6).
        User u = User.builder()
                .id(Ids.newId())
                .email(email)
                .fullName(name)
                .status(User.Status.ACTIVE)
                .superAdmin(false)
                .emailVerified(true)
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
