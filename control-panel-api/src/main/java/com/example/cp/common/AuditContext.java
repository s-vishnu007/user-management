package com.example.cp.common;

import com.example.cp.audit.AuditOutcome;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public final class AuditContext {

    private AuditContext() {}

    private static final ThreadLocal<Ctx> CTX = ThreadLocal.withInitial(Ctx::new);

    public static void set(String action) {
        CTX.get().action = action;
    }

    public static void setTarget(String type, String id) {
        Ctx c = CTX.get();
        c.targetType = type;
        c.targetId = id;
    }

    public static void putPayload(String key, Object value) {
        CTX.get().payload.put(key, value);
    }

    public static void setActor(UUID userId, UUID orgId) {
        Ctx c = CTX.get();
        c.actorUserId = userId;
        c.actorOrgId = orgId;
    }

    public static void setIp(String ip) {
        CTX.get().ip = ip;
    }

    public static void setOutcome(AuditOutcome o) {
        CTX.get().outcome = o == null ? AuditOutcome.SUCCESS : o;
    }

    public static void markFailClosed() {
        CTX.get().failClosed = true;
    }

    /**
     * Sentinel set by an explicit audit write so the {@code AuditInterceptor} advice and
     * {@code GlobalExceptionHandler} fallback skip writing a duplicate row for the same request.
     */
    public static void markRecorded() {
        CTX.get().recorded = true;
    }

    public static String currentAction() {
        return CTX.get().action;
    }

    public static String currentTargetType() {
        return CTX.get().targetType;
    }

    public static String currentTargetId() {
        return CTX.get().targetId;
    }

    public static UUID currentActorUserId() {
        return CTX.get().actorUserId;
    }

    public static UUID currentActorOrgId() {
        return CTX.get().actorOrgId;
    }

    public static Map<String, Object> currentPayload() {
        return CTX.get().payload;
    }

    public static String currentIp() {
        return CTX.get().ip;
    }

    public static AuditOutcome currentOutcome() {
        return CTX.get().outcome;
    }

    public static boolean isFailClosed() {
        return CTX.get().failClosed;
    }

    public static boolean isRecorded() {
        return CTX.get().recorded;
    }

    public static void clear() {
        CTX.remove();
    }

    private static class Ctx {
        String action;
        String targetType;
        String targetId;
        UUID actorUserId;
        UUID actorOrgId;
        String ip;
        AuditOutcome outcome = AuditOutcome.SUCCESS;
        boolean failClosed = false;
        boolean recorded = false;
        Map<String, Object> payload = new HashMap<>();
    }
}
