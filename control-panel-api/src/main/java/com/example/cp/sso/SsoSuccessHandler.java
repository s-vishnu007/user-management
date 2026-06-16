package com.example.cp.sso;

import com.example.cp.audit.AuditOutcome;
import com.example.cp.audit.AuditWriter;
import com.example.cp.auth.SessionTokenService;
import com.example.cp.common.TrustedProxyResolver;
import com.example.cp.rbac.AuthoritiesLoader;
import com.example.cp.users.User;

import java.util.Map;
import java.util.Set;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.security.saml2.provider.service.authentication.Saml2AuthenticatedPrincipal;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.UUID;

@Component
public class SsoSuccessHandler implements AuthenticationSuccessHandler {

    private static final Logger log = LoggerFactory.getLogger(SsoSuccessHandler.class);

    /** Session cookie name shared with {@code JwtAuthFilter}, which also accepts it as the bearer token. */
    public static final String SESSION_COOKIE = "cp_session";

    private final SsoProviderRepository providerRepo;
    private final SsoIdentityRepository identityRepo;
    private final SsoProvisioningService provisioningService;
    private final AuditWriter auditWriter;
    private final TrustedProxyResolver trustedProxyResolver;
    private final SessionTokenService sessionTokenService;
    private final AuthoritiesLoader authoritiesLoader;

    @Value("${app.ui.base-url:http://localhost:5173}")
    private String uiBaseUrl;

    /**
     * Whether the {@code cp_session} cookie carries the {@code Secure} attribute. Defaults to true
     * (production posture: cookie only sent over HTTPS); may be set to false for local http dev.
     */
    @Value("${app.auth.cookie-secure:true}")
    private boolean cookieSecure;

    public SsoSuccessHandler(SsoProviderRepository providerRepo, SsoIdentityRepository identityRepo,
                             SsoProvisioningService provisioningService,
                             AuditWriter auditWriter, TrustedProxyResolver trustedProxyResolver,
                             SessionTokenService sessionTokenService, AuthoritiesLoader authoritiesLoader) {
        this.providerRepo = providerRepo;
        this.identityRepo = identityRepo;
        this.provisioningService = provisioningService;
        this.auditWriter = auditWriter;
        this.trustedProxyResolver = trustedProxyResolver;
        this.sessionTokenService = sessionTokenService;
        this.authoritiesLoader = authoritiesLoader;
    }

