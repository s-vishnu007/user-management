package com.example.cp.sso;

import com.example.cp.common.Ids;
import com.example.cp.orgs.OrgMember;
import com.example.cp.orgs.OrgMemberRepository;
import com.example.cp.users.User;
import com.example.cp.users.UserRepository;
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

    @Value("${app.ui.base-url:http://localhost:5173}")
    private String uiBaseUrl;

    public SsoSuccessHandler(UserRepository userRepo, OrgMemberRepository memberRepo) {
        this.userRepo = userRepo;
        this.memberRepo = memberRepo;
    }

    @Override
    public void onAuthenticationSuccess(HttpServletRequest request, HttpServletResponse response, Authentication authentication) throws IOException, ServletException {
        String email = extractEmail(authentication);
        String name = extractName(authentication);
        UUID orgId = extractOrgId(request);

        if (email == null) {
            log.warn("SSO success without an email claim — redirecting to UI without provisioning");
            response.sendRedirect(uiBaseUrl);
            return;
        }
        Optional<User> existing = userRepo.findByEmail(email);
        User user = existing.orElseGet(() -> jitCreateUser(email, name));
        if (orgId != null) {
            ensureMembership(orgId, user.getId());
        }
        response.sendRedirect(uiBaseUrl + "?sso=success");
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

    private void ensureMembership(UUID orgId, UUID userId) {
        if (memberRepo.findByOrgIdAndUserId(orgId, userId).isPresent()) return;
        memberRepo.save(OrgMember.builder()
                .orgId(orgId)
                .userId(userId)
                .role(OrgMember.Role.MEMBER)
                .addedAt(OffsetDateTime.now())
                .build());
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
