package com.example.cp.auth;

import com.example.cp.common.ApiException;
import com.example.cp.common.AuditContext;
import com.example.cp.common.Ids;
import com.example.cp.common.TokenHashing;
import com.example.cp.subscriptions.OutboxPublisher;
import com.example.cp.users.User;
import com.example.cp.users.UserRepository;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.Map;

/**
 * Issues and consumes email-verification tokens for self-service signups.
 *
 * <p>Tokens are opaque, high-entropy, single-use and time-bounded; only their SHA-256 hash is
 * stored ({@link EmailVerificationToken}), exactly like {@link PasswordResetToken}. The raw token is
 * delivered out-of-band: in prod an {@code email.verification.requested} outbox event drives the
 * email; in dev the controller returns it when {@code app.auth.expose-verification-token=true}.
 *
 * <p>Verification is intentionally non-blocking — a user can sign in before verifying — so this
 * service only flips the {@code email_verified} flag; it never gates login.
 */
@Service
public class EmailVerificationService {

    /** Token byte length (CSPRNG) before Base64 encoding. */
    private static final int TOKEN_BYTES = 32;
    /** How long a verification link stays valid. */
    private static final long TOKEN_TTL_HOURS = 48;

    private final EmailVerificationTokenRepository tokenRepository;
    private final UserRepository userRepository;
    private final OutboxPublisher outboxPublisher;

    public EmailVerificationService(EmailVerificationTokenRepository tokenRepository,
                                    UserRepository userRepository,
                                    @Qualifier("subscriptionOutboxPublisher") OutboxPublisher outboxPublisher) {
        this.tokenRepository = tokenRepository;
        this.userRepository = userRepository;
        this.outboxPublisher = outboxPublisher;
    }

    /**
     * Persists a fresh verification token for {@code user} and returns the RAW token (the only time
     * it exists in plaintext). Caller decides whether to expose it (dev) or rely on the outbox email.
     */
    @Transactional
    public String issueToken(User user) {
        // Invalidate any still-active token for this user so only ONE verification link is live at a
        // time — bounds the valid-token pool and DB-row growth under repeated resends (re-audit #4).
        tokenRepository.deleteActiveByUserId(user.getId());
        String raw = TokenHashing.generateRawToken(TOKEN_BYTES);
        OffsetDateTime now = OffsetDateTime.now();
        tokenRepository.save(EmailVerificationToken.builder()
                .id(Ids.newId())
                .userId(user.getId())
                .tokenHash(TokenHashing.sha256(raw))
                .expiresAt(now.plusHours(TOKEN_TTL_HOURS))
                .createdAt(now)
                .build());
        return raw;
    }

    /**
     * Validates a raw token (hash match, not used, not expired) and marks the owning user's email
     * verified. Returns the verified user. Generic error messages avoid leaking token state.
     */
    @Transactional
    public User verify(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) {
            throw ApiException.badRequest("Token is required");
        }
        String hash = TokenHashing.sha256(rawToken);
        EmailVerificationToken token = tokenRepository.findByTokenHash(hash)
                .orElseThrow(() -> ApiException.badRequest("Invalid or expired token"));
        if (token.getUsedAt() != null) {
            // Generic message: a distinct "already used" reply leaks that the token existed and was
            // valid (token-state disclosure). Treat used/unknown/expired identically (re-audit #10).
            throw ApiException.badRequest("Invalid or expired token");
        }
        if (token.getExpiresAt() == null || token.getExpiresAt().isBefore(OffsetDateTime.now())) {
            throw ApiException.badRequest("Invalid or expired token");
        }
        User user = userRepository.findById(token.getUserId())
                .orElseThrow(() -> ApiException.badRequest("Invalid or expired token"));

        user.setEmailVerified(true);
        userRepository.save(user);
        token.setUsedAt(OffsetDateTime.now());
        tokenRepository.save(token);

        outboxPublisher.publish("user", user.getId().toString(), "email.verified",
                Map.of("email", user.getEmail()));
        AuditContext.set("auth.email.verified");
        AuditContext.setTarget("user", user.getId().toString());
        return user;
    }

    /**
     * Issues a new verification token for an already-authenticated, still-unverified user (the
     * "resend" button). Returns the raw token, or {@code null} when the user is already verified
     * (no-op). Publishes a fresh {@code email.verification.requested} event.
     */
    @Transactional
    public String resend(User user) {
        if (user.isEmailVerified()) {
            return null;
        }
        String raw = issueToken(user);
        outboxPublisher.publish("user", user.getId().toString(), "email.verification.requested",
                Map.of("email", user.getEmail()));
        AuditContext.set("auth.email.verification.resent");
        AuditContext.setTarget("user", user.getId().toString());
        return raw;
    }
}
