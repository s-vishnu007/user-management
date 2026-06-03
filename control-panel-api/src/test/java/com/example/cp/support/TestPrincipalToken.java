package com.example.cp.support;

import com.example.cp.common.AuthenticatedUser;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;

import java.util.Collection;

/**
 * A pre-authenticated {@link org.springframework.security.core.Authentication} carrying an
 * {@link AuthenticatedUser} as its principal, used by the {@code asUser}/{@code asApiKey}
 * {@code RequestPostProcessor}s in {@link AbstractIntegrationTest}.
 *
 * <p>{@link com.example.cp.common.SecurityUtils#currentUser()} only resolves a principal that
 * {@code instanceof AuthenticatedUser}, so this token exposes the record directly as the principal
 * (unlike a plain {@code UsernamePasswordAuthenticationToken} whose principal would be a String if
 * built carelessly). It is marked authenticated so method-security SpEL runs.
 */
public class TestPrincipalToken extends AbstractAuthenticationToken {

    private final AuthenticatedUser principal;

    public TestPrincipalToken(AuthenticatedUser principal,
                              Collection<? extends GrantedAuthority> authorities) {
        super(authorities);
        this.principal = principal;
        setAuthenticated(true);
    }

    @Override
    public Object getCredentials() {
        return null;
    }

    @Override
    public Object getPrincipal() {
        return principal;
    }
}
