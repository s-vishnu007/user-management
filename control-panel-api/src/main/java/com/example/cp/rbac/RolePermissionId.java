package com.example.cp.rbac;

import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

public class RolePermissionId implements Serializable {

    private UUID roleId;
    private UUID permissionId;

    public RolePermissionId() {}

    public RolePermissionId(UUID roleId, UUID permissionId) {
        this.roleId = roleId;
        this.permissionId = permissionId;
    }

    public UUID getRoleId() { return roleId; }
    public void setRoleId(UUID roleId) { this.roleId = roleId; }

    public UUID getPermissionId() { return permissionId; }
    public void setPermissionId(UUID permissionId) { this.permissionId = permissionId; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof RolePermissionId other)) return false;
        return Objects.equals(roleId, other.roleId) && Objects.equals(permissionId, other.permissionId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(roleId, permissionId);
    }
}
