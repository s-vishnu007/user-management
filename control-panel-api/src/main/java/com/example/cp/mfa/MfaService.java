package com.example.cp.mfa;

import com.example.cp.common.ApiException;
import com.example.cp.keys.KeyEncryptor;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.JWSVerifier;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jose.crypto.MACVerifier;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import dev.samstevens.totp.code.CodeGenerator;
import dev.samstevens.totp.code.CodeVerifier;
import dev.samstevens.totp.code.DefaultCodeGenerator;
import dev.samstevens.totp.code.DefaultCodeVerifier;
import dev.samstevens.totp.code.HashingAlgorithm;
import dev.samstevens.totp.secret.DefaultSecretGenerator;
import dev.samstevens.totp.secret.SecretGenerator;
import dev.samstevens.totp.time.SystemTimeProvider;
import dev.samstevens.totp.time.TimeProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.text.ParseException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Date;
import java.util.UUID;

/**
 * TOTP (RFC 6238) multi-factor authentication: enrollment, confirmation, code verification, and
 * disable. The shared secret is stored AES-GCM-encrypted via {@link KeyEncryptor} and only the
 * plaintext base32 secret + {@code otpauth://} URI are ever returned to the client (once, at
 * enrollment time).
 *
 * <p>Verification semantics (via {@code dev.samstevens.totp}): SHA1, 6 digits, 30-second period,
 * with a small allowed discrepancy ({@value #ALLOWED_TIME_PERIOD_DISCREPANCY} periods either side)
 * to tolerate device/server clock skew.</p>
 */
@Service
public class MfaService {

    /** Number of 30s windows of clock skew tolerated either side of "now" when verifying a code. */
    static final int ALLOWED_TIME_PERIOD_DISCREPANCY = 1;
    private static final int TIME_PERIOD_SECONDS = 30;
    private static final HashingAlgorithm ALGORITHM = HashingAlgorithm.SHA1;
    private static final int DIGITS = 6;

    /** Marks the short-lived JWT issued after step-1 of login as an MFA challenge, not a session. */
    private static final String CHALLENGE_PURPOSE = "mfa_challenge";
    /** Short challenge lifetime: enough to type a code, short enough to limit replay. */
    private static final Duration CHALLENGE_TTL = Duration.ofMinutes(5);

    private final UserMfaRepository repository;
    private final KeyEncryptor keyEncryptor;
    private final SecretGenerator secretGenerator;
    private final CodeVerifier codeVerifier;
    private final String issuerLabel;
    private final String sessionSecret;

    public MfaService(UserMfaRepository repository,
                      KeyEncryptor keyEncryptor,
                      @Value("${app.signing.issuer:control-panel}") String issuerLabel,
                      @Value("${app.auth.session-secret:}") String sessionSecret) {
        this.repository = repository;
        this.keyEncryptor = keyEncryptor;
        this.secretGenerator = new DefaultSecretGenerator();
        TimeProvider timeProvider = new SystemTimeProvider();
        CodeGenerator codeGenerator = new DefaultCodeGenerator(ALGORITHM, DIGITS);
        DefaultCodeVerifier verifier = new DefaultCodeVerifier(codeGenerator, timeProvider);
        verifier.setTimePeriod(TIME_PERIOD_SECONDS);
        verifier.setAllowedTimePeriodDiscrepancy(ALLOWED_TIME_PERIOD_DISCREPANCY);
        this.codeVerifier = verifier;
        this.issuerLabel = (issuerLabel == null || issuerLabel.isBlank()) ? "control-panel" : issuerLabel;
        this.sessionSecret = sessionSecret == null ? "" : sessionSecret;
    }

    /** True when the user has confirmed (enabled) MFA — the login flow must then require a code. */
    @Transactional(readOnly = true)
    public boolean isEnabled(UUID userId) {
        return userId != null && repository.existsByUserIdAndEnabledTrue(userId);
    }

    /**
     * Starts (or restarts) enrollment: generates a fresh secret, stores it encrypted with
     * {@code enabled=false}, and returns the plaintext base32 secret + {@code otpauth://} URI for
     * the authenticator app. Re-enrolling overwrites any unconfirmed secret; an already-enabled
     * user must disable first.
     */
    @Transactional
    public EnrollmentResult enroll(UUID userId, String accountLabel) {
        if (userId == null) {
            throw ApiException.unauthorized("Not authenticated");
        }
        if (repository.existsByUserIdAndEnabledTrue(userId)) {
            throw ApiException.conflict("MFA is already enabled; disable it before re-enrolling");
        }
        String secret = secretGenerator.generate();
        UserMfa row = repository.findByUserId(userId).orElseGet(() ->
                UserMfa.builder().userId(userId).createdAt(OffsetDateTime.now()).build());
        row.setSecretEnc(keyEncryptor.encrypt(secret.getBytes(StandardCharsets.UTF_8)));
        row.setEnabled(false);
        if (row.getCreatedAt() == null) {
            row.setCreatedAt(OffsetDateTime.now());
        }
        repository.save(row);
        String uri = otpAuthUri(secret, accountLabel);
        return new EnrollmentResult(secret, uri);
    }