    @Override
    public void onAuthenticationSuccess(HttpServletRequest request, HttpServletResponse response, Authentication authentication) throws IOException, ServletException {
        String email = extractEmail(authentication);
        String name = extractName(authentication);
        UUID orgId = extractOrgId(request);
        String ip = trustedProxyResolver.resolveClientIp(request);

        // Resolve the matched provider from the registration id baked into the authentication.
        // The (provider, subject) pair — never bare email — is the durable identity key (#48).
        SsoProvider provider = resolveProvider(authentication);
        String subject = extractSubject(authentication);

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

        // Global (org-less) Google sign-in has no per-org provider or domain allow-list: identity is
        // the (already-verified) Google email. It takes its own provisioning path that mints the user
        // their own organization, instead of the per-org provider gates below.
        if ("google".equals(registrationId(authentication))) {
            // Global Google links by verified email, so require a POSITIVE email_verified=true
            // assertion (not merely "not explicitly false") before provisioning (re-audit high #2).
            if (!isEmailVerifiedTrue(authentication)) {
                log.warn("Global Google sign-in for {} without a positive email_verified assertion — refusing",
                        mask(email));
                auditWriter.record(null, null, "sso.login", "sso", "google",
                        Map.of("reason", "email-not-verified", "email", mask(email)), ip, AuditOutcome.DENIED, false);
                response.sendRedirect(uiBaseUrl + "?sso=unverified");
                return;
            }
            User googleUser;
            try {
                googleUser = provisioningService.provisionGlobal(email, name, ip, mask(email)).user();
            } catch (RuntimeException e) {
                log.warn("Global Google provisioning failed for {} — no partial state committed: {}",
                        mask(email), e.toString());
                auditWriter.record(null, null, "sso.login", "sso", "google",
                        Map.of("reason", "provisioning-failed", "email", mask(email)), ip, AuditOutcome.DENIED, false);
                response.sendRedirect(uiBaseUrl + "?sso=error");
                return;
            }
            issueSessionCookie(response, googleUser);
            response.sendRedirect(uiBaseUrl + "?sso=success");
            return;
        }

        // 1) Strong binding: an existing (provider, subject) identity wins regardless of the asserted
        //    email, so a hostile IdP that changes the email it sends cannot hijack another account.
        //    This is a READ here purely to decide whether the new-binding gates below apply; the
        //    authoritative resolution happens transactionally inside SsoProvisioningService.
        boolean alreadyBound = false;
        if (provider != null && subject != null) {
            alreadyBound = identityRepo.findByProviderIdAndSubject(provider.getId(), subject).isPresent();
        }

        if (!alreadyBound) {
            // 2) First login for this (provider, subject). Auto-linking by email and JIT provisioning
            //    are gated on the provider's verified-domain allow-list (#40); deny otherwise.
            if (provider == null) {
                log.warn("SSO success could not be matched to a provider — refusing to provision/login");
                auditWriter.record(null, orgId, "sso.login", "sso", null,
                        Map.of("reason", "unknown-provider", "email", mask(email)), ip, AuditOutcome.DENIED, false);
                response.sendRedirect(uiBaseUrl + "?sso=error");
                return;
            }
            if (!emailDomainAllowed(provider, email)) {
                log.warn("SSO email domain not in provider {} allow-list for {} — refusing to provision/login",
                        provider.getId(), mask(email));
                auditWriter.record(null, provider.getOrgId(), "sso.login", "sso", provider.getId().toString(),
                        Map.of("reason", "email-domain-not-allowed", "email", mask(email)), ip, AuditOutcome.DENIED, false);
                response.sendRedirect(uiBaseUrl + "?sso=domain");
                return;
            }
        }

        // 3) Provision atomically: the user create/link, the (provider, subject) identity binding, the
        //    org membership, and their audit rows all commit in one transaction. A mid-sequence
        //    failure rolls back the whole provisioning instead of leaving partial state.
        User user;
        try {
            user = provisioningService.provision(provider, subject, email, name, orgId, mask(email), ip)
                    .user();
        } catch (RuntimeException e) {
            log.warn("SSO provisioning failed for {} — no partial state committed: {}", mask(email), e.toString());
            auditWriter.record(null, orgId, "sso.login", "sso",
                    provider != null ? provider.getId().toString() : null,
                    Map.of("reason", "provisioning-failed", "email", mask(email)), ip, AuditOutcome.DENIED, false);
            response.sendRedirect(uiBaseUrl + "?sso=error");
            return;
        }

        // Mint a control-panel session JWT and set it as the cp_session cookie on the redirect so the
        // post-SSO browser is actually authenticated to the API (closes the broken-STATELESS gap).
        issueSessionCookie(response, user);
        response.sendRedirect(uiBaseUrl + "?sso=success");
    }

    /** Sets the HttpOnly / Secure / SameSite=Lax {@code cp_session} cookie carrying a fresh session JWT. */
    private void issueSessionCookie(HttpServletResponse response, User user) {
        boolean superAdmin = user.isSuperAdmin();
        Set<String> authorities = authoritiesLoader.authoritiesFor(user.getId(), null, superAdmin);
        SessionTokenService.IssuedToken issued =
                sessionTokenService.issue(user.getId(), user.getEmail(), superAdmin, authorities, user.getTokenVersion());

        long maxAgeSeconds = sessionTokenService.ttl().getSeconds();
        Cookie cookie = new Cookie(SESSION_COOKIE, issued.token());
        cookie.setHttpOnly(true);
        cookie.setSecure(cookieSecure);
        cookie.setPath("/");
        cookie.setMaxAge((int) Math.min(maxAgeSeconds, Integer.MAX_VALUE));
        cookie.setAttribute("SameSite", "Lax");
        response.addCookie(cookie);
    }

