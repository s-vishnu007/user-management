package com.example.cp.sso;

import com.example.cp.audit.AuditWriter;
import com.example.cp.auth.SessionTokenService;
import com.example.cp.common.TrustedProxyResolver;
import com.example.cp.orgs.OrgMemberRepository;
import com.example.cp.rbac.AuthoritiesLoader;
import com.example.cp.users.User;
import com.example.cp.users.UserRepository;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Hermetic unit tests for {@link SsoSuccessHandler}: (provider, subject) identity binding, the
 * email-domain allow-list gate for JIT/auto-link, no super-admin auto-promotion, and the
 * {@code cp_session} cookie minting. No Spring context, DB, or network — all collaborators mocked.
 */
class SsoSuccessHandlerTest {

    private static final String SECRET = "0123456789abcdef0123456789abcdef-extra";

    private final UUID providerId = UUID.randomUUID();
    private final UUID orgId = UUID.randomUUID();

    private UserRepository userRepo;
    private OrgMemberRepository memberRepo;
    private SsoProviderRepository providerRepo;
    private SsoIdentityRepository identityRepo;
    private AuditWriter auditWriter;
    private TrustedProxyResolver proxyResolver;
    private SessionTokenService tokenService;
    private AuthoritiesLoader authoritiesLoader;
    private SsoProvisioningService provisioningService;
    private SsoSuccessHandler handler;

    @BeforeEach
    void setUp() {
        userRepo = mock(UserRepository.class);
        memberRepo = mock(OrgMemberRepository.class);
        providerRepo = mock(SsoProviderRepository.class);
        identityRepo = mock(SsoIdentityRepository.class);
        auditWriter = mock(AuditWriter.class);
        proxyResolver = mock(TrustedProxyResolver.class);
        tokenService = new SessionTokenService(SECRET, Duration.ofMinutes(30));
        authoritiesLoader = mock(AuthoritiesLoader.class);

        when(proxyResolver.resolveClientIp(any())).thenReturn("203.0.113.9");
        when(authoritiesLoader.authoritiesFor(any(), any(), eq(false))).thenReturn(Set.of("sub.read"));
        when(memberRepo.findByOrgIdAndUserId(any(), any())).thenReturn(Optional.empty());

        // The JIT user/identity/membership writes now live in a @Transactional SsoProvisioningService;
        // wire a real instance over the same mocks so the handler's gating + the provisioning behaviour
        // are both exercised end-to-end (the @Transactional boundary is a no-op without a tx manager).
        provisioningService = new SsoProvisioningService(userRepo, memberRepo, identityRepo, auditWriter);

        handler = new SsoSuccessHandler(providerRepo, identityRepo, provisioningService,
                auditWriter, proxyResolver, tokenService, authoritiesLoader);
        ReflectionTestUtils.setField(handler, "uiBaseUrl", "https://ui.example.com");
        ReflectionTestUtils.setField(handler, "cookieSecure", true);
    }

    private SsoProvider provider(String allowedDomains) {
        return SsoProvider.builder()
                .id(providerId)
                .orgId(orgId)
                .type(SsoProvider.Type.OIDC)
                .configJson("{}")
                .allowedEmailDomains(allowedDomains)
                .enabled(true)
                .createdAt(OffsetDateTime.now())
                .build();
    }

    /** OAuth2 (non-OIDC) token where principal name = subject and attributes carry sub + email. */
    private OAuth2AuthenticationToken oauth2(String subject, String email) {
        OAuth2User principal = new DefaultOAuth2User(
                List.of(() -> "ROLE_USER"),
                Map.of("sub", subject, "email", email),
                "sub");
        return new OAuth2AuthenticationToken(principal, principal.getAuthorities(), "oidc-" + providerId);
    }