    /**
     * Confirms enrollment: verifies a code against the pending (or existing) secret and flips
     * {@code enabled=true}. Idempotent for an already-enabled user presenting a valid code.
     *
     * @return {@code true} if the code matched and MFA is now enabled.
     */
    @Transactional
    public boolean confirmEnrollment(UUID userId, String code) {
        UserMfa row = repository.findByUserId(userId)
                .orElseThrow(() -> ApiException.badRequest("No MFA enrollment in progress"));
        if (!verifyCode(row, code)) {
            return false;
        }
        if (!row.isEnabled()) {
            row.setEnabled(true);
            repository.save(row);
        }
        return true;
    }

    /** Verifies a TOTP code for an enabled user (used during the two-step login). */
    @Transactional(readOnly = true)
    public boolean verifyLoginCode(UUID userId, String code) {
        UserMfa row = repository.findByUserId(userId).orElse(null);
        if (row == null || !row.isEnabled()) {
            return false;
        }
        return verifyCode(row, code);
    }

    /** Disables MFA for a user by deleting the enrollment row. No-op if not enrolled. */
    @Transactional
    public void disable(UUID userId) {
        repository.deleteByUserId(userId);
    }

    /**
     * Issues the short-lived MFA challenge token returned by step-1 of login when the user has MFA
     * enabled. It is an HS256 JWT signed with the session secret but carrying
     * {@code purpose=mfa_challenge} so it can NEVER be mistaken for (or used as) a full session
     * token: {@link com.example.cp.auth.SessionTokenService#parse} would reject it on the missing
     * session claims, and the API only accepts it at {@code /api/v1/auth/mfa/login}.
     */
    public MfaChallenge issueChallenge(UUID userId, String email) {
        Instant now = Instant.now();
        Instant exp = now.plus(CHALLENGE_TTL);
        try {
            JWTClaimsSet claims = new JWTClaimsSet.Builder()
                    .subject(userId.toString())
                    .claim("email", email)
                    .claim("purpose", CHALLENGE_PURPOSE)
                    .issueTime(Date.from(now))
                    .expirationTime(Date.from(exp))
                    .jwtID(UUID.randomUUID().toString())
                    .build();
            SignedJWT jwt = new SignedJWT(
                    new JWSHeader.Builder(JWSAlgorithm.HS256).type(JOSEObjectType.JWT).build(),
                    claims);
            jwt.sign(new MACSigner(sessionSecret.getBytes(StandardCharsets.UTF_8)));
            return new MfaChallenge(jwt.serialize(), exp);
        } catch (JOSEException e) {
            throw new IllegalStateException("Failed to sign MFA challenge", e);
        }
    }

    /**
     * Verifies a challenge token's signature, purpose and expiry and returns the bound user id.
     * Throws {@link ApiException} (401) on any failure.
     */
    public UUID parseChallenge(String challenge) {
        if (challenge == null || challenge.isBlank()) {
            throw ApiException.unauthorized("Missing MFA challenge");
        }
        try {
            SignedJWT jwt = SignedJWT.parse(challenge);
            JWSVerifier verifier = new MACVerifier(sessionSecret.getBytes(StandardCharsets.UTF_8));
            if (!jwt.verify(verifier)) {
                throw ApiException.unauthorized("Invalid MFA challenge");
            }
            JWTClaimsSet claims = jwt.getJWTClaimsSet();
            if (!CHALLENGE_PURPOSE.equals(claims.getStringClaim("purpose"))) {
                throw ApiException.unauthorized("Invalid MFA challenge");
            }
            Date exp = claims.getExpirationTime();
            if (exp == null || exp.toInstant().isBefore(Instant.now())) {
                throw ApiException.unauthorized("MFA challenge expired");
            }
            return UUID.fromString(claims.getSubject());
        } catch (ParseException | JOSEException | IllegalArgumentException e) {
            throw ApiException.unauthorized("Invalid MFA challenge");
        }
    }

    private boolean verifyCode(UserMfa row, String code) {
        if (code == null || code.isBlank()) {
            return false;
        }
        String secret = new String(keyEncryptor.decrypt(row.getSecretEnc()), StandardCharsets.UTF_8);
        return codeVerifier.isValidCode(secret, code.trim());
    }

    /**
     * Builds the standard {@code otpauth://totp/} provisioning URI consumed by authenticator apps
     * (Google Authenticator, Authy, 1Password, …). The label is {@code issuer:account} and the
     * issuer is repeated as a query parameter per the Key URI Format spec.
     */
    private String otpAuthUri(String secret, String accountLabel) {
        String account = (accountLabel == null || accountLabel.isBlank()) ? "user" : accountLabel;
        String label = enc(issuerLabel) + ":" + enc(account);
        return "otpauth://totp/" + label
                + "?secret=" + secret
                + "&issuer=" + enc(issuerLabel)
                + "&algorithm=SHA1"   // matches HashingAlgorithm.SHA1 used by the verifier
                + "&digits=" + DIGITS
                + "&period=" + TIME_PERIOD_SECONDS;
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    /** Result of starting enrollment: the one-time plaintext base32 secret + otpauth URI. */
    public record EnrollmentResult(String secret, String otpAuthUri) {}

    /** The short-lived MFA challenge token returned by step-1 of login, plus its expiry. */
    public record MfaChallenge(String challenge, Instant expiresAt) {}
}
