package com.example.cp.auth;

import com.example.cp.audit.AuditOutcome;
import com.example.cp.audit.AuditWriter;
import com.example.cp.common.ApiException;
import com.example.cp.common.AuditContext;
import com.example.cp.common.AuthenticatedUser;
import com.example.cp.common.Ids;
import com.example.cp.common.SecurityUtils;
import com.example.cp.common.TrustedProxyResolver;
import com.example.cp.mfa.MfaService;
import com.example.cp.orgs.OrgMember;
import com.example.cp.orgs.OrgMemberRepository;
import com.example.cp.orgs.Organization;
import com.example.cp.orgs.OrganizationRepository;
import com.example.cp.rbac.AuthoritiesLoader;
import com.example.cp.users.User;
import com.example.cp.users.UserDto;
import com.example.cp.users.UserRepository;
import com.example.cp.users.UserService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private static final String BEARER = "Bearer ";
    private static final int RESET_TOKEN_BYTES = 32;
    private static final long RESET_TOKEN_TTL_MINUTES = 60;

    private final UserRepository userRepository;
    private final UserService userService;
    private final PasswordEncoder passwordEncoder;
    private final SessionTokenService tokenService;
    private final AuthoritiesLoader authoritiesLoader;
    private final PasswordResetTokenRepository resetTokenRepository;
    private final LoginRateLimiter loginRateLimiter;
    private final OrgMemberRepository orgMemberRepository;
    private final OrganizationRepository organizationRepository;
    private final SessionRevocationStore revocationStore;
    private final MfaService mfaService;
    private final AuditWriter auditWriter;
    private final TrustedProxyResolver proxyResolver;
    private final boolean exposeResetToken;
    private final RegistrationService registrationService;
    private final EmailVerificationService emailVerificationService;
    private final boolean exposeVerificationToken;

    public AuthController(UserRepository userRepository,
                          UserService userService,
                          PasswordEncoder passwordEncoder,
                          SessionTokenService tokenService,
                          AuthoritiesLoader authoritiesLoader,
                          PasswordResetTokenRepository resetTokenRepository,
                          LoginRateLimiter loginRateLimiter,
                          OrgMemberRepository orgMemberRepository,
                          OrganizationRepository organizationRepository,
                          SessionRevocationStore revocationStore,
                          MfaService mfaService,
                          AuditWriter auditWriter,
                          TrustedProxyResolver proxyResolver,
                          @Value("${app.auth.expose-reset-token:false}") boolean exposeResetToken,
                          RegistrationService registrationService,
                          EmailVerificationService emailVerificationService,
                          @Value("${app.auth.expose-verification-token:false}") boolean exposeVerificationToken) {
        this.userRepository = userRepository;
        this.userService = userService;
        this.passwordEncoder = passwordEncoder;
        this.tokenService = tokenService;
        this.authoritiesLoader = authoritiesLoader;
        this.resetTokenRepository = resetTokenRepository;
        this.loginRateLimiter = loginRateLimiter;
        this.orgMemberRepository = orgMemberRepository;
        this.organizationRepository = organizationRepository;
        this.revocationStore = revocationStore;
        this.mfaService = mfaService;
        this.auditWriter = auditWriter;
        this.proxyResolver = proxyResolver;
        this.exposeResetToken = exposeResetToken;
        this.registrationService = registrationService;
        this.emailVerificationService = emailVerificationService;
        this.exposeVerificationToken = exposeVerificationToken;
    }

    /** Whether the cp_session cookie carries the Secure attribute (HTTPS-only). Off only for local dev. */
    @Value("${app.auth.cookie-secure:true}")
    private boolean cookieSecure;

    @PostMapping("/login")
    @Transactional
    public LoginResponse login(@Valid @RequestBody LoginRequest body, HttpServletRequest request,
                               HttpServletResponse httpResponse) {
        String email = body.email() == null ? "" : body.email().trim();
        String ip = proxyResolver.resolveClientIp(request);
        if (loginRateLimiter.isLocked(email, ip)) {
            // Explicit DENIED write before throwing; mark recorded so the aspect does not duplicate.
            auditWriter.record(null, null, "auth.login.locked", "user", null,
                    Map.of("email", maskEmail(email)), ip, AuditOutcome.DENIED, false);
            AuditContext.markRecorded();
            throw ApiException.unauthorized("Too many failed attempts; try again later");
        }
        Optional<User> userOpt = userRepository.findByEmail(email);
        if (userOpt.isEmpty()) {
            recordLoginFailed(null, email, "unknown_user", ip);
            loginRateLimiter.recordFailure(email, ip);
            throw ApiException.unauthorized("Invalid email or password");
        }
        User user = userOpt.get();
        if (user.getStatus() != User.Status.ACTIVE) {
            recordLoginFailed(user.getId(), email, "inactive", ip);
            loginRateLimiter.recordFailure(email, ip);
            throw ApiException.unauthorized("Account is not active");
        }
        if (user.getPasswordHash() == null || !passwordEncoder.matches(body.password(), user.getPasswordHash())) {
            recordLoginFailed(user.getId(), email, "bad_password", ip);
            loginRateLimiter.recordFailure(email, ip);
            throw ApiException.unauthorized("Invalid email or password");
        }

        loginRateLimiter.recordSuccess(email, ip);

        // Step 1 of two-step login: when MFA is enabled the password alone yields only a short-lived
        // challenge — NOT a session token. The session is issued by /mfa/login after a valid code.
        if (mfaService.isEnabled(user.getId())) {
            MfaService.MfaChallenge challenge = mfaService.issueChallenge(user.getId(), user.getEmail());
            AuditContext.set("auth.login.mfa_challenge");
            AuditContext.setTarget("user", user.getId().toString());
            return LoginResponse.mfaChallenge(challenge.challenge(), challenge.expiresAt());
        }

        AuditContext.set("auth.login");
        AuditContext.setTarget("user", user.getId().toString());
        return completeSession(user, httpResponse);
    }

    /**
     * Step 2 of two-step login: completes authentication for an MFA-enabled user by verifying a
     * TOTP code against the challenge issued by {@code /login}, returning the full session token.
     * Public (like {@code /login}) because the caller has no session yet — the bearer of a valid
     * signed {@code challenge} + correct {@code code} is the authenticated user.
     *
     * <p>The signed {@code challenge} is REQUIRED: it is the only proof that the password step
     * ({@code /login}) succeeded, so MFA stays a true second factor (there is no email-only path
     * that would collapse 2FA to a single TOTP guess). This endpoint is additionally rate-limited /
     * lockout-protected (per account + per IP, shared with {@code /login}) so the 6-digit code
     * cannot be brute-forced: {@link #isLocked} is consulted at entry and every bad code is recorded
     * as a failure, locking the account after the configured ceiling.</p>
     */
    @PostMapping("/mfa/login")
    @Transactional
    public LoginResponse mfaLogin(@Valid @RequestBody MfaLoginRequest body, HttpServletRequest request,
                                  HttpServletResponse httpResponse) {
        String ip = proxyResolver.resolveClientIp(request);

        // The signed challenge (issued by /login after a valid password) is mandatory — it both
        // identifies the subject and proves factor one passed. No email-only fallback exists.
        if (body.challenge() == null || body.challenge().isBlank()) {
            throw ApiException.badRequest("A signed MFA challenge is required");
        }
        UUID userId = mfaService.parseChallenge(body.challenge());

        User user = userRepository.findById(userId)
                .orElseThrow(() -> ApiException.unauthorized("Invalid MFA challenge"));

        // Throttle BEFORE verifying the code so an attacker cannot make unlimited TOTP guesses
        // against an account they hold a (still-valid) challenge for. Consult the lock first.
        if (loginRateLimiter.isLocked(user.getEmail(), ip)) {
            auditWriter.record(user.getId(), null, "auth.login.mfa.locked", "user",
                    user.getId().toString(), Map.of("email", maskEmail(user.getEmail())), ip,
                    AuditOutcome.DENIED, false);
            AuditContext.markRecorded();
            throw ApiException.unauthorized("Too many failed attempts; try again later");
        }

        if (user.getStatus() != User.Status.ACTIVE) {
            recordLoginFailed(user.getId(), user.getEmail(), "inactive", ip);
            throw ApiException.unauthorized("Account is not active");
        }
        if (!mfaService.verifyLoginCode(user.getId(), body.code())) {
            recordLoginFailed(user.getId(), user.getEmail(), "bad_mfa_code", ip);
            loginRateLimiter.recordFailure(user.getEmail(), ip);
            throw ApiException.unauthorized("Invalid code");
        }

        loginRateLimiter.recordSuccess(user.getEmail(), ip);
        AuditContext.set("auth.login.mfa");
        AuditContext.setTarget("user", user.getId().toString());
        return completeSession(user, httpResponse);
    }

    /** Issues the full session token for an authenticated user and stamps last-login. */
    private LoginResponse completeSession(User user, HttpServletResponse httpResponse) {
        SessionTokenService.IssuedToken issued = issueSessionCookie(user, httpResponse);
        return LoginResponse.session(issued.token(), issued.expiresAt(), UserDto.from(user));
    }

    /**
     * Mints a session token for {@code user}, stamps last-login, and sets it as the
     * HttpOnly/Secure/SameSite=Lax {@code cp_session} cookie (accepted by JwtAuthFilter) so browser
     * clients need not store the token in JS-readable storage (mitigates XSS token theft). Shared by
     * password login ({@link #completeSession}) and self-service signup auto-login. The token is also
     * returned so callers can include the Bearer accessToken in the response body.
     */
    private SessionTokenService.IssuedToken issueSessionCookie(User user, HttpServletResponse httpResponse) {
        Set<String> authorities = authoritiesLoader.authoritiesFor(user.getId(), null, user.isSuperAdmin());
        SessionTokenService.IssuedToken issued = tokenService.issue(
                user.getId(), user.getEmail(), user.isSuperAdmin(), authorities, user.getTokenVersion());

        user.setLastLoginAt(OffsetDateTime.now());
        userRepository.save(user);

        ResponseCookie cookie = ResponseCookie.from("cp_session", issued.token())
                .httpOnly(true).secure(cookieSecure).sameSite("Lax").path("/")
                .maxAge(tokenService.ttl())
                .build();
        httpResponse.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
        return issued;
    }

    /**
     * Self-service signup: creates a new organization with the caller as OWNER plus a new ACTIVE
     * (email-unverified) user, then auto-logs them in (sets the cp_session cookie). Public and
     * rate-limited (see {@code RateLimitFilter}). Email verification is non-blocking; the raw
     * verification token is returned only in dev ({@code app.auth.expose-verification-token=true}) —
     * in prod it is emailed via the outbox {@code email.verification.requested} event.
     */
    @PostMapping("/register")
    @Transactional
    public RegisterResponse register(@Valid @RequestBody RegisterRequest body, HttpServletRequest request,
                                     HttpServletResponse httpResponse) {
        RegistrationService.Result result = registrationService.register(
                body.fullName(), body.email(), body.password(), body.orgName(),
                proxyResolver.resolveClientIp(request));
        User user = result.user();
        SessionTokenService.IssuedToken issued = issueSessionCookie(user, httpResponse);
        AuditContext.set("auth.register");
        AuditContext.setTarget("user", user.getId().toString());
        AuditContext.putPayload("org_id", result.org().getId().toString());
        String token = exposeVerificationToken ? result.verificationToken() : null;
        return new RegisterResponse(issued.token(), issued.expiresAt(), UserDto.from(user),
                result.orgSlug(), true, token);
    }

    /**
     * Confirms an email-verification token (public — the link may be opened in any browser, even one
     * without a session). Flips the user's {@code email_verified} flag; idempotent-ish via the
     * single-use token guard. Does not establish a session.
     */
    @PostMapping("/verify-email")
    @Transactional
    public Map<String, Object> verifyEmail(@Valid @RequestBody VerifyEmailRequest body) {
        User user = emailVerificationService.verify(body.token());
        AuditContext.set("auth.email.verified");
        AuditContext.setTarget("user", user.getId().toString());
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("verified", true);
        response.put("email", user.getEmail());
        return response;
    }

    /**
     * Re-issues a verification token for the currently-authenticated, still-unverified user (the
     * "resend" action behind the verify-your-email banner). Requires a session. The raw token is
     * returned only in dev; in prod it is emailed via the outbox.
     */
    @PostMapping("/verify-email/resend")
    @Transactional
    public Map<String, Object> resendVerification() {
        AuthenticatedUser me = SecurityUtils.requireUser();
        User user = userRepository.findById(me.userId())
                .orElseThrow(() -> ApiException.unauthorized("User not found"));
        String raw = emailVerificationService.resend(user);
        AuditContext.set("auth.email.verification.resent");
        AuditContext.setTarget("user", user.getId().toString());
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("status", "ok");
        response.put("alreadyVerified", user.isEmailVerified());
        if (exposeVerificationToken && raw != null) {
            response.put("verification_token", raw);
        }
        return response;
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(HttpServletRequest request, HttpServletResponse httpResponse) {
        // Stateless JWT — denylist the exact jti for its remaining life so the token cannot be reused.
        SecurityUtils.currentUser().ifPresent(u ->
                AuditContext.setTarget("user", u.userId().toString()));
        AuditContext.set("auth.logout");

        // Resolve the session token with the SAME precedence as JwtAuthFilter: Bearer header first,
        // else the cp_session cookie (the only credential a post-SSO browser holds). Without this a
        // cookie/SSO session could never be revoked via logout.
        String token = bearerToken(request);
        if (token == null) {
            token = sessionCookie(request);
        }
        if (token != null && !token.isBlank()) {
            try {
                SessionTokenService.ParsedToken parsed = tokenService.parse(token);
                if (parsed.jti() != null && parsed.expiresAt() != null) {
                    Duration ttl = Duration.between(Instant.now(), parsed.expiresAt());
                    if (!ttl.isZero() && !ttl.isNegative()) {
                        revocationStore.denylistJti(parsed.jti(), ttl);
                    }
                }
            } catch (ApiException ignored) {
                // Token expired/invalid: logout is idempotent — nothing to denylist.
            } catch (RevocationStoreException e) {
                // The denylist write could not be persisted (e.g. Redis outage). Do NOT report a
                // successful logout while the token stays valid — return 503 so the client retries.
                AuditContext.setOutcome(AuditOutcome.FAILED);
                throw new ApiException(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,
                        "Service Unavailable", "Could not revoke the session; please retry");
            }
        }

        // Always clear the cp_session cookie (Max-Age=0) so the browser drops it even when the jti
        // was already denylisted/expired. Mirrors the attributes set on login so the browser matches.
        ResponseCookie expired = ResponseCookie.from("cp_session", "")
                .httpOnly(true).secure(cookieSecure).sameSite("Lax").path("/")
                .maxAge(0)
                .build();
        httpResponse.addHeader(HttpHeaders.SET_COOKIE, expired.toString());
        return ResponseEntity.noContent().build();
    }

    /** The Bearer token from the Authorization header, or {@code null} when absent. */
    private static String bearerToken(HttpServletRequest request) {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header != null && header.startsWith(BEARER)) {
            return header.substring(BEARER.length()).trim();
        }
        return null;
    }

    /** The value of the cp_session cookie, or {@code null} when it is not present. */
    private static String sessionCookie(HttpServletRequest request) {
        jakarta.servlet.http.Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return null;
        }
        for (jakarta.servlet.http.Cookie c : cookies) {
            if ("cp_session".equals(c.getName())) {
                return c.getValue();
            }
        }
        return null;
    }

    @PostMapping("/password-reset/request")
    @Transactional
    public ResponseEntity<Map<String, Object>> requestReset(@Valid @RequestBody PasswordResetRequest body) {
        String email = body.email() == null ? "" : body.email().trim();
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("status", "ok");

        Optional<User> userOpt = userRepository.findByEmail(email);
        if (userOpt.isEmpty() || userOpt.get().getStatus() != User.Status.ACTIVE) {
            // Do not leak existence
            return ResponseEntity.ok(response);
        }
        User user = userOpt.get();

        String rawToken = generateRawToken();
        String hash = sha256(rawToken);
        OffsetDateTime now = OffsetDateTime.now();
        PasswordResetToken token = PasswordResetToken.builder()
                .id(Ids.newId())
                .userId(user.getId())
                .tokenHash(hash)
                .expiresAt(now.plusMinutes(RESET_TOKEN_TTL_MINUTES))
                .createdAt(now)
                .build();
        resetTokenRepository.save(token);

        AuditContext.set("auth.password_reset.requested");
        AuditContext.setTarget("user", user.getId().toString());

        // The token is exposed only when app.auth.expose-reset-token=true (dev/test profiles).
        // In production this flag is false and the token would be emailed, not returned.
        if (exposeResetToken) {
            response.put("reset_token", rawToken);
        }
        return ResponseEntity.ok(response);
    }

    @PostMapping("/password-reset/confirm")
    @Transactional
    public ResponseEntity<Void> confirmReset(@Valid @RequestBody PasswordResetConfirm body) {
        if (body.token() == null || body.token().isBlank()) {
            throw ApiException.badRequest("Token is required");
        }
        String hash = sha256(body.token());
        PasswordResetToken token = resetTokenRepository.findByTokenHash(hash)
                .orElseThrow(() -> ApiException.badRequest("Invalid or expired token"));
        if (token.getUsedAt() != null) {
            // Generic message — don't leak that the token existed and was already consumed (re-audit #10).
            throw ApiException.badRequest("Invalid or expired token");
        }
        if (token.getExpiresAt() == null || token.getExpiresAt().isBefore(OffsetDateTime.now())) {
            throw ApiException.badRequest("Invalid or expired token");
        }
        // UserService.setPassword bumps the user token-version (revokes all prior sessions);
        // do NOT bump again here to avoid a double-bump.
        userService.setPassword(token.getUserId(), body.newPassword());
        token.setUsedAt(OffsetDateTime.now());
        resetTokenRepository.save(token);
        AuditContext.set("auth.password_reset.confirmed");
        AuditContext.setTarget("user", token.getUserId().toString());
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/me")
    public MeResponse me() {
        AuthenticatedUser me = SecurityUtils.requireUser();
        User user = userRepository.findById(me.userId())
                .orElseThrow(() -> ApiException.unauthorized("User not found"));

        List<OrgMember> memberships = orgMemberRepository.findByUserId(me.userId());
        List<OrgMembershipDto> orgMembershipDtos = memberships.stream()
                .map(m -> {
                    String slug = null;
                    String name = null;
                    Optional<Organization> o = organizationRepository.findById(m.getOrgId());
                    if (o.isPresent()) {
                        slug = o.get().getSlug();
                        name = o.get().getName();
                    }
                    return new OrgMembershipDto(m.getOrgId(), slug, name, m.getRole().name());
                })
                .toList();

        return new MeResponse(UserDto.from(user), orgMembershipDtos, me.authorities());
    }

    private void recordLoginFailed(UUID userId, String email, String reason, String ip) {
        // Written directly (login throws before the aspect's @AfterReturning success path) and
        // fail-open (login must still return 401 even if the audit write hiccups). Mark recorded so
        // the aspect's @AfterThrowing does not write a duplicate FAILED row.
        auditWriter.record(userId, null, "auth.login.failed", "user",
                userId == null ? null : userId.toString(),
                Map.of("email", maskEmail(email), "reason", reason), ip, AuditOutcome.FAILED, false);
        AuditContext.markRecorded();
    }

    private static String maskEmail(String email) {
        if (email == null || email.isBlank()) {
            return "";
        }
        int at = email.indexOf('@');
        if (at <= 1) {
            return "***" + (at >= 0 ? email.substring(at) : "");
        }
        return email.charAt(0) + "***" + email.substring(at);
    }

    private String generateRawToken() {
        byte[] buf = new byte[RESET_TOKEN_BYTES];
        new SecureRandom().nextBytes(buf);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(buf);
    }

    private String sha256(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] out = md.digest(s.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(out);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    public record LoginRequest(
            @NotBlank @Email String email,
            @NotBlank String password) {}

    /**
     * Unified login result. For a non-MFA user (or step-2 completion) it carries the full session
     * ({@code accessToken}, {@code expiresAt}, {@code user}) with {@code mfaRequired=false}. For an
     * MFA-enabled user step-1 returns {@code mfaRequired=true} with a short-lived {@code mfaChallenge}
     * (and {@code mfaChallengeExpiresAt}) and NO session token — the client must call
     * {@code /api/v1/auth/mfa/login} with a code to obtain a session.
     */
    @com.fasterxml.jackson.annotation.JsonInclude(
            com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    public record LoginResponse(String accessToken, Instant expiresAt, UserDto user,
                                boolean mfaRequired, String mfaChallenge, Instant mfaChallengeExpiresAt) {

        static LoginResponse session(String accessToken, Instant expiresAt, UserDto user) {
            return new LoginResponse(accessToken, expiresAt, user, false, null, null);
        }

        static LoginResponse mfaChallenge(String challenge, Instant challengeExpiresAt) {
            return new LoginResponse(null, null, null, true, challenge, challengeExpiresAt);
        }
    }

    public record MfaLoginRequest(
            @NotBlank String challenge,
            @NotBlank String code) {}

    public record PasswordResetRequest(@NotBlank @Email String email) {}

    public record PasswordResetConfirm(
            @NotBlank String token,
            @NotBlank @Size(min = 8, max = 255) String newPassword) {}

    public record RegisterRequest(
            @NotBlank @Size(max = 255) String fullName,
            @NotBlank @Email @Size(max = 320) String email,
            @NotBlank @Size(min = 12, max = 72) String password,
            @NotBlank @Size(max = 255) String orgName) {}

    @com.fasterxml.jackson.annotation.JsonInclude(
            com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    public record RegisterResponse(String accessToken, Instant expiresAt, UserDto user, String orgSlug,
                                   boolean emailVerificationSent, String verificationToken) {}

    public record VerifyEmailRequest(@NotBlank String token) {}

    public record OrgMembershipDto(UUID orgId, String slug, String name, String role) {}

    public record MeResponse(UserDto user, List<OrgMembershipDto> orgs, Set<String> permissions) {}
}
