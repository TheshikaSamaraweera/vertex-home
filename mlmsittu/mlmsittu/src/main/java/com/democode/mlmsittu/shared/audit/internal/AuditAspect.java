package com.democode.mlmsittu.shared.audit.internal;

import com.democode.mlmsittu.shared.audit.api.AuditActor;
import com.democode.mlmsittu.shared.audit.api.AuditContext;
import com.democode.mlmsittu.shared.audit.api.Audited;
import com.democode.mlmsittu.shared.error.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import java.util.UUID;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.core.annotation.Order;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * Writes one {@code audit_log} row per successful {@link Audited} method (architecture §8.2).
 *
 * <p><b>Ordering matters.</b> {@code AuditConfig} pins the transaction advisor at order 100 and
 * this aspect sits at 200, so the audit insert runs <em>inside</em> the business transaction. If
 * the business change rolls back, so does its audit row — an audit trail describing writes that
 * never happened is worse than none.
 */
@Aspect
@Component
@Order(200)
public class AuditAspect {

    private final AuditLogWriter writer;

    public AuditAspect(AuditLogWriter writer) {
        this.writer = writer;
    }

    @Around("@annotation(audited)")
    public Object recordAudit(ProceedingJoinPoint joinPoint, Audited audited) throws Throwable {
        // A previous failure on this thread may have left a snapshot behind.
        AuditContext.clear();

        Object result;
        try {
            result = joinPoint.proceed();
        } catch (Throwable failure) {
            if (audited.auditFailures()) {
                recordFailure(audited, failure);
            }
            AuditContext.clear();
            throw failure;
        }

        AuditContext.Entry entry = AuditContext.consume();
        writer.write(
                currentActorId(),
                audited.action(),
                audited.entityType(),
                entry == null ? null : entry.entityId(),
                entry == null ? null : entry.before(),
                entry == null ? null : entry.after(),
                currentIp());

        return result;
    }

    /**
     * Records a rejected action. The reason recorded is the machine-readable error code, never the
     * exception message — messages leak internals and change without notice, and this row may be
     * read years later during a breach investigation.
     */
    private void recordFailure(Audited audited, Throwable failure) {
        AuditContext.Entry entry = AuditContext.consume();
        String reason =
                failure instanceof ApiException apiException
                        ? apiException.getCode()
                        : "UNEXPECTED_ERROR";

        writer.writeIndependently(
                currentActorId(),
                audited.action() + "_FAILED",
                audited.entityType(),
                entry == null ? null : entry.entityId(),
                null,
                Map.of("reason", reason),
                currentIp());
    }

    private UUID currentActorId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null
                || !authentication.isAuthenticated()
                || !(authentication.getPrincipal() instanceof AuditActor actor)) {
            // Unauthenticated actions (a failed login, a scheduled job) still deserve a row.
            return null;
        }
        return actor.auditActorId();
    }

    private String currentIp() {
        if (RequestContextHolder.getRequestAttributes()
                instanceof ServletRequestAttributes attributes) {
            HttpServletRequest request = attributes.getRequest();
            return request.getRemoteAddr();
        }
        return null;
    }
}
