package com.example.cp.orgs;

import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

public class OrgMemberId implements Serializable {

    private UUID orgId;
    private UUID userId;

    public OrgMemberId() {}

    public OrgMemberId(UUID orgId, UUID userId) {
        this.orgId = orgId;
        this.userId = userId;
    }

    public UUID getOrgId() { return orgId; }
    public void setOrgId(UUID orgId) { this.orgId = orgId; }

    public UUID getUserId() { return userId; }
    public void setUserId(UUID userId) { this.userId = userId; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof OrgMemberId other)) return false;
        return Objects.equals(orgId, other.orgId) && Objects.equals(userId, other.userId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(orgId, userId);
    }
}
