package com.example.licenseverifier.spring;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Guards a method or type so it executes only when the active license grants
 * the named permission. Method-level annotations override type-level ones.
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface RequiresPermission {

    /** The single permission code that must be present in the license. */
    String value() default "";

    /** If non-empty, the call is permitted when any one of these codes is granted. */
    String[] anyOf() default {};

    /**
     * If true, this call is a read-only operation and is allowed when the license
     * is in READ_ONLY (expired-but-tolerated) mode. Defaults to false so that
     * mutating endpoints are blocked once the license expires.
     */
    boolean readOnly() default false;
}
