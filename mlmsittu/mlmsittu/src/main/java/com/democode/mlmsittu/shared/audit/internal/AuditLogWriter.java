package com.democode.mlmsittu.shared.audit.internal;

import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * The only class in the application that inserts into {@code audit_log}.
 *
 * <p>Plain JDBC on purpose: the table is partitioned and append-only, the application DB role has
 * no {@code UPDATE} or {@code DELETE} on it, and there is no reason to give Hibernate a managed
 * entity it could dirty-check and try to rewrite.
 */
@Component
public class AuditLogWriter {

    private static final Logger log = LoggerFactory.getLogger(AuditLogWriter.class);

    private static final String INSERT =
            """
            INSERT INTO audit_log (actor_id, action, entity_type, entity_id, before, after, ip)
            VALUES (?, ?, ?, ?, ?::jsonb, ?::jsonb, ?::inet)
            """;

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public AuditLogWriter(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    /** Writes inside the caller's transaction, so the row commits with the change it describes. */
    public void write(
            UUID actorId,
            String action,
            String entityType,
            UUID entityId,
            Object before,
            Object after,
            String ip) {

        jdbc.update(
                INSERT,
                actorId,
                action,
                entityType,
                entityId,
                toJson(before),
                toJson(after),
                ip);
    }

    /**
     * Writes in a suspended, independent transaction — for recording an action that <em>failed</em>.
     *
     * <p>The surrounding transaction is on its way to rollback; a row written inside it would
     * disappear along with the change, which is precisely backwards for a rejected login or a
     * blocked privileged action.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void writeIndependently(
            UUID actorId,
            String action,
            String entityType,
            UUID entityId,
            Object before,
            Object after,
            String ip) {

        write(actorId, action, entityType, entityId, before, after, ip);
    }

    private String toJson(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JacksonException e) {
            // An unserialisable snapshot must not abort the business transaction, but losing the
            // diff silently would be worse — record the failure in the column itself.
            log.warn("Could not serialise audit snapshot of type {}", value.getClass().getName(), e);
            return "{\"_error\":\"snapshot could not be serialised\"}";
        }
    }
}
