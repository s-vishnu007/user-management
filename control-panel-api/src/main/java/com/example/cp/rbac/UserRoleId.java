package com.example.cp.rbac;

import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

public class UserRoleId implements Serializable {

    private UUID userId;
    private UUID roleId;
    private UUID orgId;

    public UserRoleId() {}

    public UserRoleId(UUID userId, UUID roleId, UUID orgId) {
        this.userId = userId;
        this.roleId = roleId;
        this.orgId = orgId;
    }

    public UUID getUserId() { return userId; }
    public void setUserId(UUID userId) { this.userId = userId; }

    public UUID getRoleId() { return roleId; }
    public void setRoleId(UUID roleId) { this.roleId = roleId; }

    public UUID getOrgId() { return orgId; }
    public void setOrgId(UUID orgId) { this.orgId = orgId; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof UserRoleId other)) return false;
        return Objects.equals(userId, other.userId)
                && Objects.equals(roleId, other.roleId)
                && Objects.equals(orgId, other.orgId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(userId, roleId, orgId);
    }
}
