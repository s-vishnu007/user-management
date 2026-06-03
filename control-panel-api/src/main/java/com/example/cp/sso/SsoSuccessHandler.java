package com.example.cp.sso;

import com.example.cp.audit.AuditOutcome;
import com.example.cp.audit.AuditWriter;
import com.example.cp.common.Ids;
import com.example.cp.common.TrustedProxyResolver;
import com.example.cp.orgs.OrgMember;
import com.example.cp.orgs.OrgMemberRepository;
import com.example.cp.users.User;
import com.example.cp.users.UserRepository;

import java.util.Map;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.security.saml2.provider.service.authentication.Saml2AuthenticatedPrincipal;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

@Component
public class SsoSuccessHandler implements AuthenticationSuccessHandler {

    private static final Logger log = LoggerFactory.getLogger(SsoSuccessHandler.class);

    private final UserRepository userRepo;
    private final OrgMemberRepository memberRepo;
    private final AuditWriter auditWriter;
    private final TrustedProxyResolver trustedProxyResolver;

    @Value("${app.ui.base-url:http://localhost:5173}")
    private String uiBaseUrl;

    public SsoSuccessHandler(UserRepository userRepo, OrgMemberRepository memberRepo,
                             AuditWriter auditWriter, TrustedProxyResolver trustedProxyResolver) {
        this.userRepo = userRepo;
        this.memberRepo = memberRepo;
        this.auditWriter = auditWriter;
        this.trustedProxyResolver = trustedProxyResolver;
    }

    @Override
    public void onAuthenticationSuccess(HttpServletRequest request, HttpServletResponse response, Authentication authentication) throws IOException, ServletException {
        String email = extractEmail(authentication);
        String name = extractName(authentication);
        UUID orgId = extractOrgId(request);
        String ip = trustedProxyResolver.resolveClientIp(request);

        if (email == null) {
            log.warn("SSO success without an email claim — redirecting to UI without provisioning");
            auditWriter.record(null, orgId, "sso.login", "sso", null,
                    Map.of("reason", "missing-email"), ip, AuditOutcome.DENIED, false);
            response.sendRedirect(uiBaseUrl);
            return;
        }
        // OIDC: never provision/login on an unverified email (account-takeover guard, finding #47).
        if (isEmailExplicitlyUnverified(authentication)) {
            log.warn("SSO success with email_verified=false for {} — refusing to provision/login", mask(email));
            auditWriter.record(null, orgId, "sso.login", "sso", null,
                    Map.of("reason", "email-not-verified", "email", mask(email)), ip, AuditOutcome.DENIED, false);
            response.sendRedirect(uiBaseUrl + "?sso=unverified");
            return;
        }

        Optional<User> existing = userRepo.findByEmail(email);
        boolean created = existing.isEmpty();
        User user = existing.orElseGet(() -> jitCreateUser(email, name));
        if (created) {
            // JIT provisioning must be audited (finding #5) since it bypasses UserService.createUser.
            auditWriter.record(user.getId(), orgId, "user.created", "user", user.getId().toString(),
                    Map.of("via", "sso", "email", mask(email)), ip, AuditOutcome.SUCCESS, false);
        }
        if (orgId != null && ensureMembership(orgId, user.getId())) {
            auditWriter.record(user.getId(), orgId, "org.member.added", "org_member", user.getId().toString(),
                    Map.of("via", "sso", "role", OrgMember.Role.MEMBER.name()), ip, AuditOutcome.SUCCESS, false);
        }
        auditWriter.record(user.getId(), orgId, "sso.login", "user", user.getId().toString(),
                Map.of("email", mask(email)), ip, AuditOutcome.SUCCESS, false);
        response.sendRedirect(uiBaseUrl + "?sso=success");
    }

    /** True only when the IdP explicitly asserts email_verified=false (OIDC). Absent claim => not blocked. */
    private boolean isEmailExplicitlyUnverified(Authentication auth) {
        if (auth.getPrincipal() instanceof OAuth2User o) {
            Object v = o.getAttributes().get("email_verified");
            if (v instanceof Boolean b) return !b;
            if (v instanceof String s) return "false".equalsIgnoreCase(s.trim());
        }
        return false;
    }

    private static String mask(String email) {
        if (email == null) return null;
        int at = email.indexOf('@');
        if (at <= 1) return "***" + (at >= 0 ? email.substring(at) : "");
        return email.charAt(0) + "***" + email.substring(at);
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

    private String extractEmail(Authentication auth) {
        Object principal = auth.getPrincipal();
        if (principal instanceof OAuth2User o) {
            Object v = o.getAttributes().getOrDefault("email", o.getName());
            return v == null ? null : v.toString();
        }
        if (principal instanceof Saml2AuthenticatedPrincipal s) {
            Object v = firstNonNull(s.getFirstAttribute("email"), s.getFirstAttribute("mail"), s.getName());
            return v == null ? null : v.toString();
        }
        return auth.getName();
    }

    private String extractName(Authentication auth) {
        Object principal = auth.getPrincipal();
        if (principal instanceof OAuth2User o) {
            Object v = o.getAttributes().get("name");
            return v == null ? null : v.toString();
        }
        if (principal instanceof Saml2AuthenticatedPrincipal s) {
            Object v = firstNonNull(s.getFirstAttribute("displayName"), s.getFirstAttribute("name"));
            return v == null ? null : v.toString();
        }
        return null;
    }

    private UUID extractOrgId(HttpServletRequest req) {
        String orgIdAttr = (String) req.getSession().getAttribute("sso.orgId");
        if (orgIdAttr == null) return null;
        try { return UUID.fromString(orgIdAttr); } catch (Exception e) { return null; }
    }

    @SafeVarargs
    private static <T> T firstNonNull(T... values) {
        for (T v : values) if (v != null) return v;
        return null;
    }
}
