package com.example.cp.auth;

import com.example.cp.common.AuditContext;
import com.example.cp.common.AuthenticatedUser;
import com.example.cp.rbac.AuthoritiesLoader;
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

    public JwtAuthFilter(SessionTokenService tokenService,
                         AuthoritiesLoader authoritiesLoader,
                         ObjectMapper objectMapper) {
        this.tokenService = tokenService;
        this.authoritiesLoader = authoritiesLoader;
        this.objectMapper = objectMapper;
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

        Set<String> authorityCodes;
        if (parsed.superAdmin()) {
            // Resolve fresh authorities for super admin every request (small set, ok for MVP).
            authorityCodes = authoritiesLoader.authoritiesFor(parsed.userId(), null, true);
        } else if (parsed.authorities() != null && !parsed.authorities().isEmpty()) {
            authorityCodes = parsed.authorities();
        } else {
            authorityCodes = authoritiesLoader.authoritiesFor(parsed.userId(), null, false);
        }

        Collection<? extends GrantedAuthority> granted = authoritiesLoader.toGrantedAuthorities(authorityCodes);

        AuthenticatedUser principal = new AuthenticatedUser(
                parsed.userId(), parsed.email(), parsed.superAdmin(), authorityCodes, granted);

        UsernamePasswordAuthenticationToken auth =
                new UsernamePasswordAuthenticationToken(principal, null, granted);
        SecurityContextHolder.getContext().setAuthentication(auth);

        AuditContext.setActor(parsed.userId(), null);
        AuditContext.setIp(request.getRemoteAddr());

        try {
            chain.doFilter(request, response);
        } finally {
            AuditContext.clear();
        }
    }

    private void writeProblem(HttpServletResponse response, int status, String title, String detail) throws IOException {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(org.springframework.http.HttpStatusCode.valueOf(status), detail);
        pd.setTitle(title);
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        objectMapper.writeValue(response.getOutputStream(), pd);
    }
}
