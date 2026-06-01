package com.example.cp.usage;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "usage_quotas")
@IdClass(UsageQuota.PK.class)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UsageQuota {

    @Id
    @Column(name = "subscription_id", nullable = false)
    private UUID subscriptionId;

    @Id
    @Column(name = "feature_key", nullable = false, length = 64)
    private String featureKey;

    @Id
    @Column(name = "period_start", nullable = false)
    private OffsetDateTime periodStart;

    @Column(name = "period_end", nullable = false)
    private OffsetDateTime periodEnd;

    @Column(name = "limit_value")
    private BigDecimal limitValue;

    @Column(name = "consumed_value", nullable = false)
    private BigDecimal consumedValue;

    public static class PK implements java.io.Serializable {
        private UUID subscriptionId;
        private String featureKey;
        private OffsetDateTime periodStart;

        public PK() {}

        public PK(UUID subscriptionId, String featureKey, OffsetDateTime periodStart) {
            this.subscriptionId = subscriptionId;
            this.featureKey = featureKey;
            this.periodStart = periodStart;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof PK pk)) return false;
            return java.util.Objects.equals(subscriptionId, pk.subscriptionId)
                    && java.util.Objects.equals(featureKey, pk.featureKey)
                    && java.util.Objects.equals(periodStart, pk.periodStart);
        }

        @Override
        public int hashCode() {
            return java.util.Objects.hash(subscriptionId, featureKey, periodStart);
        }
    }
}
