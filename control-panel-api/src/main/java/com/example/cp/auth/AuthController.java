package com.example.cp.auth;

import com.example.cp.audit.AuditOutcome;
import com.example.cp.audit.AuditWriter;
import com.example.cp.common.ApiException;
import com.example.cp.common.AuditContext;
import com.example.cp.common.AuthenticatedUser;
import com.example.cp.common.Ids;
import com.example.cp.common.SecurityUtils;
import com.example.cp.common.TrustedProxyResolver;
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
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
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
    private final LoginAttempt loginAttempt;
    private final OrgMemberRepository orgMemberRepository;
    private final OrganizationRepository organizationRepository;
    private final SessionRevocationStore revocationStore;
    private final AuditWriter auditWriter;
    private final TrustedProxyResolver proxyResolver;
    private final boolean exposeResetToken;

    public AuthController(UserRepository userRepository,
                          UserService userService,
                          PasswordEncoder passwordEncoder,
                          SessionTokenService tokenService,
                          AuthoritiesLoader authoritiesLoader,
                          PasswordResetTokenRepository resetTokenRepository,
                          LoginAttempt loginAttempt,
                          OrgMemberRepository orgMemberRepository,
                          OrganizationRepository organizationRepository,
                          SessionRevocationStore revocationStore,
                          AuditWriter auditWriter,
                          TrustedProxyResolver proxyResolver,
                          @Value("${app.auth.expose-reset-token:false}") boolean exposeResetToken) {
        this.userRepository = userRepository;
        this.userService = userService;
        this.passwordEncoder = passwordEncoder;
        this.tokenService = tokenService;
        this.authoritiesLoader = authoritiesLoader;
        this.resetTokenRepository = resetTokenRepository;
        this.loginAttempt = loginAttempt;
        this.orgMemberRepository = orgMemberRepository;
        this.organizationRepository = organizationRepository;
        this.revocationStore = revocationStore;
        this.auditWriter = auditWriter;
        this.proxyResolver = proxyResolver;
        this.exposeResetToken = exposeResetToken;
    }

    @PostMapping("/login")
    @Transactional
    public LoginResponse login(@Valid @RequestBody LoginRequest body, HttpServletRequest request) {
        String email = body.email() == null ? "" : body.email().trim();
        String ip = proxyResolver.resolveClientIp(request);
        if (loginAttempt.isLocked(email)) {
            // Explicit DENIED write before throwing; mark recorded so the aspect does not duplicate.
            auditWriter.record(null, null, "auth.login.locked", "user", null,
                    Map.of("email", maskEmail(email)), ip, AuditOutcome.DENIED, false);
            AuditContext.markRecorded();
            throw ApiException.unauthorized("Too many failed attempts; try again later");
        }
        Optional<User> userOpt = userRepository.findByEmail(email);
        if (userOpt.isEmpty()) {
            recordLoginFailed(null, email, "unknown_user", ip);
            loginAttempt.recordFailure(email);
            throw ApiException.unauthorized("Invalid email or password");
        }
        User user = userOpt.get();
        if (user.getStatus() != User.Status.ACTIVE) {
            recordLoginFailed(user.getId(), email, "inactive", ip);
            loginAttempt.recordFailure(email);
            throw ApiException.unauthorized("Account is not active");
        }
        if (user.getPasswordHash() == null || !passwordEncoder.matches(body.password(), user.getPasswordHash())) {
            recordLoginFailed(user.getId(), email, "bad_password", ip);
            loginAttempt.recordFailure(email);
            throw ApiException.unauthorized("Invalid email or password");
        }

        loginAttempt.recordSuccess(email);

        Set<String> authorities = authoritiesLoader.authoritiesFor(user.getId(), null, user.isSuperAdmin());
        SessionTokenService.IssuedToken issued = tokenService.issue(
                user.getId(), user.getEmail(), user.isSuperAdmin(), authorities, user.getTokenVersion());

        user.setLastLoginAt(OffsetDateTime.now());
        userRepository.save(user);

        AuditContext.set("auth.login");
        AuditContext.setTarget("user", user.getId().toString());

        return new LoginResponse(issued.token(), issued.expiresAt(), UserDto.from(user));
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(HttpServletRequest request) {
        // Stateless JWT — denylist the exact jti for its remaining life so the token cannot be reused.
        SecurityUtils.currentUser().ifPresent(u ->
                AuditContext.setTarget("user", u.userId().toString()));
        AuditContext.set("auth.logout");

        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header != null && header.startsWith(BEARER)) {
            String token = header.substring(BEARER.length()).trim();
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
            }
        }
        return ResponseEntity.noContent().build();
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
            throw ApiException.badRequest("Token already used");
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

    public record LoginResponse(String accessToken, Instant expiresAt, UserDto user) {}

    public record PasswordResetRequest(@NotBlank @Email String email) {}

    public record PasswordResetConfirm(
            @NotBlank String token,
            @NotBlank @Size(min = 8, max = 255) String newPassword) {}

    public record OrgMembershipDto(UUID orgId, String slug, String name, String role) {}

    public record MeResponse(UserDto user, List<OrgMembershipDto> orgs, Set<String> permissions) {}
}
