package com.example.cp.common;

import org.springframework.security.core.GrantedAuthority;

import java.util.Collection;
import java.util.Set;
import java.util.UUID;

public record AuthenticatedUser(
        UUID userId,
        String email,
        boolean superAdmin,
        Set<String> authorities,
        Collection<? extends GrantedAuthority> grantedAuthorities,
        boolean apiKey,
        UUID apiKeyOrgId
) {

    /**
     * Backward-compatible 5-arg ctor for human-user principals (apiKey=false, apiKeyOrgId=null).
     */
    public AuthenticatedUser(UUID userId, String email, boolean superAdmin,
                             Set<String> authorities, Collection<? extends GrantedAuthority> grantedAuthorities) {
        this(userId, email, superAdmin, authorities, grantedAuthorities, false, null);
    }

    public boolean hasAuthority(String code) {
        return superAdmin || (authorities != null && authorities.contains(code));
    }

    public boolean isApiKey() {
        return apiKey;
    }
}
