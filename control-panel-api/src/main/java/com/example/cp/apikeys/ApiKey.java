package com.example.cp.apikeys;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.DynamicUpdate;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "api_keys")
// @DynamicUpdate so a last-used-at write emits an UPDATE of only the changed columns (not a full
// re-persist that would re-write a stale revoked_at=NULL); @Version then makes any concurrent
// update conflict fail-fast instead of silently overwriting a committed revoke().
@DynamicUpdate
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ApiKey {

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    /** Optimistic-locking version; incremented on each update to prevent lost concurrent writes. */
    @Version
    @Column(name = "version", nullable = false)
    private long version;

    @Column(name = "org_id", nullable = false)
    private UUID orgId;

    @Column(name = "name")
    private String name;

    @Column(name = "key_hash", nullable = false)
    private String keyHash;

    @Column(name = "key_prefix", nullable = false, length = 16)
    private String keyPrefix;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "scopes_json", columnDefinition = "jsonb")
    private String scopesJson;

    @Column(name = "last_used_at")
    private OffsetDateTime lastUsedAt;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "revoked_at")
    private OffsetDateTime revokedAt;
}