    /** The raw Spring Security registration id ("google", "oidc-&lt;uuid&gt;", "saml-&lt;uuid&gt;"), or null. */
    private static String registrationId(Authentication auth) {
        if (auth instanceof OAuth2AuthenticationToken o) {
            return o.getAuthorizedClientRegistrationId();
        }
        if (auth.getPrincipal() instanceof Saml2AuthenticatedPrincipal s) {
            return s.getRelyingPartyRegistrationId();
        }
        return null;
    }

    /** Resolve the {@link SsoProvider} from the registration id (oidc-&lt;uuid&gt; / saml-&lt;uuid&gt;). */
    private SsoProvider resolveProvider(Authentication auth) {
        String registrationId = null;
        if (auth instanceof OAuth2AuthenticationToken o) {
            registrationId = o.getAuthorizedClientRegistrationId();
        } else if (auth.getPrincipal() instanceof Saml2AuthenticatedPrincipal s) {
            registrationId = s.getRelyingPartyRegistrationId();
        }
        if (registrationId == null) return null;
        int dash = registrationId.indexOf('-');
        if (dash < 0) return null;
        try {
            UUID providerId = UUID.fromString(registrationId.substring(dash + 1));
            return providerRepo.findById(providerId).orElse(null);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** The stable IdP subject identifier: OIDC {@code sub}, SAML NameID. Never the email. */
    private String extractSubject(Authentication auth) {
        Object principal = auth.getPrincipal();
        if (principal instanceof OidcUser oidc) {
            String sub = oidc.getSubject();
            if (sub != null && !sub.isBlank()) return sub;
        }
        if (principal instanceof OAuth2User o) {
            Object sub = o.getAttributes().get("sub");
            if (sub != null && !sub.toString().isBlank()) return sub.toString();
            return o.getName();
        }
        if (principal instanceof Saml2AuthenticatedPrincipal s) {
            return s.getName();
        }
        return auth.getName();
    }

    /**
     * True when the email's domain matches one of the provider's allowed_email_domains (CSV).
     * A null/blank allow-list denies all (deny JIT/auto-link by default, per SsoProvider docs).
     */
    private boolean emailDomainAllowed(SsoProvider provider, String email) {
        String allowed = provider.getAllowedEmailDomains();
        if (allowed == null || allowed.isBlank()) return false;
        int at = email.lastIndexOf('@');
        if (at < 0 || at == email.length() - 1) return false;
        String domain = email.substring(at + 1).trim().toLowerCase();
        for (String d : allowed.split(",")) {
            String candidate = d.trim().toLowerCase();
            if (!candidate.isEmpty() && candidate.equals(domain)) return true;
        }
        return false;
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

    /**
     * True only when the IdP POSITIVELY asserts email_verified == true (Boolean true or "true").
     * Used to gate the global Google flow, which links by email and so must not accept an absent
     * or non-boolean claim as "verified".
     */
    private boolean isEmailVerifiedTrue(Authentication auth) {
        if (auth.getPrincipal() instanceof OAuth2User o) {
            Object v = o.getAttributes().get("email_verified");
            if (v instanceof Boolean b) return b;
            if (v instanceof String s) return "true".equalsIgnoreCase(s.trim());
        }
        return false;
    }

    private static String mask(String email) {
        if (email == null) return null;
        int at = email.indexOf('@');
        if (at <= 1) return "***" + (at >= 0 ? email.substring(at) : "");
        return email.charAt(0) + "***" + email.substring(at);
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
