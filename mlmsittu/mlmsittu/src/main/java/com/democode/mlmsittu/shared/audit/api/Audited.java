package com.democode.mlmsittu.shared.audit.api;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a service method whose successful completion must land in {@code audit_log}.
 *
 * <p>Architecture §8.2: audit writing lives in one aspect, not scattered through business logic.
 * The method body says <em>what</em> changed via {@link AuditContext#record}; the aspect supplies
 * actor, IP and timestamp and performs the insert inside the caller's transaction.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface Audited {

    /** Stable verb recorded in {@code audit_log.action}, e.g. {@code USER_ROLES_CHANGED}. */
    String action();

    /** Table or aggregate the action applies to, e.g. {@code app_user}. */
    String entityType();

    /**
     * Also record a row when the method throws, using {@code action + "_FAILED"}.
     *
     * <p>Off by default — most failures are ordinary validation noise. Turn it on where the
     * failure is itself the interesting security signal: a rejected login, a blocked self-review,
     * a duplicate bank reference. Failure rows are written in their own transaction so they
     * survive the rollback of the change that failed.
     */
    boolean auditFailures() default false;
}
