package com.example.cp.mfa;

import com.example.cp.common.ApiException;
import com.example.cp.common.AuditContext;
import com.example.cp.common.AuthenticatedUser;
import com.example.cp.common.SecurityUtils;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * TOTP MFA self-service for the authenticated user: enroll, confirm (verify), and disable. The
 * two-step <em>login</em> completion lives in {@code AuthController} ({@code /api/v1/auth/mfa/login})
 * since it must be reachable without a full session; these endpoints all require an authenticated
 * session and act on the caller's own account.
 */
@RestController
@RequestMapping("/api/v1/auth/mfa")
public class MfaController {

    private final MfaService mfaService;

    public MfaController(MfaService mfaService) {
        this.mfaService = mfaService;
    }

    /**
     * Starts enrollment for the current user. Returns the base32 secret and {@code otpauth://} URI
     * to load into an authenticator app. The secret is shown ONCE; MFA is not active until the user
     * confirms a code via {@link #verify}.
     */
    @PostMapping("/enroll")
    public EnrollResponse enroll() {
        AuthenticatedUser me = SecurityUtils.requireUser();
        MfaService.EnrollmentResult result = mfaService.enroll(me.userId(), me.email());
        AuditContext.set("auth.mfa.enroll");
        AuditContext.setTarget("user", me.userId().toString());
        return new EnrollResponse(result.secret(), result.otpAuthUri());
    }

    /** Confirms enrollment by verifying a code; on success MFA becomes active for the user. */
    @PostMapping("/verify")
    public ResponseEntity<Void> verify(@Valid @RequestBody CodeRequest body) {
        AuthenticatedUser me = SecurityUtils.requireUser();
        boolean ok = mfaService.confirmEnrollment(me.userId(), body.code());
        if (!ok) {
            AuditContext.set("auth.mfa.verify.failed");
            AuditContext.setTarget("user", me.userId().toString());
            AuditContext.setOutcome(com.example.cp.audit.AuditOutcome.FAILED);
            throw ApiException.badRequest("Invalid code");
        }
        AuditContext.set("auth.mfa.enabled");
        AuditContext.setTarget("user", me.userId().toString());
        return ResponseEntity.noContent().build();
    }

    /** Disables MFA for the current user. Idempotent. */
    @PostMapping("/disable")
    public ResponseEntity<Void> disable() {
        AuthenticatedUser me = SecurityUtils.requireUser();
        mfaService.disable(me.userId());
        AuditContext.set("auth.mfa.disabled");
        AuditContext.setTarget("user", me.userId().toString());
        return ResponseEntity.noContent().build();
    }

    public record EnrollResponse(String secret, String otpAuthUri) {}

    public record CodeRequest(@NotBlank String code) {}
}
