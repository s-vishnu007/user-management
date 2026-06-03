package com.example.cp.sso;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "sso_providers")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SsoProvider {

    public enum Type { SAML, OIDC }

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "org_id", nullable = false)
    private UUID orgId;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 20)
    private Type type;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "config_json", nullable = false, columnDefinition = "jsonb")
    private String configJson;

    @Column(name = "enabled", nullable = false)
    private boolean enabled;

    /**
     * AES-GCM blob (from KeyEncryptor) of the OIDC client secret. Nullable; the plaintext
     * secret is stripped from {@link #configJson} by SsoService at persist time and is never
     * returned in DTOs.
     */
    @Column(name = "client_secret_enc")
    private byte[] clientSecretEnc;

    /**
     * CSV of verified email domains the org will accept for SSO JIT provisioning. A null/blank
     * value means NO domains are allowed (deny JIT by default for safety).
     */
    @Column(name = "allowed_email_domains")
    private String allowedEmailDomains;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;
}
