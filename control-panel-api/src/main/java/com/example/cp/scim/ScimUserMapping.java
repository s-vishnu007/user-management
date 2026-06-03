package com.example.cp.scim;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Durable bridge between an IdP's user namespace and a control-panel {@code users} row, scoped to one
 * organization. Maps the migration {@code scim_user_mappings} (16-scim.sql) 1:1
 * ({@code spring.jpa.hibernate.ddl-auto=validate}).
 *
 * <p>The SCIM resource {@code id} exposed to the IdP is this mapping's {@code id} — never the raw
 * {@code users.id} — so a client can only address users it provisioned in its own org. {@code orgId}
 * pins the mapping to the calling API key's org; {@code externalId} is the IdP-assigned key
 * (unique per org via {@code ux_scim_user_mappings_org_external}); {@code userId} is the linked user.
 */
@Entity
@Table(name = "scim_user_mappings")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ScimUserMapping {

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "org_id", nullable = false)
    private UUID orgId;

    @Column(name = "external_id")
    private String externalId;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;
}
