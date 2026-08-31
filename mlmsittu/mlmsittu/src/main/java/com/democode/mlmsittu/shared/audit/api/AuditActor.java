package com.democode.mlmsittu.shared.audit.api;

import java.util.UUID;

/**
 * Implemented by whatever object modules place in the Spring Security context as the principal.
 *
 * <p>The contract lives in {@code shared} so the audit aspect can identify the actor without
 * depending on {@code identity} — dependencies point at the shared kernel, never the other way.
 */
public interface AuditActor {

    /** The {@code app_user.id} to record in {@code audit_log.actor_id}. */
    UUID auditActorId();
}
