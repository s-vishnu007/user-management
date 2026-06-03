package com.example.cp.sso;

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
 * Binds an external (provider, subject) pair to a local user so a hostile IdP that
 * changes the email it asserts cannot hijack an existing local account. Lookups during
 * SSO login key on (providerId, subject), never on email.
 */
@Entity
@Table(name = "sso_identities")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SsoIdentity {

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "provider_id", nullable = false)
    private UUID providerId;

    @Column(name = "subject", nullable = false)
    private String subject;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;
}
