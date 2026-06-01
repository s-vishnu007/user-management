package com.example.licenseverifier.spring;

import com.example.licenseverifier.License;
import java.lang.reflect.Method;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;

@Aspect
public class RequiresPermissionAspect {

    private final LicenseService licenseService;

    public RequiresPermissionAspect(LicenseService licenseService) {
        this.licenseService = licenseService;
    }

    @Around("@annotation(com.example.licenseverifier.spring.RequiresPermission) "
            + "|| @within(com.example.licenseverifier.spring.RequiresPermission)")
    public Object check(ProceedingJoinPoint pjp) throws Throwable {
        RequiresPermission rp = resolveAnnotation(pjp);
        if (rp == null) {
            return pjp.proceed();
        }

        LicenseService.Status status = licenseService.status();
        if (status == LicenseService.Status.NOT_LOADED
                || status == LicenseService.Status.EXPIRED) {
            throw new LicensePermissionDeniedException(
                    requiredCode(rp),
                    "License is not active (status=" + status + ")");
        }
        if (status == LicenseService.Status.READ_ONLY && !rp.readOnly()) {
            throw new LicensePermissionDeniedException(
                    requiredCode(rp),
                    "License is in READ_ONLY mode; mutating operation rejected");
        }

        License license = licenseService.current();
        if (!hasAccess(license, rp)) {
            throw new LicensePermissionDeniedException(requiredCode(rp));
        }
        return pjp.proceed();
    }

    private static RequiresPermission resolveAnnotation(ProceedingJoinPoint pjp) {
        MethodSignature sig = (MethodSignature) pjp.getSignature();
        Method method = sig.getMethod();
        RequiresPermission rp = method.getAnnotation(RequiresPermission.class);
        if (rp != null) {
            return rp;
        }
        Class<?> target = pjp.getTarget().getClass();
        rp = target.getAnnotation(RequiresPermission.class);
        if (rp != null) {
            return rp;
        }
        return method.getDeclaringClass().getAnnotation(RequiresPermission.class);
    }

    private static boolean hasAccess(License license, RequiresPermission rp) {
        if (rp.anyOf().length > 0) {
            for (String code : rp.anyOf()) {
                if (!code.isEmpty() && license.hasPermission(code)) {
                    return true;
                }
            }
            if (!rp.value().isEmpty() && license.hasPermission(rp.value())) {
                return true;
            }
            return false;
        }
        if (rp.value().isEmpty()) {
            return true;
        }
        return license.hasPermission(rp.value());
    }

    private static String requiredCode(RequiresPermission rp) {
        if (!rp.value().isEmpty()) {
            return rp.value();
        }
        if (rp.anyOf().length > 0) {
            return String.join("|", rp.anyOf());
        }
        return "(unspecified)";
    }
}