    private MockHttpServletResponse run(OAuth2AuthenticationToken auth) throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest();
        MockHttpServletResponse res = new MockHttpServletResponse();
        handler.onAuthenticationSuccess(req, res, auth);
        return res;
    }

    private static Cookie sessionCookie(MockHttpServletResponse res) {
        return res.getCookie("cp_session");
    }

    @Test
    @DisplayName("existing (provider, subject) binding loads the bound user regardless of asserted email")
    void existingBindingWins() throws Exception {
        UUID boundUserId = UUID.randomUUID();
        when(providerRepo.findById(providerId)).thenReturn(Optional.of(provider("example.com")));
        when(identityRepo.findByProviderIdAndSubject(providerId, "subject-1"))
                .thenReturn(Optional.of(SsoIdentity.builder().id(UUID.randomUUID())
                        .providerId(providerId).subject("subject-1").userId(boundUserId)
                        .createdAt(OffsetDateTime.now()).build()));
        User bound = User.builder().id(boundUserId).email("real@example.com")
                .status(User.Status.ACTIVE).superAdmin(false).tokenVersion(3L).build();
        when(userRepo.findById(boundUserId)).thenReturn(Optional.of(bound));

        // The IdP asserts a DIFFERENT email; binding must still resolve the original bound user.
        MockHttpServletResponse res = run(oauth2("subject-1", "attacker@evil.com"));

        // No new user, no new identity created when the binding already exists.
        verify(userRepo, never()).save(any());
        verify(identityRepo, never()).save(any());
        assertThat(res.getRedirectedUrl()).isEqualTo("https://ui.example.com?sso=success");
        assertThat(sessionCookie(res)).isNotNull();
    }

    @Test
    @DisplayName("denies JIT/auto-link when the email domain is not in the provider allow-list")
    void deniesDisallowedDomain() throws Exception {
        when(providerRepo.findById(providerId)).thenReturn(Optional.of(provider("allowed.com")));
        when(identityRepo.findByProviderIdAndSubject(providerId, "subject-2")).thenReturn(Optional.empty());

        MockHttpServletResponse res = run(oauth2("subject-2", "user@other.com"));

        verify(userRepo, never()).save(any());
        verify(identityRepo, never()).save(any());
        assertThat(res.getRedirectedUrl()).isEqualTo("https://ui.example.com?sso=domain");
        assertThat(sessionCookie(res)).isNull();
    }

    @Test
    @DisplayName("denies when allow-list is blank (deny-by-default)")
    void deniesBlankAllowList() throws Exception {
        when(providerRepo.findById(providerId)).thenReturn(Optional.of(provider("  ")));
        when(identityRepo.findByProviderIdAndSubject(providerId, "subject-3")).thenReturn(Optional.empty());

        MockHttpServletResponse res = run(oauth2("subject-3", "user@anything.com"));

        verify(userRepo, never()).save(any());
        assertThat(res.getRedirectedUrl()).isEqualTo("https://ui.example.com?sso=domain");
    }

    @Test
    @DisplayName("JIT-creates a non-super-admin user and persists the identity when the domain is allowed")
    void jitCreatesAndBinds() throws Exception {
        when(providerRepo.findById(providerId)).thenReturn(Optional.of(provider("example.com,other.com")));
        when(identityRepo.findByProviderIdAndSubject(providerId, "subject-4")).thenReturn(Optional.empty());
        when(userRepo.findByEmail("new@other.com")).thenReturn(Optional.empty());
        when(userRepo.save(any(User.class))).thenAnswer(i -> i.getArgument(0));

        MockHttpServletResponse res = run(oauth2("subject-4", "new@other.com"));

        ArgumentCaptor<User> userCap = ArgumentCaptor.forClass(User.class);
        verify(userRepo).save(userCap.capture());
        assertThat(userCap.getValue().isSuperAdmin()).as("never auto-promote to super_admin").isFalse();
        assertThat(userCap.getValue().getEmail()).isEqualTo("new@other.com");

        ArgumentCaptor<SsoIdentity> idCap = ArgumentCaptor.forClass(SsoIdentity.class);
        verify(identityRepo).save(idCap.capture());
        assertThat(idCap.getValue().getProviderId()).isEqualTo(providerId);
        assertThat(idCap.getValue().getSubject()).isEqualTo("subject-4");

        assertThat(res.getRedirectedUrl()).isEqualTo("https://ui.example.com?sso=success");
        assertThat(sessionCookie(res)).isNotNull();
        Cookie c = sessionCookie(res);
        assertThat(c.isHttpOnly()).isTrue();
        assertThat(c.getSecure()).isTrue();
        assertThat(c.getPath()).isEqualTo("/");
        assertThat(c.getAttribute("SameSite")).isEqualTo("Lax");
    }

    @Test
    @DisplayName("auto-links an existing user by email (allowed domain) without elevating privileges")
    void autoLinksExistingUser() throws Exception {
        UUID existingId = UUID.randomUUID();
        when(providerRepo.findById(providerId)).thenReturn(Optional.of(provider("example.com")));
        when(identityRepo.findByProviderIdAndSubject(providerId, "subject-5")).thenReturn(Optional.empty());
        User existing = User.builder().id(existingId).email("existing@example.com")
                .status(User.Status.ACTIVE).superAdmin(false).tokenVersion(0L).build();
        when(userRepo.findByEmail("existing@example.com")).thenReturn(Optional.of(existing));

        MockHttpServletResponse res = run(oauth2("subject-5", "existing@example.com"));

        // Existing user is linked, not re-created.
        verify(userRepo, never()).save(any());
        ArgumentCaptor<SsoIdentity> idCap = ArgumentCaptor.forClass(SsoIdentity.class);
        verify(identityRepo).save(idCap.capture());
        assertThat(idCap.getValue().getUserId()).isEqualTo(existingId);
        assertThat(res.getRedirectedUrl()).isEqualTo("https://ui.example.com?sso=success");
    }
}
