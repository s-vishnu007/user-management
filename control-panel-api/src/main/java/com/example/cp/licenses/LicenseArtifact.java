package com.example.cp.licenses;

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

/**
 * The exact signed {@code .lic} artifact (raw JWT + the envelope metadata needed to rebuild the
 * download body) persisted at issue time, keyed by jti.
 *
 * <p>This makes {@code GET /licenses/{jti}/download} a <b>pure read</b>: it returns the stored JWT
 * for the requested jti or 404/410, and never re-mints a license (the previous in-memory-cache
 * design re-issued a brand-new license on any cache miss — a read-only principal could thereby
 * mint licenses it was forbidden to issue, with zero audit rows; see audit P1-4).
 *
 * <p>The artifact is immutable: the signed JWT for a jti never changes after issue, so there is no
 * update path.
 */
@Entity
@Table(name = "license_artifacts")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class LicenseArtifact {

    @Id
    @Column(name = "jti", nullable = false, length = 64)
    private String jti;

    @Column(name = "jwt", nullable = false)
    private String jwt;

    @Column(name = "kid", nullable = false, length = 64)
    private String kid;

    @Column(name = "plan_code", length = 64)
    private String planCode;

    @Column(name = "org_name")
    private String orgName;

    @Column(name = "org_slug", length = 190)
    private String orgSlug;

    @Column(name = "issued_at", nullable = false)
    private OffsetDateTime issuedAt;

    @Column(name = "expires_at", nullable = false)
    private OffsetDateTime expiresAt;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    /** Rebuilds the {@link LicenseIssuer.IssuedLicense} view used by the file/envelope builder. */
    public LicenseIssuer.IssuedLicense toIssuedLicense() {
        return new LicenseIssuer.IssuedLicense(jti, jwt, issuedAt, expiresAt, planCode, orgName, orgSlug, kid);
    }

    static LicenseArtifact from(LicenseIssuer.IssuedLicense issued) {
        return LicenseArtifact.builder()
                .jti(issued.jti())
                .jwt(issued.jwt())
                .kid(issued.kid())
                .planCode(issued.planCode())
                .orgName(issued.orgName())
                .orgSlug(issued.orgSlug())
                .issuedAt(issued.issuedAt())
                .expiresAt(issued.expiresAt())
                .createdAt(OffsetDateTime.now())
                .build();
    }
}
