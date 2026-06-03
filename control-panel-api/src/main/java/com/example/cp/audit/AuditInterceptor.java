package com.example.cp.audit;

import com.example.cp.common.AuditContext;
import com.example.cp.common.AuditProperties;
import com.example.cp.common.AuthenticatedUser;
import com.example.cp.common.SecurityUtils;
import com.example.cp.common.TrustedProxyResolver;
import jakarta.servlet.http.HttpServletRequest;
import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.annotation.AfterReturning;
import org.aspectj.lang.annotation.AfterThrowing;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Pointcut;
import org.aspectj.lang.reflect.MethodSignature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import com.example.cp.common.ApiException;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Aspect
@Component
public class AuditInterceptor {

    private static final Logger log = LoggerFactory.getLogger(AuditInterceptor.class);

    /** Max length of the {@code error.message} captured in payloads (avoid logging secrets / huge text). */
    private static final int MAX_ERROR_MSG = 240;

    private final AuditWriter writer;
    private final TrustedProxyResolver proxyResolver;
    private final Set<String> failClosedActions;

    public AuditInterceptor(AuditWriter writer, TrustedProxyResolver proxyResolver, AuditProperties props) {
        this.writer = writer;
        this.proxyResolver = proxyResolver;
        this.failClosedActions = props != null && props.getFailClosedActions() != null
                ? new HashSet<>(props.getFailClosedActions())
                : new HashSet<>();
    }

    @Pointcut("within(com.example.cp..*Controller)")
    public void inControllerLayer() {}

    @Pointcut("@annotation(org.springframework.web.bind.annotation.PostMapping)" +
            " || @annotation(org.springframework.web.bind.annotation.PutMapping)" +
            " || @annotation(org.springframework.web.bind.annotation.PatchMapping)" +
            " || @annotation(org.springframework.web.bind.annotation.DeleteMapping)")
    public void mutatingEndpoint() {}

    @AfterReturning(pointcut = "inControllerLayer() && mutatingEndpoint()", returning = "ret")
    public void afterMutating(JoinPoint jp, Object ret) {
        try {
            // An explicit write already happened for this request (e.g. fail-closed SUCCESS in a
            // service method, or AuthController). Do not duplicate.
            if (AuditContext.isRecorded()) {
                return;
            }
            emit(jp, AuditOutcome.SUCCESS, null);
        } catch (Exception e) {
            log.warn("AuditInterceptor afterMutating failed: {}", e.getMessage());
        } finally {
            AuditContext.clear();
        }
    }

    @AfterThrowing(pointcut = "inControllerLayer() && mutatingEndpoint()", throwing = "ex")
    public void afterThrowing(JoinPoint jp, Throwable ex) {
        try {
            // Honor the sentinel: an explicit write (e.g. AuthController login.failed) already
            // recorded this; skip to avoid duplicate rows.
            if (AuditContext.isRecorded()) {
                return;
            }
            emit(jp, outcomeFor(ex), ex);
        } catch (Exception e) {
            log.warn("AuditInterceptor afterThrowing failed: {}", e.getMessage());
        } finally {
            AuditContext.clear();
        }
        // NOTE: advice intentionally does not swallow ex; @AfterThrowing lets it propagate
        // to GlobalExceptionHandler.
    }

    /**
     * Shared write path for both the SUCCESS (afterReturning) and DENIED/FAILED (afterThrowing)
     * advices. Derives actor/target/action/ip from {@link AuditContext} (falling back to the
     * request and security context) and writes a single canonical audit row.
     */
    private void emit(JoinPoint jp, AuditOutcome outcome, Throwable ex) {
        String action = AuditContext.currentAction();
        String targetType = AuditContext.currentTargetType();
        String targetId = AuditContext.currentTargetId();
        Map<String, Object> payload = AuditContext.currentPayload();

        HttpServletRequest req = currentRequest().orElse(null);
        String ip = AuditContext.currentIp() != null ? AuditContext.currentIp() : proxyResolver.resolveClientIp(req);

        if (action == null || action.isBlank()) {
            action = deriveAction(jp, req);
        }

        UUID userId = AuditContext.currentActorUserId();
        UUID orgId = AuditContext.currentActorOrgId();
        if (userId == null) {
            Optional<AuthenticatedUser> u = SecurityUtils.currentUser();
            if (u.isPresent()) userId = u.get().userId();
        }

        Map<String, Object> safePayload = payload == null ? new HashMap<>() : new HashMap<>(payload);
        if (ex != null) {
            safePayload.put("error.class", ex.getClass().getSimpleName());
            safePayload.put("error.message", truncate(ex.getMessage()));
            Integer status = httpStatusOf(ex);
            if (status != null) {
                safePayload.put("http.status", status);
            }
        }

        boolean failClosed = action != null && failClosedActions.contains(action);
        writer.record(userId, orgId, action, targetType, targetId, safePayload, ip, outcome, failClosed);
    }

    /**
     * Maps a thrown exception to an outcome: authn/authz refusals are DENIED, everything else is
     * FAILED.
     */
    private AuditOutcome outcomeFor(Throwable ex) {
        if (ex instanceof AccessDeniedException || ex instanceof AuthenticationException) {
            return AuditOutcome.DENIED;
        }
        if (ex instanceof ApiException api) {
            int code = api.getStatus() != null ? api.getStatus().value() : 0;
            if (code == 401 || code == 403) {
                return AuditOutcome.DENIED;
            }
        }
        return AuditOutcome.FAILED;
    }

    private Integer httpStatusOf(Throwable ex) {
        if (ex instanceof ApiException api && api.getStatus() != null) {
            return api.getStatus().value();
        }
        if (ex instanceof AccessDeniedException) {
            return 403;
        }
        if (ex instanceof AuthenticationException) {
            return 401;
        }
        return null;
    }

    private static String truncate(String msg) {
        if (msg == null) {
            return null;
        }
        return msg.length() <= MAX_ERROR_MSG ? msg : msg.substring(0, MAX_ERROR_MSG) + "...";
    }

    private String deriveAction(JoinPoint jp, HttpServletRequest req) {
        MethodSignature sig = (MethodSignature) jp.getSignature();
        Method m = sig.getMethod();
        String http = httpVerb(m);
        String path = req != null ? req.getRequestURI() : sig.getDeclaringType().getSimpleName() + "." + m.getName();
        return (http + " " + path).trim();
    }

    private String httpVerb(Method m) {
        if (m.isAnnotationPresent(PostMapping.class)) return "POST";
        if (m.isAnnotationPresent(PutMapping.class)) return "PUT";
        if (m.isAnnotationPresent(PatchMapping.class)) return "PATCH";
        if (m.isAnnotationPresent(DeleteMapping.class)) return "DELETE";
        if (m.isAnnotationPresent(RequestMapping.class)) return "REQUEST";
        return "ACTION";
    }

    private Optional<HttpServletRequest> currentRequest() {
        try {
            ServletRequestAttributes attrs = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
            return Optional.ofNullable(attrs).map(ServletRequestAttributes::getRequest);
        } catch (Exception e) {
            return Optional.empty();
        }
    }
}
