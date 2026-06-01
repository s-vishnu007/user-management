package com.example.cp.rbac;

import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Service
public class PermissionService {

    private final EntityManager em;

    public PermissionService(EntityManager em) {
        this.em = em;
    }

    /**
     * Returns all permission codes granted to the user via user_roles → role_permissions → permissions.
     * If orgId is null, only global (org_id NULL) role assignments are considered.
     * If orgId is non-null, both global and org-scoped role assignments contribute.
     */
    @Transactional(readOnly = true)
    @SuppressWarnings("unchecked")
    public Set<String> permissionsFor(UUID userId, UUID orgId) {
        if (userId == null) {
            return Set.of();
        }
        String jpql;
        List<String> rows;
        if (orgId == null) {
            jpql = "select distinct p.code from Permission p, RolePermission rp, UserRole ur "
                    + "where p.id = rp.permissionId and rp.roleId = ur.roleId and ur.userId = :userId and ur.orgId is null";
            rows = em.createQuery(jpql, String.class)
                    .setParameter("userId", userId)
                    .getResultList();
        } else {
            jpql = "select distinct p.code from Permission p, RolePermission rp, UserRole ur "
                    + "where p.id = rp.permissionId and rp.roleId = ur.roleId and ur.userId = :userId "
                    + "and (ur.orgId is null or ur.orgId = :orgId)";
            rows = em.createQuery(jpql, String.class)
                    .setParameter("userId", userId)
                    .setParameter("orgId", orgId)
                    .getResultList();
        }
        return new LinkedHashSet<>(rows);
    }
}
