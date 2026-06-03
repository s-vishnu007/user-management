package com.example.cp.keys;

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

import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "signing_keys")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SigningKey {

    public enum Status { ACTIVE, RETIRED, COMPROMISED }

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "kid", nullable = false, unique = true, length = 64)
    private String kid;

    @Column(name = "algorithm", nullable = false, length = 32)
    private String algorithm;

    @Column(name = "public_key_pem", nullable = false, columnDefinition = "text")
    private String publicKeyPem;

    @Column(name = "private_key_encrypted", nullable = false)
    private byte[] privateKeyEncrypted;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private Status status;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "retired_at")
    private OffsetDateTime retiredAt;
}
