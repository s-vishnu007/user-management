package com.example.cp.mfa;

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
 * Per-user TOTP (RFC 6238) enrollment. The shared secret is stored AES-GCM-encrypted via
 * {@link com.example.cp.keys.KeyEncryptor} ({@code secret_enc}) — the plaintext base32 secret is
 * only ever returned once at enrollment time and is never persisted in the clear.
 *
 * <p>A row is created (with {@code enabled=false}) when a user starts enrollment via
 * {@code POST /api/v1/auth/mfa/enroll}, and flipped to {@code enabled=true} once they confirm a
 * valid code via {@code POST /api/v1/auth/mfa/verify}. Disabling deletes the row.</p>
 */
@Entity
@Table(name = "user_mfa")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UserMfa {

    @Id
    @Column(name = "user_id", nullable = false)
    private UUID userId;

    /** AES-GCM blob (from KeyEncryptor) of the base32-encoded TOTP shared secret. */
    @Column(name = "secret_enc", nullable = false)
    private byte[] secretEnc;

    @Column(name = "enabled", nullable = false)
    @Builder.Default
    private boolean enabled = false;

    /**
     * The 30-second TOTP time-step ({@code epochSeconds / 30}) of the most-recently-accepted code.
     * {@code null} until the first code is accepted. A login/confirm code is rejected when its step
     * is {@code <= lastAcceptedStep}, so a code observed (e.g. shoulder-surfed or intercepted) cannot
     * be replayed within the ~90s window otherwise allowed by the verifier's skew tolerance.
     */
    @Column(name = "last_accepted_step")
    private Long lastAcceptedStep;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;
}
