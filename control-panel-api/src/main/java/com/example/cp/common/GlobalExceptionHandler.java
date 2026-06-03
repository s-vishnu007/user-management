package com.example.cp.common;

import com.example.cp.audit.AuditOutcome;
import com.example.cp.audit.AuditWriter;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.context.request.WebRequest;

import java.net.URI;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    private final AuditWriter auditWriter;
    private final TrustedProxyResolver proxyResolver;

    public GlobalExceptionHandler(AuditWriter auditWriter, TrustedProxyResolver proxyResolver) {
        this.auditWriter = auditWriter;
        this.proxyResolver = proxyResolver;
    }

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ProblemDetail> handleApi(ApiException ex, WebRequest req) {
        // ApiException details are intentionally caller-safe — pass through unchanged.
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(ex.getStatus(), ex.getDetail());
        pd.setTitle(ex.getTitle());
        if (ex.getType() != null && !"about:blank".equals(ex.getType())) {
            pd.setType(URI.create(ex.getType()));
        }
        return ResponseEntity.status(ex.getStatus()).body(pd);
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ProblemDetail> handleAccessDenied(AccessDeniedException ex) {
        // Do NOT leak which resource/why; log server-side for forensics.
        log.warn("Access denied: {}", ex.getMessage());
        recordDenied("access.denied", ex);
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN, "Access is denied");
        pd.setTitle("Forbidden");
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(pd);
    }

    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<ProblemDetail> handleAuth(AuthenticationException ex) {
        // Avoid leaking provider internals.
        log.warn("Authentication failure: {}", ex.getMessage());
        recordDenied("auth.denied", ex);
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED, "Authentication required");
        pd.setTitle("Unauthorized");
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(pd);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ProblemDetail> handleValidation(MethodArgumentNotValidException ex) {
        // Field names + author-controlled bean-validation messages are safe to surface.
        Map<String, String> fieldErrors = new LinkedHashMap<>();
        List<FieldError> errors = ex.getBindingResult().getFieldErrors();
        for (FieldError fe : errors) {
            fieldErrors.put(fe.getField(), fe.getDefaultMessage() == null ? "invalid" : fe.getDefaultMessage());
        }
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Validation failed");
        pd.setTitle("Bad Request");
        pd.setProperty("errors", fieldErrors);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(pd);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ProblemDetail> handleIllegal(IllegalArgumentException ex) {
        // Do NOT echo the raw message (may contain arbitrary internal strings); log it instead.
        // Caller-facing messages must be routed through ApiException, which is intentionally safe.
        log.warn("IllegalArgumentException", ex);
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Bad request");
        pd.setTitle("Bad Request");
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(pd);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ProblemDetail> handleGeneric(Exception ex) {
        log.error("Unhandled exception", ex);
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR, "An unexpected error occurred");
        pd.setTitle("Internal Server Error");
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(pd);
    }

    /**
     * Fallback DENIED audit write for authn/authz refusals thrown OUTSIDE the controller mutating
     * pointcut (e.g. GET endpoints, filters). Skipped when the {@code AuditInterceptor} aspect
     * already recorded a row for this request (the {@code isRecorded} sentinel) to avoid duplicates.
     */
    private void recordDenied(String defaultAction, Throwable ex) {
        try {
            if (AuditContext.isRecorded()) {
                return;
            }
            String action = AuditContext.currentAction();
            if (action == null || action.isBlank()) {
                action = defaultAction;
            }
            Map<String, Object> payload = new HashMap<>(AuditContext.currentPayload());
            payload.put("error.class", ex.getClass().getSimpleName());
            String ip = AuditContext.currentIp() != null
                    ? AuditContext.currentIp()
                    : proxyResolver.resolveClientIp(currentRequest());
            auditWriter.record(AuditContext.currentActorUserId(), AuditContext.currentActorOrgId(),
                    action, AuditContext.currentTargetType(), AuditContext.currentTargetId(),
                    payload, ip, AuditOutcome.DENIED, false);
            AuditContext.markRecorded();
        } catch (Exception e) {
            log.warn("Failed to write fallback DENIED audit row: {}", e.getMessage());
        }
    }

    private HttpServletRequest currentRequest() {
        try {
            ServletRequestAttributes attrs = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
            return attrs == null ? null : attrs.getRequest();
        } catch (Exception e) {
            return null;
        }
    }
}
