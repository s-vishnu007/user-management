package com.example.cp.auth;

import com.example.cp.common.TrustedProxyResolver;
import com.example.cp.rbac.AuthoritiesLoader;
import com.example.cp.users.User;
import com.example.cp.users.UserRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Hermetic unit tests for {@link JwtAuthFilter}'s token acquisition: in addition to the Bearer
 * Authorization header, a {@code cp_session} cookie (minted post-SSO) is accepted on the identical
 * parse/validation path. No Spring context, DB, or Redis — uses the real {@link SessionTokenService}
 * + {@link InMemorySessionRevocationStore} and Mockito mocks for the rest.
 */
class JwtAuthFilterCookieTest {

    // 32+ byte secret so SessionTokenService's HS256 length guard passes.
    private static final String SECRET = "0123456789abcdef0123456789abcdef-extra";

    private final UUID userId = UUID.randomUUID();
    private SessionTokenService tokenService;
    private UserRepository userRepository;
    private AuthoritiesLoader authoritiesLoader;
    private TrustedProxyResolver proxyResolver;
    private JwtAuthFilter filter;

    @BeforeEach
    void setUp() {
        tokenService = new SessionTokenService(SECRET, Duration.ofMinutes(30));
        userRepository = mock(UserRepository.class);
        authoritiesLoader = mock(AuthoritiesLoader.class);
        proxyResolver = mock(TrustedProxyResolver.class);

        User user = User.builder()
                .id(userId)
                .email("u@example.com")
                .status(User.Status.ACTIVE)
                .superAdmin(false)
                .tokenVersion(0L)
                .build();
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(authoritiesLoader.authoritiesFor(eq(userId), any(), eq(false))).thenReturn(Set.of("sub.read"));
        when(authoritiesLoader.toGrantedAuthorities(any()))
                .thenReturn(List.of(new SimpleGrantedAuthority("sub.read")));
        when(proxyResolver.resolveClientIp(any())).thenReturn("203.0.113.7");

        filter = new JwtAuthFilter(tokenService, authoritiesLoader, new ObjectMapper(),
                new InMemorySessionRevocationStore(), userRepository, true, proxyResolver);
        SecurityContextHolder.clearContext();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private String validToken() {
        return tokenService.issue(userId, "u@example.com", false, Set.of("sub.read"), 0L).token();
    }

    @Test
    @DisplayName("authenticates from the cp_session cookie when no Bearer header is present")
    void authenticatesFromCookie() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.setCookies(new Cookie("cp_session", validToken()));
        MockHttpServletResponse res = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilterInternal(req, res, chain);

        verify(chain, times(1)).doFilter(req, res);
        assertThat(res.getStatus()).isEqualTo(200);
    }

    @Test
    @DisplayName("rejects an invalid cp_session cookie with 401 and does not continue the chain")
    void rejectsInvalidCookie() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.setCookies(new Cookie("cp_session", "not-a-jwt"));
        MockHttpServletResponse res = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilterInternal(req, res, chain);

        verify(chain, never()).doFilter(any(), any());
        assertThat(res.getStatus()).isEqualTo(401);
    }

    @Test
    @DisplayName("Bearer header takes precedence over the cp_session cookie")
    void bearerWinsOverCookie() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.addHeader("Authorization", "Bearer " + validToken());
        // A bogus cookie that would 401 if it were consulted — proves the header path is used.
        req.setCookies(new Cookie("cp_session", "not-a-jwt"));
        MockHttpServletResponse res = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilterInternal(req, res, chain);

        verify(chain, times(1)).doFilter(req, res);
        assertThat(res.getStatus()).isEqualTo(200);
    }

    @Test
    @DisplayName("no header and no cookie passes through unauthenticated")
    void noTokenPassesThrough() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest();
        MockHttpServletResponse res = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilterInternal(req, res, chain);

        verify(chain, times(1)).doFilter(req, res);
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        assertThat(auth).isNull();
    }

    @Test
    @DisplayName("an unrelated cookie is ignored (treated as no token)")
    void unrelatedCookieIgnored() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.setCookies(new Cookie("other", "value"));
        MockHttpServletResponse res = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilterInternal(req, res, chain);

        verify(chain, times(1)).doFilter(req, res);
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }
}
