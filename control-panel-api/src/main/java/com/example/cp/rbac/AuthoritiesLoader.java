package com.example.cp.rbac;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Component
public class AuthoritiesLoader {

    private final PermissionService permissionService;
    private final PermissionRepository permissionRepository;

    public AuthoritiesLoader(PermissionService permissionService, PermissionRepository permissionRepository) {
        this.permissionService = permissionService;
        this.permissionRepository = permissionRepository;
    }

    public Set<String> authoritiesFor(UUID userId, UUID orgId, boolean superAdmin) {
        if (superAdmin) {
            Set<String> all = permissionRepository.findAll().stream()
                    .map(Permission::getCode)
                    .collect(Collectors.toCollection(LinkedHashSet::new));
            all.add("SUPER_ADMIN");
            return all;
        }
        return permissionService.permissionsFor(userId, orgId);
    }

    public Collection<GrantedAuthority> toGrantedAuthorities(Set<String> codes) {
        List<GrantedAuthority> out = new ArrayList<>();
        if (codes == null) {
            return out;
        }
        for (String c : codes) {
            out.add(new SimpleGrantedAuthority(c));
        }
        return out;
    }
}
