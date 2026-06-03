package com.example.cp.auth;

import com.example.cp.audit.AuditOutcome;
import com.example.cp.common.AuditContext;
import com.example.cp.common.AuthenticatedUser;
import com.example.cp.common.TrustedProxyResolver;
import com.example.cp.rbac.AuthoritiesLoader;
import com.example.cp.users.User;
import com.example.cp.users.UserRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import com.example.cp.common.ApiException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.Collection;
import java.util.Set;

public class JwtAuthFilter extends OncePerRequestFilter {

    private static final String BEARER = "Bearer ";

    private final SessionTokenService tokenService;
    private final AuthoritiesLoader authoritiesLoader;
    private final ObjectMapper objectMapper;
    private final SessionRevocationStore revocationStore;
    private final UserRepository userRepository;
    private final boolean revocationEnabled;
    private final TrustedProxyResolver proxyResolver;

    public JwtAuthFilter(SessionTokenService tokenService,
                         AuthoritiesLoader authoritiesLoader,
                         ObjectMapper objectMapper,
                         SessionRevocationStore revocationStore,
                         UserRepository userRepository,
                         boolean revocationEnabled,
                         TrustedProxyResolver proxyResolver) {
        this.tokenService = tokenService;
        this.authoritiesLoader = authoritiesLoader;
        this.objectMapper = objectMapper;
        this.revocationStore = revocationStore;
        this.userRepository = userRepository;
        this.revocationEnabled = revocationEnabled;
        this.proxyResolver = proxyResolver;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header == null || !header.startsWith(BEARER)) {
            chain.doFilter(request, response);
            return;
        }

        String token = header.substring(BEARER.length()).trim();
        SessionTokenService.ParsedToken parsed;
        try {
            parsed = tokenService.parse(token);
        } catch (ApiException ex) {
            writeProblem(response, ex.getStatus().value(), ex.getTitle(), ex.getDetail());
            return;
        }

        // Denylist check (single-session logout) — gated by the revocation flag + Redis availability.
        if (revocationEnabled && parsed.jti() != null && revocationStore.isJtiDenylisted(parsed.jti())) {
            writeProblem(response, 401, "Unauthorized", "Session has been revoked");
            return;
        }

        // Reload the user. Status + fresh-authorities re-check are UNCONDITIONAL (always run), so a
        // suspended/deleted user or a stale frozen-authorities claim is rejected even if the Redis
        // fast-path is disabled or unavailable.
        User user = userRepository.findById(parsed.userId()).orElse(null);
        if (user == null) {
            writeProblem(response, 401, "Unauthorized", "User not found");
            return;
        }
        if (user.getStatus() != User.Status.ACTIVE) {
            writeProblem(response, 401, "Unauthorized", "Account is not active");
            return;
        }

        // Token-version (bulk revocation) check. DB column is the durable source of truth; the Redis
        // cached version (>=0) only accelerates and may be -1 on a cache miss.
        if (revocationEnabled) {
            long effectiveVersion = user.getTokenVersion();
            long cached = revocationStore.currentTokenVersion(parsed.userId());
            if (cached >= 0) {
                effectiveVersion = Math.max(effectiveVersion, cached);
            }
            if (parsed.tokenVersion() < effectiveVersion) {
                writeProblem(response, 401, "Unauthorized", "Session has been revoked");
                return;
            }
        }

        // Prefer current server state over the frozen claims: super-admin and authorities are always
        // resolved fresh from the reloaded user (closes the gap where a stale claim grants access
        // until token expiry).
        boolean superAdmin = user.isSuperAdmin();
        Set<String> authorityCodes = authoritiesLoader.authoritiesFor(parsed.userId(), null, superAdmin);

        Collection<? extends GrantedAuthority> granted = authoritiesLoader.toGrantedAuthorities(authorityCodes);

        AuthenticatedUser principal = new AuthenticatedUser(
                parsed.userId(), parsed.email(), superAdmin, authorityCodes, granted);

        UsernamePasswordAuthenticationToken auth =
                new UsernamePasswordAuthenticationToken(principal, null, granted);
        SecurityContextHolder.getContext().setAuthentication(auth);

        AuditContext.setActor(parsed.userId(), null);
        AuditContext.setIp(proxyResolver.resolveClientIp(request));

        try {
            chain.doFilter(request, response);
        } finally {
            AuditContext.clear();
        }
    }

    private void writeProblem(HttpServletResponse response, int status, String title, String detail) throws IOException {
        // Mark the outcome so any audit fallback for this request reflects the denial.
        AuditContext.setOutcome(AuditOutcome.DENIED);
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(org.springframework.http.HttpStatusCode.valueOf(status), detail);
        pd.setTitle(title);
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        objectMapper.writeValue(response.getOutputStream(), pd);
    }
}
