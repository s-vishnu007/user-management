package com.example.cp.apikeys;

import com.example.cp.common.AuthenticatedUser;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

@Component
public class ApiKeyAuthFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(ApiKeyAuthFilter.class);
    private static final String HEADER = "Authorization";
    private static final String SCHEME = "ApiKey ";

    private final ApiKeyService service;

    public ApiKeyAuthFilter(ApiKeyService service) {
        this.service = service;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String auth = request.getHeader(HEADER);
        if (auth != null && auth.startsWith(SCHEME)) {
            String raw = auth.substring(SCHEME.length()).trim();
            try {
                Optional<ApiKey> match = service.verify(raw);
                if (match.isPresent()) {
                    ApiKey key = match.get();
                    Set<String> scopes = service.parseScopes(key);
                    List<GrantedAuthority> authorities = scopes.stream()
                            .map(SimpleGrantedAuthority::new)
                            .collect(Collectors.toList());
                    AuthenticatedUser principal = new AuthenticatedUser(
                            null, "apikey:" + key.getId(), false, scopes, authorities, true, key.getOrgId());
                    ApiKeyAuthentication token = new ApiKeyAuthentication(principal, authorities, key.getOrgId());
                    token.setAuthenticated(true);
                    SecurityContextHolder.getContext().setAuthentication(token);
                }
            } catch (Exception e) {
                log.warn("ApiKey auth failed: {}", e.getMessage());
            }
        }
        chain.doFilter(request, response);
    }

    public static class ApiKeyAuthentication extends AbstractAuthenticationToken {
        private final AuthenticatedUser principal;
        private final java.util.UUID orgId;

        public ApiKeyAuthentication(AuthenticatedUser principal, List<GrantedAuthority> authorities, java.util.UUID orgId) {
            super(authorities);
            this.principal = principal;
            this.orgId = orgId;
        }

        @Override
        public Object getCredentials() { return ""; }

        @Override
        public Object getPrincipal() { return principal; }

        public java.util.UUID getOrgId() { return orgId; }
    }
}
