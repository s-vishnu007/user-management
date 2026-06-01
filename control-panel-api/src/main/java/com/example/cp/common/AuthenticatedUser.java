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
        Collection<? extends GrantedAuthority> grantedAuthorities
) {

    public boolean hasAuthority(String code) {
        return superAdmin || (authorities != null && authorities.contains(code));
    }
}
