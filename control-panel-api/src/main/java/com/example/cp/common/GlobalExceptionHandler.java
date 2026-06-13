package com.example.cp.common;

import com.example.cp.audit.AuditOutcome;
import com.example.cp.audit.AuditWriter;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
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
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import java.net.URI;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Central API error mapper.
 *
 * <p>Extends {@link ResponseEntityExceptionHandler} so the framework's standard Spring MVC
 * exceptions are mapped to their correct HTTP status (and rendered as RFC-7807 {@link ProblemDetail}
 * bodies) instead of falling through to the catch-all 500. That base class covers, among others:</p>
 * <ul>
 *   <li>{@code HttpMessageNotReadableException} (malformed/empty JSON body) &rarr; 400;</li>
 *   <li>{@code HttpRequestMethodNotSupportedException} (wrong HTTP method) &rarr; 405;</li>
 *   <li>{@code HttpMediaTypeNotSupportedException} (wrong {@code Content-Type}) &rarr; 415;</li>
 *   <li>{@code HttpMediaTypeNotAcceptableException} (unsatisfiable {@code Accept}) &rarr; 406;</li>
 *   <li>{@code MissingServletRequestParameterException} / {@code MissingPathVariableException} &rarr; 400;</li>
 *   <li>{@code MethodArgumentTypeMismatchException} / {@code TypeMismatchException}
 *       (e.g. a non-UUID value bound to a {@code UUID} path variable) &rarr; 400;</li>
 *   <li>{@code NoHandlerFoundException} / {@code NoResourceFoundException} (unknown subpath) &rarr; 404.</li>
 * </ul>
 *
 * <p>The application-specific {@code @ExceptionHandler} methods below take precedence over the base
 * class for the types they declare; {@link #handleGeneric(Exception)} remains the last-resort 500 for
 * genuinely unexpected failures only.</p>
 */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    private final AuditWriter auditWriter;
    private final TrustedProxyResolver proxyResolver;

    public GlobalExceptionHandler(AuditWriter auditWriter, TrustedProxyResolver proxyResolver) {
        this.auditWriter = auditWriter;
        this.proxyResolver = proxyResolver;
    }

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ProblemDetail> handleApi(ApiException ex, WebRequest req) {
        // Authorization refusals raised inside a controller (e.g. RBAC/tenant checks throwing 403)
        // must be auditable. The @AfterThrowing aspect's write happens inside the controller's
        // rolling-back @Transactional; recording here — after the business tx has unwound — commits
        // a durable DENIED row (REQUIRES_NEW).
        int status = ex.getStatus() != null ? ex.getStatus().value() : 500;
        if (status == 401 || status == 403) {
            recordDenied("request.denied", ex);
        }
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

    /**
     * Optimistic-locking conflict: a concurrent writer bumped the {@code @Version} (or a versioned
     * row vanished) between our load and flush. This is a transient, retryable race — surface it as
     * {@code 409 Conflict} (NOT a 500) with a retry hint so a well-behaved client simply re-reads and
     * retries rather than treating it as a server fault.
     */
    @ExceptionHandler(OptimisticLockingFailureException.class)
    public ResponseEntity<ProblemDetail> handleOptimisticLock(OptimisticLockingFailureException ex) {
        log.warn("Optimistic locking failure: {}", ex.getMessage());
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT,
                "The resource was modified concurrently. Re-read the latest state and retry.");
        pd.setTitle("Conflict");
        pd.setProperty("retryable", true);
        return ResponseEntity.status(HttpStatus.CONFLICT).body(pd);
    }

    /**
     * Database integrity violation — almost always a check-then-insert race losing the UNIQUE
     * constraint (two requests both passed an existence check, only one INSERT can win). Map to
     * {@code 409 Conflict} rather than 500: the resource already exists / conflicts with the request.
     * The raw constraint message is logged but never echoed (it can leak schema internals).
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ProblemDetail> handleDataIntegrity(DataIntegrityViolationException ex) {
        log.warn("Data integrity violation: {}", ex.getMostSpecificCause().getMessage());
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT,
                "The request conflicts with the current state of the resource.");
        pd.setTitle("Conflict");
        return ResponseEntity.status(HttpStatus.CONFLICT).body(pd);
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

    /**
     * Bean-validation failures on {@code @Valid @RequestBody} arguments. Overrides the
     * {@link ResponseEntityExceptionHandler} hook so the per-field errors (field name + the
     * author-controlled bean-validation message, both safe to surface) are attached to the
     * {@code 400} ProblemDetail.
     */
    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException ex,
                                                                  HttpHeaders headers,
                                                                  HttpStatusCode status,
                                                                  WebRequest request) {
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
            // (the actual write + markRecorded happen below; AuditContext is cleared in finally)
            String action = AuditContext.currentAction();
            if (action == null || action.isBlank()) {
                action = defaultAction;
            }
            Map<String, Object> payload = new HashMap<>(AuditContext.currentPayload());
            payload.put("error.class", ex.getClass().getSimpleName());
            String ip = AuditContext.currentIp() != null
                    ? AuditContext.currentIp()
                    : proxyResolver.resolveClientIp(currentRequest());
            // Prefer the AuditContext actor; fall back to the security principal (the @AfterThrowing
            // aspect may have already cleared AuditContext before this handler runs).
            java.util.UUID actorId = AuditContext.currentActorUserId();
            if (actorId == null) {
                actorId = SecurityUtils.currentUser().map(AuthenticatedUser::userId).orElse(null);
            }
            auditWriter.record(actorId, AuditContext.currentActorOrgId(),
                    action, AuditContext.currentTargetType(), AuditContext.currentTargetId(),
                    payload, ip, AuditOutcome.DENIED, false);
            AuditContext.markRecorded();
        } catch (Exception e) {
            log.warn("Failed to write fallback DENIED audit row: {}", e.getMessage());
        } finally {
            // This exception handler is the terminal step for a failed request. Clear the AuditContext
            // ThreadLocal so the markRecorded sentinel cannot leak to the next request on this pooled
            // thread (which would otherwise suppress that request's audit row).
            AuditContext.clear();
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
