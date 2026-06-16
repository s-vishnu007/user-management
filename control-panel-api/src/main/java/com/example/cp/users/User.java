package com.example.cp.users;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "users")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class User {

    public enum Status { ACTIVE, SUSPENDED, DELETED }

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    /** Optimistic-locking version; incremented on each update to prevent lost concurrent writes. */
    @Version
    @Column(name = "version", nullable = false)
    private long version;

    // Column is PostgreSQL CITEXT (case-insensitive unique email, see 01-organizations-users.sql).
    // columnDefinition keeps Hibernate schema-validation aligned with the citext type.
    @Column(name = "email", nullable = false, columnDefinition = "citext")
    private String email;

    @Column(name = "password_hash")
    private String passwordHash;

    @Column(name = "full_name")
    private String fullName;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private Status status;

    @Column(name = "super_admin", nullable = false)
    private boolean superAdmin;

    /**
     * Whether the user's email address has been confirmed. Self-service signups start {@code false}
     * (verification is non-blocking — they can still sign in); administratively-created and
     * pre-existing users are {@code true}. See migration 19.
     */
    @Column(name = "email_verified", nullable = false)
    @Builder.Default
    private boolean emailVerified = false;

    @Column(name = "token_version", nullable = false)
    @Builder.Default
    private long tokenVersion = 0L;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "last_login_at")
    private OffsetDateTime lastLoginAt;
}
