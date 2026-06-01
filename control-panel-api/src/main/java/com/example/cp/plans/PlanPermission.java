package com.example.cp.plans;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "plan_permissions")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PlanPermission {

    @EmbeddedId
    private Pk id;

    @Embeddable
    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class Pk implements Serializable {

        @Column(name = "plan_id", nullable = false)
        private UUID planId;

        @Column(name = "permission_code", nullable = false)
        private String permissionCode;

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof Pk pk)) return false;
            return Objects.equals(planId, pk.planId) && Objects.equals(permissionCode, pk.permissionCode);
        }

        @Override
        public int hashCode() {
            return Objects.hash(planId, permissionCode);
        }
    }
}
