package com.example.cp.audit;

import com.example.cp.common.AuditContext;
import com.example.cp.common.AuthenticatedUser;
import com.example.cp.common.SecurityUtils;
import jakarta.servlet.http.HttpServletRequest;
import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.annotation.AfterReturning;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Pointcut;
import org.aspectj.lang.reflect.MethodSignature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Aspect
@Component
public class AuditInterceptor {

    private static final Logger log = LoggerFactory.getLogger(AuditInterceptor.class);

    private final AuditWriter writer;

    public AuditInterceptor(AuditWriter writer) {
        this.writer = writer;
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
            String action = AuditContext.currentAction();
            String targetType = AuditContext.currentTargetType();
            String targetId = AuditContext.currentTargetId();
            Map<String, Object> payload = AuditContext.currentPayload();

            HttpServletRequest req = currentRequest().orElse(null);
            String ip = AuditContext.currentIp() != null ? AuditContext.currentIp() : extractIp(req);

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
            writer.record(userId, orgId, action, targetType, targetId, safePayload, ip);
        } catch (Exception e) {
            log.warn("AuditInterceptor failed: {}", e.getMessage());
        } finally {
            AuditContext.clear();
        }
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

    static String extractIp(HttpServletRequest req) {
        if (req == null) return null;
        String fwd = req.getHeader("X-Forwarded-For");
        if (fwd != null && !fwd.isBlank()) {
            int comma = fwd.indexOf(',');
            return comma >= 0 ? fwd.substring(0, comma).trim() : fwd.trim();
        }
        return req.getRemoteAddr();
    }
}
